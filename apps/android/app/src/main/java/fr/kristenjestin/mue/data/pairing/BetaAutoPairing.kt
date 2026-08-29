package fr.kristenjestin.mue.data.pairing

import android.util.Log
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Les trois valeurs qu'une `beta` configurée porte — ensemble, ou pas du tout.
 *
 * **C'est ce type qui rend le chemin inerte ailleurs, et pas un `if`.** `default_server_address`,
 * `default_account_email` et `default_account_password` sont la chaîne vide dans `release`, `local`
 * et `debug` (`build.gradle.kts`, et `verifyReleaseCarriesNoBetaDefaults` le relit sur l'APK plutôt
 * que de croire le script). Le constructeur est privé et [of] est le seul chemin vers une
 * instance : une des trois vide, [of] rend `null`, et il n'y a alors aucun objet à qui demander
 * quoi que ce soit. [BetaAutoPairing] exige ce type en paramètre, donc « pas de valeurs, pas de
 * tentative » n'est pas une garde qu'un remaniement peut déplacer ou inverser — c'est une
 * impossibilité de construire l'appelant.
 *
 * La formulation inverse — un `BetaAutoPairing` qui prendrait trois `String` et déciderait
 * lui-même — compile aussi bien et se lit presque pareil, mais elle met la garantie qui protège
 * une build publiable au même rang qu'une condition ordinaire, à un déplacement de ligne près.
 *
 * `isNotBlank` sur les trois, comme `SyncViewModel.seedForm` : « vide » veut dire la même chose des
 * deux côtés de la ressource, et le côté Gradle a déjà rogné (`localProperty`). Les valeurs sont
 * gardées telles quelles — rogner ici un mot de passe que la ressource porte tel quel enverrait
 * au serveur autre chose que ce que le formulaire manuel enverrait, pour le même `local.properties`.
 */
class BetaPairingDefaults private constructor(
    val serverAddress: String,
    val accountEmail: String,
    val accountPassword: String,
) {

    companion object {

        /** Les trois, ou `null`. Il n'y a pas de troisième réponse, et c'est tout le mécanisme. */
        fun of(
            serverAddress: String,
            accountEmail: String,
            accountPassword: String,
        ): BetaPairingDefaults? {
            if (serverAddress.isBlank() || accountEmail.isBlank() || accountPassword.isBlank()) {
                return null
            }
            return BetaPairingDefaults(serverAddress, accountEmail, accountPassword)
        }
    }
}

/**
 * Ce qu'un lancement de processus a fait de l'appairage automatique.
 *
 * Une valeur de retour et non un état gardé : un échec ici ne doit rien laisser derrière lui
 * (`MueApplication` l'ignore), et la forme sealed existe pour que les tests distinguent « déjà
 * appairé » de « rien tenté » de « tenté et raté », trois situations qu'un `Boolean` confondrait.
 * Même raison et même forme que [fr.kristenjestin.mue.data.local.database.CiqualSeedOutcome].
 */
sealed interface AutoPairingOutcome {

    /** La tentative unique de ce processus a déjà été consommée. Rien n'a été relu ni redemandé. */
    data object AlreadyAttempted : AutoPairingOutcome

    /** `sync_state` porte déjà un serveur. Rien n'a été remplacé, rafraîchi ni vérifié. */
    data object AlreadyPaired : AutoPairingOutcome

    /** Le téléphone est appairé, et la synchronisation initiale de PRD 9.2 a eu lieu. */
    data class Paired(val serverName: String, val account: String) : AutoPairingOutcome

    /** L'appairage n'a pas eu lieu. Rien n'a été écrit, et rien ne sera retenté. */
    data class Failed(val reason: String) : AutoPairingOutcome
}

/**
 * Le journal de ce chemin (garantie « observable »).
 *
 * Une interface plutôt qu'un appel direct à `android.util.Log`, exactement pour la raison que
 * `ScaleLog` écrit en toutes lettres : `Log` est une classe stub dans les tests JVM, dont chaque
 * méthode lève `RuntimeException("not mocked")`, et le module ne pose pas `returnDefaultValues`.
 * Un `Log.i` dans [BetaAutoPairing] rendrait rouge toute la suite ci-contre, celle qui doit tourner
 * sans Android. Le journal réel vit dans [AndroidAutoPairingLog], plus bas, qui n'est chargé que
 * par le conteneur d'injection : un test qui ne le nomme pas ne charge jamais `Log`.
 *
 * Une seule méthode et aucun niveau de gravité, parce qu'il n'y a rien de grave sur ce chemin : un
 * appairage automatique raté est un non-événement pour l'utilisateur, qui ouvre `Server settings`
 * et appuie sur un bouton comme avant. Ce que le journal sert, c'est de distinguer *raté* de
 * *jamais tenté* — sans trace, les deux se ressemblent depuis le téléphone.
 */
