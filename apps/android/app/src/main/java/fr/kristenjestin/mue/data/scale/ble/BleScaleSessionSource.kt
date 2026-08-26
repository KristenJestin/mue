package fr.kristenjestin.mue.data.scale.ble

import fr.kristenjestin.mue.domain.model.ScaleAdvertisement
import fr.kristenjestin.mue.domain.model.ScaleDevice
import fr.kristenjestin.mue.domain.model.ScaleDriver
import fr.kristenjestin.mue.domain.model.ScaleDriverRegistry
import fr.kristenjestin.mue.domain.model.ScaleDriverSession
import fr.kristenjestin.mue.domain.model.ScaleFrameEvent
import fr.kristenjestin.mue.domain.model.ScaleGattProfile
import fr.kristenjestin.mue.domain.model.ScaleLinkMode
import fr.kristenjestin.mue.domain.model.ScaleReading
import fr.kristenjestin.mue.domain.model.ScaleSessionState
import fr.kristenjestin.mue.domain.model.ScaleWrite
import fr.kristenjestin.mue.domain.model.Weight
import fr.kristenjestin.mue.domain.repository.ScaleRepository
import fr.kristenjestin.mue.domain.repository.ScaleSessionSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.UUID

/** Deux minutes, couvrant le scan, la connexion et l'attente de la montée (FR-SCALE-020). */
private const val SEARCH_WINDOW_MS = 120_000L

/** Dix secondes après le poids stable pour l'impédance (PRD_SCALE 14.3, point 5). */
private const val IMPEDANCE_WINDOW_MS = 10_000L

/**
 * Plafond d'une tentative de connexion.
 *
 * La pile d'Android abandonne d'elle-même après une trentaine de secondes, ce qui consommerait le
 * quart de la fenêtre de deux minutes pour une balance qui s'est rendormie entre l'annonce et la
 * connexion — le cas normal de FR-SCALE-021. Vingt secondes laissent le temps à une connexion
 * lente d'aboutir sur un téléphone modeste (PRD_SCALE 24) tout en gardant de quoi réessayer.
 */
private const val CONNECT_TIMEOUT_MS = 20_000L

/**
 * Le « court backoff » de FR-SCALE-020, croissant puis plafonné.
 *
 * Il ne s'agit pas de ménager un serveur : la balance ne réannonce sa présence qu'au réveil
 * (FR-SCALE-021), et repartir en scan dans la milliseconde ne ferait que brûler la radio sans
 * changer la réponse. Le plafond existe pour qu'une fenêtre de deux minutes ne se réduise pas à
 * deux tentatives.
 */
private val RECONNECT_BACKOFF_MS = longArrayOf(500L, 1_000L, 2_000L, 4_000L)

/**
 * La machine à états d'une pesée (PRD_SCALE 11, 21.2).
 *
 * **Ce qu'elle est.** L'unique implémentation de [ScaleSessionSource], et le seul endroit du module
 * où le cycle de vie d'une mesure est écrit. Elle ne décode aucune trame — c'est le travail pur des
 * pilotes — et ne parle à aucune API Android — c'est le travail de [ScaleTransport]. Ce qui reste
 * est exactement ce que PRD_SCALE 21.3 demande de couvrir « sans Bluetooth » : les fenêtres de
 * temps, le verrouillage sur la première balance, les bornes, la reprise silencieuse et la validité
 * du `sessionId`.
 *
 * **Le `sessionId`.** PRD_SCALE 21.2 : « La machine à états porte un `sessionId`. Chaque callback
 * BLE vérifie cet identifiant avant de modifier l'état, afin qu'une notification tardive d'une
 * ancienne liaison ne complète jamais une autre pesée. » Ici, ce contrôle est structurel : [publish]
 * et [conclude] sont les **seules** écritures de [state], toutes deux prennent le `sessionId` de
 * l'appelant et ne font rien s'il n'est plus l'actif. Fermer une session — [stop], [closeSession],
 * expiration, [retry] — commence toujours par invalider l'identifiant, *avant* d'annuler quoi que
 * ce soit. L'annulation coopérative des coroutines est un second rideau, pas le premier : elle
 * n'est pas instantanée, et une trame déjà en vol au moment de la fermeture doit être rejetée par
 * une valeur, pas par une course.
 *
 * **Un seul fil.** Rien ici n'est synchronisé. La machine est confinée au dispatcher de [scope] —
 * `Dispatchers.Main.immediate` dans l'application, le dispatcher de test sous `runTest`. Les
 * callbacks BLE, eux, arrivent sur un fil quelconque : ils traversent les files et les
 * continuations de [ScaleLink], jamais l'état d'ici. [activeSessionId] est tout de même `@Volatile`
 * parce qu'il est la garde décrite ci-dessus, et qu'une garde ne doit pas dépendre d'un cache de
 * processeur.
 *
 * @param transport La liaison. Interface, pour que la machine se teste sans radio (PRD_SCALE 21.3).
 * @param scales Les balances appairées. Sans aucune, il ne se passe strictement rien (FR-SCALE-020).
 * @param drivers Le registre de pilotes, câblé par le conteneur avec ou sans pilote fictif.
 * @param scope La portée de la session. Sa durée de vie est celle de l'application ; les sessions
 *   sont des travaux qu'on y lance et qu'on y annule.
 * @param now L'horloge de [ScaleReading.receivedAt]. Injectée pour que les tests n'aient pas à
 *   comparer des instants qu'ils ne contrôlent pas.
 * @param newSessionId La fabrique d'identifiants de session. Injectée pour que les tests puissent
 *   nommer les sessions et lire leurs assertions.
 * @param log Le journal technique de PRD_SCALE 18.5. Muet par défaut.
 */