fun interface AutoPairingLog {

    fun log(message: String)

    companion object {
        /** Le journal des tests et des chemins sans Android : il n'écrit rien. */
        val NONE: AutoPairingLog = AutoPairingLog { }
    }
}

/**
 * L'appairage que la bêta fait toute seule au démarrage du processus, une fois par lancement.
 *
 * Le propriétaire réinstalle la bêta souvent et n'a pas de parcours d'inscription : les trois clés
 * de `local.properties` pré-remplissaient déjà le formulaire (AGENTS.md §4.5), ce qui laissait un
 * écran à ouvrir et un bouton à trouver après chaque installation. Ceci enlève les deux, et rien
 * d'autre : c'est [ServerPairing.pair] qui est appelée, la même que le bouton, donc le même
 * `probe`, la même vérification du bearer avant écriture, la même ligne `sync_state`, les mêmes
 * `PairingFailure` et la même synchronisation initiale. Un chemin réseau parallèle aurait été un
 * second comportement à prouver et à faire vieillir.
 *
 * ## Les quatre bornes, et où chacune est tenue
 *
 * 1. **Inerte partout ailleurs** — [BetaPairingDefaults] : sans les trois ressources, cet objet ne
 *    peut pas être construit, donc `release`, `local` et `debug` n'atteignent aucune de ces lignes.
 * 2. **Jamais sur un téléphone déjà appairé** — [store] est relu au début de [pairOnce] et
 *    `sync_state.server_url` fait foi. Un appairage existant n'est ni remplacé, ni rafraîchi, ni
 *    vérifié : la fonction sort avant d'avoir ouvert la moindre connexion. Ce n'est pas une
 *    politesse, c'est la même règle que `SyncViewModel.seedForm` applique au formulaire —
 *    `ServerPairing.pair` accepterait de ré-appairer le même compte, en remettant le curseur à zéro
 *    et en relisant tout le journal, pour un téléphone qui n'avait besoin de rien.
 * 3. **Une seule tentative par lancement** — [attempted], posé avant tout le reste. Better Auth
 *    limite le débit sans en-tête d'adresse fiable, donc sur un seau unique partagé : un mot de
 *    passe devenu faux qui produirait une tentative par seconde ne raterait pas seulement son
 *    appairage, il bloquerait celui que le propriétaire tente à la main sur le même serveur. Le
 *    drapeau est consommé même par le chemin « déjà appairé » : ce processus a posé sa question.
 * 4. **Un échec est silencieux** — [pairOnce] rend une valeur que `MueApplication` jette. Aucun
 *    dialogue, aucun état, aucune écriture : `ServerPairing` n'écrit rien tant que tout n'a pas
 *    marché, donc `Server settings` s'ouvre ensuite sur le formulaire pré-rempli d'avant, prêt pour
 *    un appui manuel (`SyncViewModelTest.aFullyConfiguredBetaFillsAllThreeFields` est cet état).
 *
 * Le nom dit `Beta` parce que c'est la variante d'où les trois ressources viennent, mais rien ici
 * ne lit une variante : ce qui borne le mécanisme est que les ressources soient vides ailleurs, et
 * c'est vérifié sur l'artefact et non sur une intention.
 */