internal class BleScaleSessionSource(
    private val transport: ScaleTransport,
    private val scales: ScaleRepository,
    private val drivers: ScaleDriverRegistry,
    private val scope: CoroutineScope,
    private val now: () -> Instant = Instant::now,
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
    private val log: ScaleLog = ScaleLog.NONE,
) : ScaleSessionSource {

    /**
     * `Absent` au départ, et c'est le choix prudent : c'est le seul état qui garantit qu'aucun
     * élément d'interface n'est ajouté à `Entry` (PRD_SCALE 18.1). Le collecteur d'[init] corrige
     * en `Idle` dès la première lecture du dépôt, et les deux états s'affichent de la même façon —
     * pas du tout — donc la correction est invisible.
     */
    private val _state = MutableStateFlow<ScaleSessionState>(ScaleSessionState.Absent)

    override val state: StateFlow<ScaleSessionState> = _state.asStateFlow()

    /** La session en cours, ou `null`. La garde de PRD_SCALE 21.2. */
    @Volatile
    private var activeSessionId: String? = null

    private var sessionJob: Job? = null

    /** Le compte à rebours de deux minutes. Volontairement **hors** de [sessionJob] : voir [openSession]. */
    private var deadlineJob: Job? = null

    private var hasRegisteredScale: Boolean = false

    init {
        // FR-SCALE-020 : « aucune balance enregistrée : aucun scan, aucune permission demandée,
        // aucun élément d'interface ajouté. » L'écran doit pouvoir le savoir sans appeler start(),
        // donc l'état au repos suit le dépôt. Ce collecteur ne scanne rien et ne demande rien ; il
        // n'écrit que lorsque aucune session n'est ouverte et que l'état affiché est déjà un état
        // de repos, pour ne jamais effacer un NotFound ni une mesure reçue.
        scope.launch {
            scales.observeAll().collect { devices ->
                hasRegisteredScale = devices.isNotEmpty()
                if (activeSessionId == null && _state.value.isResting()) {
                    _state.value = restingState()
                }
            }
        }
    }

    /**
     * Ouvre une session de deux minutes (FR-SCALE-020).
     *
     * Sans effet lorsqu'une session est déjà en cours — c'est écrit dans le contrat de
     * [ScaleSessionSource] — et lorsque l'état est **conclu** : `Stable`, `Complete`, `OutOfRange`
     * et `NotFound` sont des fins de session, et une recomposition qui rappellerait [start] ne doit
     * pas relancer un scan derrière une mesure déjà posée (FR-SCALE-023) ni redémarrer en boucle
     * après le timeout (FR-SCALE-020). Le seul chemin de relance est [retry].
     *
     * `Unavailable` n'est pas une fin de session : c'est un constat qui peut avoir changé — la
     * radio rallumée depuis le volet système, une permission accordée dans les réglages — et le
     * rappel de [start] ne coûte qu'une relecture, sans scan si le constat tient toujours.
     */
    override fun start() {
        if (activeSessionId != null) return
        if (_state.value.isConcluded()) return
        openSession()
    }

    /**
     * Arrête tout : `Entry` n'est plus visible (FR-SCALE-020, PRD_SCALE 3.7).
     *
     * Identique à [closeSession] dans ses effets — invalidation, annulation, retour au repos — et
     * distincte par son appelant seul. Les garder séparées est le contrat de [ScaleSessionSource] :
     * l'écran n'a pas à savoir qu'elles coïncident, et rien ne garantit qu'elles coïncideront
     * toujours.
     */
    override fun stop() = endSession()

    /** Une nouvelle session pleine de deux minutes, depuis n'importe quel état (FR-SCALE-020). */
    override fun retry() {
        endSession()
        openSession()
    }

    /**
     * Clôt la session sans quitter l'écran (FR-SCALE-023, BR-SCALE-012).
     *
     * L'état retombe au repos : `Entry` continue d'afficher **sa** valeur, celle que l'utilisateur
     * vient d'enregistrer ou de retoucher, et l'indicateur discret de balance disparaît puisqu'il
     * n'y a plus rien à chercher. Toute trame arrivée après cet appel est rejetée par la garde de
     * `sessionId` : « la composition n'est jamais ajoutée silencieusement après la confirmation
     * `Saved` ».
     */
    override fun closeSession() = endSession()

    // region cycle de vie d'une session

    private fun openSession() {
        val sessionId = newSessionId()
        activeSessionId = sessionId
        sessionJob = scope.launch {
            try {
                runSession(sessionId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                // PRD_SCALE 18.5 : une panne technique ne produit aucun message. La session se
                // referme, l'écran reste pleinement utilisable à la main (BR-SCALE-011).
                log.log("scale session failed: ${failure.message}")
                conclude(sessionId, restingState())
            }
        }
    }

    /**
     * Ferme la session courante et revient au repos.
     *
     * L'ordre compte : l'identifiant est invalidé **d'abord**, l'annulation vient ensuite. Une
     * trame déjà en vol est ainsi rejetée par la garde même si l'annulation n'a pas encore été
     * observée par la coroutine de session.
     */
    private fun endSession() {
        activeSessionId = null
        deadlineJob?.cancel()
        deadlineJob = null
        sessionJob?.cancel()
        sessionJob = null
        _state.value = restingState()
    }

    /**
     * Le compte à rebours de FR-SCALE-020, lancé dans [scope] et **non** dans [sessionJob].
     *
     * S'il en était l'enfant, il faudrait annuler la session pour écrire `NotFound` et écrire
     * `NotFound` depuis la coroutine qu'on vient d'annuler : l'ordre serait à la fois nécessaire
     * et impossible à garantir. Séparé, il annule et conclut de l'extérieur, ce qui est exactement
     * la formulation du PRD — « Mue annule le scan, ferme toute liaison en attente et invalide le
     * `sessionId` ».
     */
    private fun startDeadline(sessionId: String) {
        deadlineJob = scope.launch {
            delay(SEARCH_WINDOW_MS)
            if (activeSessionId != sessionId) return@launch
            // « Le délai de deux minutes couvre le scan, la connexion et l'attente que
            // l'utilisateur monte sur la balance. » Une fois le poids stable reçu, la fenêtre de
            // dix secondes de l'impédance lui succède et n'est pas bornée par celle-ci : un poids
            // arrivé à une minute cinquante-neuf mérite sa composition corporelle.
            if (_state.value.stableReading != null) return@launch
            sessionJob?.cancel()
            sessionJob = null
            conclude(sessionId, ScaleSessionState.NotFound)
        }
    }

    // endregion

    // region la session elle-même

    private suspend fun runSession(sessionId: String) {
        // L'ordre de ces deux vérifications est une exigence, pas une préférence. FR-SCALE-020 :
        // sans balance enregistrée, « aucun scan, aucune permission demandée ». Interroger la
        // disponibilité d'abord reviendrait à interroger l'état des permissions de quelqu'un qui
        // n'a jamais appairé de balance.
        val devices = scales.getAll()
        if (activeSessionId != sessionId) return
        if (devices.isEmpty()) {
            conclude(sessionId, ScaleSessionState.Absent)
            return
        }

        val unavailable = transport.availability()
        if (unavailable != null) {
            conclude(sessionId, ScaleSessionState.Unavailable(unavailable))
            return
        }

        startDeadline(sessionId)

        var attempt = 0
        while (currentCoroutineContext().isActive && activeSessionId == sessionId) {
            // L'état repasse en recherche **avant** le backoff : pas une seconde ne doit s'écouler
            // sur un « connecté » qui ne l'est plus. La reprise est silencieuse, sans message ni
            // action demandée (PRD_SCALE 18.5, FR-SCALE-021).
            publish(sessionId, ScaleSessionState.Searching)
            if (attempt > 0) {
                val index = (attempt - 1).coerceAtMost(RECONNECT_BACKOFF_MS.lastIndex)
                delay(RECONNECT_BACKOFF_MS[index])
            }
            val finished = try {
                // Le scan est **dans** le `try`, et pas seulement la liaison : `ScaleTransport.scan`
                // échoue par `ScaleTransportException` dès que la plateforme refuse le scan, et le
                // refus le plus courant n'est pas la permission mais la limitation d'Android à cinq
                // démarrages par trente secondes. Laissé dehors, ce refus remontait jusqu'au `catch`
                // d'`openSession`, qui referme la session sur `Idle` : plus de scan, plus de
                // décompte, et surtout plus de `Scale not found · Try again` — l'utilisateur n'avait
                // aucun geste pour relancer. Ici, il est traité comme une liaison tombée : reprise
                // silencieuse sur le temps restant, puis `NotFound` à l'échéance (FR-SCALE-020,
                // PRD_SCALE 18.5).
                val candidate = findCandidate(devices)
                runLink(sessionId, candidate)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                // FR-SCALE-021 : « une déconnexion n'affiche pas d'erreur et ne demande aucune
                // action ». On reprend sur le temps restant, en silence (PRD_SCALE 18.5).
                log.log("scale link failed: ${failure.message}")
                false
            }
            if (finished) return
            attempt += 1
        }
    }

    /**
     * La première balance enregistrée qui répond (FR-SCALE-015).
     *
     * `first()` porte deux exigences à lui seul : il annule le flux dès la première candidate, donc
     * « le scan s'arrête immédiatement lorsqu'une balance candidate est trouvée » (FR-SCALE-020), et
     * il ignore tout ce qui suit, donc « dès qu'une connexion commence, les autres balances sont
     * ignorées pour cette session ». Aucun écran ne demande de choisir, aucune balance n'est
     * principale.
     */
    private suspend fun findCandidate(devices: List<ScaleDevice>): Candidate =
        transport.scan()
            .mapNotNull { advertisement -> candidateFor(advertisement, devices) }
            .first()

    /**
     * Une annonce devient une candidate si, et seulement si, une balance **enregistrée** répond à
     * son adresse et que son pilote est toujours livré (FR-SCALE-001).
     *
     * Le rattachement par le nom n'a délibérément pas sa place ici : il est « proposé, jamais
     * silencieux », et une session de pesée ne peut rien proposer. Voir [ScaleMatching].
     */
    private fun candidateFor(
        advertisement: ScaleAdvertisement,
        devices: List<ScaleDevice>,
    ): Candidate? {
        val device = ScaleMatching.matchByAddress(devices, advertisement) ?: return null
        val driver = drivers.byId(device.driverId)
        if (driver == null) {
            // Une balance appairée par une version antérieure peut référencer un pilote retiré
            // depuis : le cas se lit, il ne plante pas (PRD_SCALE 9.2).
            log.log("no driver ${device.driverId} for paired scale")
            return null
        }
        val profile = driver.gattProfile
        if (driver.linkMode != ScaleLinkMode.GATT || profile == null) {
            // Le mode ADVERTISEMENT existe dans la nature mais aucun pilote livré ne l'utilise ;
            // l'ouvrir ici sans matériel pour l'éprouver serait du code jamais exécuté.
            log.log("driver ${driver.id} is advertisement-only, unsupported in V1")
            return null
        }
        return Candidate(device, driver, profile, advertisement)
    }

    /**
     * Une tentative de liaison, du `connectGatt` à la fin de la pesée.
     *
     * @return `true` lorsque la session est **conclue** — poids stable exploité, ou stable hors
     *   bornes — et qu'il ne faut plus rien chercher. `false` lorsque la tentative a échoué avant
     *   tout poids stable : FR-SCALE-020 autorise alors la reprise du scan sur le temps restant.
     */
    private suspend fun runLink(sessionId: String, candidate: Candidate): Boolean {
        publish(sessionId, ScaleSessionState.Connecting)

        /*
         * La liaison est retenue **dans** le bloc, avant que sa valeur ne traverse la frontière
         * annulable de `withTimeoutOrNull`, et le `finally` porte sur elle et non sur `link`.
         *
         * `transport.connect` rend un `BluetoothGatt` **déjà ouvert**. Si l'annulation arrive
         * pendant que cette valeur remonte — le délai de vingt secondes qui expire à cet instant,
         * ou `endSession()` déclenché par un `stop()` au passage en arrière-plan, ce qui est le cas
         * courant puisqu'une connexion dure plusieurs secondes — la continuation est reprise avec
         * l'annulation et la valeur est **jetée**. Écrit `val link = withTimeoutOrNull { … } ?:
         * return false`, ce GATT devenait alors inaccessible sans que personne ne l'ait fermé.
         *
         * Android n'accorde qu'une trentaine de clients GATT par processus et n'en récupère aucun :
         * chaque fuite en consomme un définitivement, et une fois le quota épuisé toute connexion
         * échoue en silence jusqu'au redémarrage de l'application. `close()` est idempotent et non
         * suspendant (voir [ScaleLink.close]), donc l'appeler ici est sans risque et suffit.
         */
        var opened: ScaleLink? = null
        try {
            val link = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                transport.connect(candidate.advertisement, candidate.profile).also { opened = it }
            } ?: return false

            if (activeSessionId != sessionId) return true

            // « markSeen sur la balance à chaque contact réussi » : l'adresse et le nom annoncé
            // sont des indices rafraîchis à chaque rencontre, l'id et le nom donné ne bougent pas
            // (FR-SCALE-001).
            //
            // La garde encadre cet appel des **deux** côtés. `markSeen` franchit une suspension —
            // une transaction Room — après la vérification qui la précède, et un `stop()` peut
            // invalider la session pendant ce temps. La relecture d'après ne défait pas la ligne
            // déjà écrite, mais elle est ce qui empêche la séquence de continuer au nom d'une
            // session close : sans elle, la machine enchaînait sur l'attente des trames d'une
            // liaison que plus personne n'observait (PRD_SCALE 21.2).
            scales.markSeen(
                id = candidate.device.id,
                address = candidate.advertisement.address,
                advertisedName = candidate.advertisement.name ?: candidate.device.advertisedName,
                at = now(),
            )
            if (activeSessionId != sessionId) return true

            val protocol = candidate.driver.newSession()
            // PRD_SCALE 14.3 : la séquence part une fois l'abonnement effectif, ce que le contrat
            // de ScaleTransport.connect garantit, et une écriture à la fois.
            sendAll(sessionId, link, protocol.onSubscribed())
            publish(sessionId, ScaleSessionState.WaitingForStepOn)

            return when (val outcome = awaitStableWeight(sessionId, link, protocol, candidate)) {
                is WeightOutcome.Stable -> {
                    awaitImpedance(sessionId, link, protocol, candidate, outcome.reading)
                    true
                }

                WeightOutcome.OutOfRange -> true
                WeightOutcome.Stale -> true
                WeightOutcome.LinkLost -> false
            }
        } finally {
            opened?.close()
        }
    }

    /**
     * Le flux de poids, jusqu'au premier poids **stable** (PRD_SCALE 11, 14.4).
     *
     * Les bornes de FR-SCALE-024 s'appliquent différemment selon la stabilité, et la différence est
     * le cœur de l'exigence : « pendant le flux instable, les valeurs hors bornes sont ignorées
     * silencieusement », alors qu'« une mesure **stable** hors bornes » se dit. Ce n'est pas un cas
     * théorique : des appuis à la main ont produit des mesures stables entre 14 et 21 kg pendant la
     * validation du protocole.
     */
    private suspend fun awaitStableWeight(
        sessionId: String,
        link: ScaleLink,
        protocol: ScaleDriverSession,
        candidate: Candidate,
    ): WeightOutcome {
        while (true) {
            val frame = link.nextFrame() ?: return WeightOutcome.LinkLost
            // La garde de PRD_SCALE 21.2, sur le chemin de chaque trame reçue.
            if (activeSessionId != sessionId) return WeightOutcome.Stale

            when (val event = protocol.onFrame(frame)) {
                ScaleFrameEvent.Ignored -> Unit

                is ScaleFrameEvent.Rejected -> log.log("rejected frame: ${event.reason}")

                // Une impédance avant tout poids ne veut rien dire ; on l'acquitte pour ne pas
                // laisser le protocole en suspens, et on continue d'attendre le poids.
                is ScaleFrameEvent.Impedance -> sendAll(sessionId, link, event.replies)

                is ScaleFrameEvent.Weight -> {
                    val hundredths = roundedToStep(event.hundredthsKg)
                    val withinBounds = Weight.ofHundredthsOrNull(hundredths) != null

                    if (!event.stable) {
                        if (withinBounds) publish(sessionId, ScaleSessionState.Measuring(hundredths))
                        sendAll(sessionId, link, event.replies)
                        continue
                    }

                    if (!withinBounds) {
                        // Les réponses du pilote ne sont **pas** émises : l'acquittement d'un poids
                        // stable est ce qui lance la mesure d'impédance (PRD_SCALE 14.3 point 4), et
                        // il n'y a pas de composition corporelle à espérer d'un poids que Mue refuse
                        // d'enregistrer.
                        conclude(sessionId, ScaleSessionState.OutOfRange(hundredths))
                        return WeightOutcome.OutOfRange
                    }

                    val reading = ScaleReading(
                        sessionId = sessionId,
                        weightHundredthsKg = hundredths,
                        isStable = true,
                        impedanceOhm = null,
                        receivedAt = now(),
                        scaleId = candidate.device.id,
                    )
                    // L'état d'abord, l'acquittement ensuite : la valeur se pose sur la règle sans
                    // attendre un aller-retour GATT, et l'enregistrement est déjà possible
                    // (FR-SCALE-023, « le poids stable suffit »).
                    publish(sessionId, ScaleSessionState.Stable(reading))
                    sendAll(sessionId, link, event.replies)
                    return WeightOutcome.Stable(reading)
                }
            }
        }
    }

    /**
     * Les dix secondes qui suivent le poids stable (PRD_SCALE 14.3 point 5).
     *
     * Trois issues mènent à `Complete` et une seule d'entre elles allume le conseil « pieds nus ».
     * [ScaleSessionState.Complete.impedanceRefused] ne vaut `true` que lorsque le pilote a
     * **explicitement** reconnu le marqueur de mesure impossible (BR-SCALE-005, FR-BODY-002) — le
     * `0xFFFF` de PRD_SCALE 14.5, fréquent dès que le contact des deux pieds n'est pas établi. Un
     * délai écoulé ou une liaison tombée ne disent rien des pieds de l'utilisateur : les confondre
     * ferait afficher un conseil gratuit à quelqu'un dont la balance s'est simplement rendormie.
     *
     * Un pilote qui déclare ne pas fournir d'impédance n'attend pas : `Complete` immédiatement, ce
     * qui est ce que FR-SCALE-030 promet d'un modèle sans impédance.
     */
    private suspend fun awaitImpedance(
        sessionId: String,
        link: ScaleLink,
        protocol: ScaleDriverSession,
        candidate: Candidate,
        reading: ScaleReading,
    ) {
        if (!candidate.driver.capabilities.providesImpedance) {
            conclude(sessionId, ScaleSessionState.Complete(reading, impedanceRefused = false))
            return
        }

        val outcome = withTimeoutOrNull(IMPEDANCE_WINDOW_MS) {
            readImpedance(sessionId, link, protocol)
        }

        val concluded = when (outcome) {
            // Délai écoulé : « au-delà, la session devient Complete sans composition ».
            null -> ScaleSessionState.Complete(reading, impedanceRefused = false)
            // Liaison tombée avant l'impédance : une absence, jamais un refus.
            ImpedanceOutcome.LinkLost -> ScaleSessionState.Complete(reading, impedanceRefused = false)
            // Session invalidée entre-temps — enregistrement, retouche, écran quitté. On ne publie
            // rien du tout (BR-SCALE-012).
            ImpedanceOutcome.Stale -> return
            ImpedanceOutcome.Refused -> ScaleSessionState.Complete(reading, impedanceRefused = true)
            is ImpedanceOutcome.Measured -> ScaleSessionState.Complete(
                reading.copy(impedanceOhm = outcome.ohm),
                impedanceRefused = false,
            )
        }
        conclude(sessionId, concluded)
    }

    private suspend fun readImpedance(
        sessionId: String,
        link: ScaleLink,
        protocol: ScaleDriverSession,
    ): ImpedanceOutcome {
        while (true) {
            val frame = link.nextFrame() ?: return ImpedanceOutcome.LinkLost
            if (activeSessionId != sessionId) return ImpedanceOutcome.Stale

            when (val event = protocol.onFrame(frame)) {
                ScaleFrameEvent.Ignored -> Unit

                is ScaleFrameEvent.Rejected -> log.log("rejected frame: ${event.reason}")

                // La balance répète sa trame stable ; le pilote sait n'acquitter qu'une fois
                // (PRD_SCALE 14.3 point 6), et aucune autre source ne peut plus remplacer la mesure
                // (FR-SCALE-015).
                is ScaleFrameEvent.Weight -> sendAll(sessionId, link, event.replies)

                is ScaleFrameEvent.Impedance -> {
                    sendAll(sessionId, link, event.replies)
                    return event.ohm?.let(ImpedanceOutcome::Measured) ?: ImpedanceOutcome.Refused
                }
            }
        }
    }

    // endregion

    // region écritures, état, bornes

    /**
     * Les écritures du pilote, dans l'ordre, **une à la fois** (PRD_SCALE 14.3).
     *
     * La garde de `sessionId` est relue **entre chaque écriture**, et pas seulement à l'entrée.
     * `link.write` suspend jusqu'à l'acquittement de la pile ; un `stop()` — l'utilisateur quitte
     * `Entry`, l'application passe en arrière-plan — peut invalider la session pendant cette
     * suspension, et sans cette relecture les octets suivants partaient quand même vers la balance.
     * Aucun état n'était corrompu, mais PRD_SCALE 21.2 promet « invalider d'abord, annuler
     * ensuite » : l'annulation coopérative n'est pas instantanée, et la promesse ne vaut que si
     * elle va jusqu'au fil.
     */
    private suspend fun sendAll(sessionId: String, link: ScaleLink, writes: List<ScaleWrite>) {
        for (write in writes) {
            if (activeSessionId != sessionId) return
            link.write(write)
        }
    }

    /** La seule écriture d'état d'une session vivante. Garde de PRD_SCALE 21.2 incluse. */
    private fun publish(sessionId: String, next: ScaleSessionState) {
        if (activeSessionId == sessionId) _state.value = next
    }

    /**
     * Publie un état **et ferme la session** : plus aucune trame ne pourra modifier l'état, et
     * aucun scan ne redémarrera tant que [retry] ou une réouverture d'`Entry` ne l'aura pas demandé.
     */
    private fun conclude(sessionId: String, next: ScaleSessionState) {
        if (activeSessionId != sessionId) return
        activeSessionId = null
        deadlineJob?.cancel()
        deadlineJob = null
        _state.value = next
    }

    private fun restingState(): ScaleSessionState =
        if (hasRegisteredScale) ScaleSessionState.Idle else ScaleSessionState.Absent

    /**
     * L'arrondi au pas de `0.05 kg`, « appliqué à la frontière du domaine, pour tout pilote »
     * (PRD_SCALE 14.4).
     *
     * La HB9027 n'émet que des multiples de cinq centièmes — sa résolution native coïncide avec le
     * pas de PRD BR-003 — mais c'est « une propriété de cet appareil, pas une garantie de la
     * famille ». Sans cet arrondi, un futur pilote au centième produirait des `ScaleReading` que
     * `Weight` accepterait comme valeurs mais qui tomberaient entre deux crans de la règle, et
     * l'écart n'apparaîtrait qu'à l'affichage. Les deux bornes étant elles-mêmes des multiples du
     * pas, arrondir d'abord ne peut jamais faire sortir du domaine une valeur qui y était.
     */
    private fun roundedToStep(hundredthsKg: Int): Int {
        if (hundredthsKg <= 0) return hundredthsKg
        val step = Weight.STEP_HUNDREDTHS
        return ((hundredthsKg + step / 2) / step) * step
    }

    /** Les états où rien ne tourne et où rien n'a encore été trouvé. */
    private fun ScaleSessionState.isResting(): Boolean =
        this is ScaleSessionState.Absent || this is ScaleSessionState.Idle

    /**
     * Les fins de session, celles qu'un [start] involontaire ne doit pas balayer : une mesure posée
     * (FR-SCALE-023) et le timeout de recherche (FR-SCALE-020, « aucun scan ne redémarre en
     * boucle »).
     */
    private fun ScaleSessionState.isConcluded(): Boolean = when (this) {
        is ScaleSessionState.Stable,
        is ScaleSessionState.Complete,
        is ScaleSessionState.OutOfRange,
        ScaleSessionState.NotFound,
        -> true

        else -> false
    }

    // endregion

    /** Une balance enregistrée qui vient de répondre, avec de quoi lui parler. */
    private data class Candidate(
        val device: ScaleDevice,
        val driver: ScaleDriver,
        val profile: ScaleGattProfile,
        val advertisement: ScaleAdvertisement,
    )

    private sealed interface WeightOutcome {
        data class Stable(val reading: ScaleReading) : WeightOutcome

        /** Poids stable hors de `30.0–250.0 kg` : session conclue, rien d'enregistrable. */
        data object OutOfRange : WeightOutcome

        /** La liaison est tombée avant tout poids stable : reprise silencieuse (PRD_SCALE 18.5). */
        data object LinkLost : WeightOutcome

        /** La session a été invalidée pendant l'attente. Rien à publier. */
        data object Stale : WeightOutcome
    }

    private sealed interface ImpedanceOutcome {
        data class Measured(val ohm: Int) : ImpedanceOutcome

        /** Le pilote a explicitement signalé une mesure impossible (BR-SCALE-005). */
        data object Refused : ImpedanceOutcome

        data object LinkLost : ImpedanceOutcome

        data object Stale : ImpedanceOutcome
    }
}