class BetaAutoPairing(
    private val defaults: BetaPairingDefaults,
    /**
     * `sync_state`, par la même interface que [ServerPairing] écrit — donc la même ligne, lue
     * avant d'être potentiellement écrite, et une seule idée de ce que « appairé » veut dire.
     */
    private val store: PairingStore,
    /**
     * [ServerPairing.pair], passée comme appel et non comme objet.
     *
     * Même décision que `ServerPairing.firstSync` et que `HealthProfileSeeding.database` : le seul
     * moyen de prouver sur la JVM qu'aucun appel réseau ne part est que la chose qui partirait soit
     * un paramètre. Le conteneur y branche l'instance unique de `ServerPairing`, celle du bouton.
     */
    private val pair: suspend (address: String, email: String, password: String) -> PairingResult,
    /**
     * Sans valeur par défaut, comme `ServerPairing.cleartext` : un défaut est la façon dont un
     * appairage automatique finirait par ne rien journaliser chez quelqu'un qui n'a pas relu cette
     * ligne, et il n'y a qu'un seul appelant en production pour l'écrire.
     */
    private val log: AutoPairingLog,
) {

    /**
     * La tentative de ce processus, prise ou pas.
     *
     * `AtomicBoolean` et non un `Boolean` : rien ne garantit que l'unique appel de
     * `MueApplication.onCreate` restera l'unique appel, et un second appelant arrivant d'un autre
     * fil trouverait un `Boolean` non protégé dans l'état où il l'a lu. Le coût est nul, la
     * garantie devient une propriété de l'objet plutôt qu'une propriété de ses appelants.
     */
    private val attempted = AtomicBoolean(false)

    /**
     * Appaire ce téléphone si les conditions sont réunies, au plus une fois par processus.
     *
     * Ne lève rien : `ServerPairing.pair` nomme déjà chaque panne réseau en `PairingFailure`, et le
     * `catch` ci-dessous couvre ce qui resterait — Room, Keystore, une pile TLS. Un démarrage
     * d'application ne se termine pas sur une exception venue d'une commodité.
     */
    suspend fun pairOnce(): AutoPairingOutcome {
        // Avant la lecture de `sync_state` et non après : ce qui doit être unique est la question,
        // pas seulement l'appel réseau. Deux appels concurrents doivent laisser exactement un
        // d'entre eux passer, quel que soit l'état de la base.
        if (!attempted.compareAndSet(false, true)) return AutoPairingOutcome.AlreadyAttempted

        val storedServer = try {
            store.state()?.serverUrl?.takeUnless(String::isBlank)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // Une base illisible n'est pas une invitation à appairer : « on ne sait pas » se traite
            // comme « peut-être déjà appairé », qui est la seule des deux lectures dont l'erreur
            // ne coûte rien.
            val reason = failure.message ?: failure::class.java.simpleName
            log.log("automatic pairing skipped: sync_state could not be read ($reason)")
            return AutoPairingOutcome.Failed(reason)
        }

        if (storedServer != null) {
            log.log("automatic pairing skipped: this phone is already paired with $storedServer")
            return AutoPairingOutcome.AlreadyPaired
        }

        // L'adresse et le compte, jamais le mot de passe. L'APK le porte en clair et l'arbitrage
        // de `build.gradle.kts` le dit, mais `logcat` est lu par-dessus l'épaule, se copie dans un
        // rapport de bogue et sort d'un téléphone sans que l'APK sorte avec : rien n'oblige à
        // élargir une divulgation qui a été bornée à un artefact.
        log.log(
            "automatic pairing: ${defaults.serverAddress} as ${defaults.accountEmail} " +
                "(this build carries the three beta defaults)",
        )

        val result = try {
            pair(defaults.serverAddress, defaults.accountEmail, defaults.accountPassword)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            val reason = failure.message ?: failure::class.java.simpleName
            log.log("automatic pairing failed: $reason — open Server settings to pair by hand")
            return AutoPairingOutcome.Failed(reason)
        }

        return when (result) {
            is PairingResult.Paired -> {
                log.log("automatic pairing done: ${result.serverName} as ${result.account}")
                AutoPairingOutcome.Paired(serverName = result.serverName, account = result.account)
            }

            is PairingResult.Failed -> {
                // Le message est celui que le formulaire aurait affiché, et il reste ici : le
                // propriétaire n'a rien demandé, donc rien ne l'interrompt. Il le retrouvera mot
                // pour mot en appuyant sur `Connect`.
                log.log(
                    "automatic pairing failed: ${result.failure.message} — " +
                        "open Server settings to pair by hand",
                )
                AutoPairingOutcome.Failed(result.failure.message)
            }
        }
    }
}

/**
 * Le journal de ce chemin sur Android.
 *
 * `Log.i` et non `Log.w` : chaque ligne décrit une commodité qui a eu lieu ou non, jamais une faute
 * dont l'utilisateur doit s'occuper — un avertissement pour un non-événement finit par être filtré
 * avec les vrais. Volume borné par construction : au plus deux lignes par lancement de processus.
 */
internal object AndroidAutoPairingLog : AutoPairingLog {
    override fun log(message: String) {
        Log.i(AUTO_PAIRING_LOG_TAG, message)
    }
}

/** `adb logcat -s MuePairing` est la commande qui répond « tenté et raté » plutôt que « rien ». */
private const val AUTO_PAIRING_LOG_TAG = "MuePairing"
