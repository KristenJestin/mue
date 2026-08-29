package fr.kristenjestin.mue.ui.scale

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.kristenjestin.mue.MueApplication
import fr.kristenjestin.mue.data.scale.ble.ScaleMatching
import fr.kristenjestin.mue.domain.model.ScaleAdvertisement
import fr.kristenjestin.mue.domain.model.ScaleDevice
import fr.kristenjestin.mue.domain.model.ScaleDriverRegistry
import fr.kristenjestin.mue.domain.repository.ScaleRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * Le flux d'appairage : chercher, reconnaître, associer (FR-SCALE-011, 012, FR-SCALE-001).
 *
 * Trois règles gouvernent ce fichier, et aucune n'est un détail d'affichage.
 *
 * **Les appareils non pris en charge sont listés.** Ils ne servent à rien fonctionnellement, et
 * c'est précisément pourquoi il faut les montrer : sans eux, l'utilisateur dont la balance n'a pas
 * encore de pilote voit une liste vide et conclut à une panne Bluetooth. Avec eux, il voit que Mue
 * trouve son appareil et ne sait pas encore lui parler — ce qui est vrai, et sur quoi il peut agir.
 *
 * **L'association est immédiate** (FR-SCALE-012). Aucun appairage système, aucun code, aucune
 * saisie de secret, aucune pesée d'essai : la balance de référence ne demande pas de *bonding*, et
 * exiger un appairage Android là où le protocole ne l'impose pas ajouterait une étape et un mode de
 * panne pour rien. Le nom par défaut est celui du modèle, et il se remplace depuis la fiche.
 *
 * **Le rattachement est proposé, jamais silencieux** (FR-SCALE-001). Quand un appareil découvert
 * porte le même nom annoncé et le même pilote qu'une balance enregistrée dont l'adresse ne répond
 * plus, Mue pose la question au lieu de décider : deux balances identiques dans un même foyer ne
 * doivent pas fusionner à l'insu de leur propriétaire. Répondre « non » appaire un second appareil,
 * répondre « oui » met l'adresse à jour en conservant l'`id`, le nom donné et l'historique.
 *
 * **Aucune permission n'est demandée ici** : l'écran lit `rememberScalePermissions()`, en pousse le
 * résultat par [onGateChanged], et n'appelle [onScanRequested] que lorsque plus rien ne s'y oppose.
 * `request()` est déclenché par un geste délibéré, dans le contexte du premier appairage
 * (FR-SCALE-025).
 */
internal class ScaleScanViewModel(
    private val scales: ScaleRepository,
    private val drivers: ScaleDriverRegistry,
    private val discovery: ScaleDiscovery,
    private val clock: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {

    /** Les annonces reçues, la dernière de chaque adresse. Une balance annonce plusieurs fois. */
    private val discovered = MutableStateFlow<Map<String, ScaleAdvertisement>>(emptyMap())

    private val transient = MutableStateFlow(Transient())

    private var scan: Job? = null
    private var countdown: Job? = null

    val state: StateFlow<ScaleScanUiState> = combine(
        discovered,
        scales.observeAll(),
        transient,
    ) { advertisements, registered, phase ->
        val recognised = advertisements.values.recognise(registered, advertisements.keys)
        ScaleScanUiState(
            gate = phase.gate,
            scanning = phase.scanning,
            started = phase.started,
            recognised = recognised,
            unsupported = advertisements.values.unsupported(),
            // Dérivée plutôt que mémorisée : une question posée sur un appareil qui vient d'être
            // appairé par ailleurs disparaît d'elle-même au lieu de rester à l'écran.
            proposal = phase.proposalAddress
                ?.let { address -> recognised.firstOrNull { it.address == address } }
                ?.let { device -> device.reattachTo?.let { ReattachProposal(device, it) } },
            pairedScaleId = phase.pairedScaleId,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
        initialValue = ScaleScanUiState(),
    )

    /**
     * Ce qu'Android autorise, tel que l'écran vient de le lire (PRD_SCALE 16.1, 18.5).
     *
     * Un scan en cours s'arrête si la condition se referme — la radio coupée depuis le volet des
     * réglages pendant que la liste tourne — parce que continuer produirait un silence qui
     * ressemble à une absence de balance et n'en est pas une.
     */
    fun onGateChanged(gate: ScanGate) {
        if (transient.value.gate == gate) return
        transient.update { it.copy(gate = gate) }
        if (gate != ScanGate.READY) stopScan()
    }

    /**
     * Ouvre trente secondes de recherche (FR-SCALE-011).
     *
     * Chaque appel repart d'une liste vide : c'est ce que veut dire `Scan again`, et cela efface
     * du même coup les appareils qui étaient à portée la fois précédente et ne le sont plus.
     */
    fun onScanRequested() {
        if (transient.value.gate != ScanGate.READY) return
        stopScan()
        discovered.value = emptyMap()
        transient.update { it.copy(scanning = true, started = true, proposalAddress = null) }
        discovery.start()
        scan = viewModelScope.launch {
            discovery.advertisements.collect { advertisement ->
                discovered.update { it + (advertisement.address to advertisement) }
            }
        }
        countdown = viewModelScope.launch {
            delay(SCAN_DURATION_MILLIS)
            // FR-SCALE-011 : le scan s'arrête et propose de recommencer. Il ne repart jamais seul,
            // parce qu'un scan qui se relance indéfiniment vide la batterie sans rien annoncer.
            stopScan()
        }
    }

    /** L'écran disparaît : plus de scan. Le scan ne tourne qu'au premier plan (PRD_SCALE 3.7). */
    fun onScreenHidden() {
        stopScan()
    }

    /**
     * Un appareil reconnu a été choisi (FR-SCALE-012).
     *
     * Deux issues seulement : la question du rattachement, ou l'association immédiate. Un appareil
     * déjà appairé sous cette adresse n'en a aucune — il est affiché pour être reconnu, pas pour
     * être choisi.
     */
    fun onDeviceSelected(device: DiscoveredScale) {
        if (!device.selectable) return
        if (device.reattachTo != null) {
            transient.update { it.copy(proposalAddress = device.address) }
            return
        }
        pair(device)
    }

    /**
     * « Oui, c'est bien elle » (FR-SCALE-001).
     *
     * `markSeen` met à jour l'adresse et le nom annoncé **sans toucher au reste** : l'`id`, le nom
     * donné et, par voie de conséquence, toutes les mesures qui pointent dessus survivent au
     * changement d'adresse. C'est exactement ce que le remplacement des piles doit coûter.
     */
    fun onReattachConfirmed() {
        val proposal = state.value.proposal ?: return
        viewModelScope.launch {
            scales.markSeen(
                id = proposal.candidate.scaleId,
                address = proposal.device.address,
                advertisedName = proposal.device.advertisedName,
                at = clock(),
            )
            finish(proposal.candidate.scaleId)
        }
    }

    /** « Non, c'en est une autre » : le second appareil du foyer, appairé comme tel. */
    fun onReattachDeclined() {
        val device = state.value.proposal?.device ?: return
        pair(device)
    }

    /** La question est refermée sans réponse ; rien n'a changé et le scan continue. */
    fun onProposalDismissed() {
        transient.update { it.copy(proposalAddress = null) }
    }

    /** L'écran a pris acte de l'association et est reparti vers la liste (FR-SCALE-012). */
    fun onPairingHandled() {
        transient.update { it.copy(pairedScaleId = null) }
    }

    override fun onCleared() {
        stopScan()
    }

    private fun pair(device: DiscoveredScale) {
        viewModelScope.launch {
            val id = newId()
            scales.save(
                ScaleDevice(
                    id = id,
                    driverId = device.driverId,
                    address = device.address,
                    advertisedName = device.advertisedName,
                    // FR-SCALE-012 : le nom du modèle, que la fiche permet de remplacer.
                    displayName = device.modelName,
                    /*
                     * `null`, et non l'instant présent : voir une annonce prouve que l'appareil
                     * est allumé, pas que Mue a su lui parler. `Never connected` est la vérité
                     * jusqu'à la première pesée, et FR-SCALE-013 demande la date du dernier
                     * contact **réussi**.
                     */
                    lastSeenAt = null,
                    createdAt = clock(),
                ),
            )
            finish(id)
        }
    }

    /** Association faite : le scan s'arrête et l'écran revient à la liste (FR-SCALE-012). */
    private fun finish(scaleId: String) {
        stopScan()
        transient.update { it.copy(proposalAddress = null, pairedScaleId = scaleId) }
    }

    private fun stopScan() {
        countdown?.cancel()
        countdown = null
        val running = scan ?: return
        scan = null
        running.cancel()
        discovery.stop()
        transient.update { it.copy(scanning = false) }
    }

    /**
     * Les appareils qu'un pilote sait lire, chacun confronté aux balances enregistrées.
     *
     * @param seen Les adresses vues pendant ce scan. Une balance enregistrée dont l'adresse en fait
     *   partie répond : elle n'est candidate à aucun rattachement, quel que soit son nom annoncé.
     */
    private fun Collection<ScaleAdvertisement>.recognise(
        registered: List<ScaleDevice>,
        seen: Set<String>,
    ): List<DiscoveredScale> = mapNotNull { advertisement ->
        val driver = drivers.recognise(advertisement) ?: return@mapNotNull null
        val sameAddress = ScaleMatching.matchByAddress(registered, advertisement)
        DiscoveredScale(
            address = advertisement.address,
            advertisedName = advertisement.name.orEmpty(),
            driverId = driver.id,
            modelName = driver.modelName,
            alreadyPairedAs = sameAddress?.displayName,
            reattachTo = reattachmentFor(registered, advertisement, seen),
        )
        // Ordre stable : une liste qui se réordonne à chaque annonce reçue est une liste dans
        // laquelle on ne peut pas appuyer.
    }.sortedWith(compareBy({ it.advertisedName.lowercase(Locale.ROOT) }, { it.address }))

    /**
     * La balance enregistrée que cette annonce pourrait être (FR-SCALE-001).
     *
     * Le calcul appartient à `ScaleMatching`, écrit pur et testé du côté de la couche de liaison :
     * aucune balance ne répond à cette adresse, un pilote reconnaît l'annonce, et une balance
     * enregistrée partage ce pilote et ce nom annoncé. Deux conditions s'y ajoutent ici, et
     * seulement ici, parce qu'elles n'ont de sens que dans un scan :
     *
     * - **une candidate dont l'adresse répond dans ce même scan n'en est pas une.** Elle est là,
     *   sous les yeux de l'utilisateur, et proposer de la rattacher à un autre appareil serait
     *   proposer de fusionner deux balances qui existent toutes deux ;
     * - **une ambiguïté ne se tranche pas.** `ScaleMatching` rend une *liste* de candidates
     *   précisément parce que deux balances identiques dans un foyer en produisent deux légitimes,
     *   et son contrat est explicite : l'appelant ne doit jamais en choisir une. Sans candidate
     *   unique, rien n'est proposé et l'appareil s'appaire comme une balance de plus — ce qui ne
     *   perd rien, puisqu'une balance enregistrée dont l'adresse a changé n'est jamais supprimée.
     */
    private fun reattachmentFor(
        registered: List<ScaleDevice>,
        advertisement: ScaleAdvertisement,
        seen: Set<String>,
    ): ReattachCandidate? {
        val proposal = ScaleMatching.proposeReattachment(registered, advertisement, drivers)
            ?: return null
        val silent = proposal.candidates.filterNot { candidate ->
            seen.any { it.equals(candidate.address, ignoreCase = true) }
        }
        val only = silent.singleOrNull() ?: return null
        return ReattachCandidate(only.id, only.displayName)
    }

    /** Le reste, nommé. Voir [UnsupportedDevice] pour la raison du filtre sur le nom. */
    private fun Collection<ScaleAdvertisement>.unsupported(): List<UnsupportedDevice> =
        mapNotNull { advertisement ->
            if (drivers.recognise(advertisement) != null) return@mapNotNull null
            val name = advertisement.name?.trim().orEmpty()
            if (name.isEmpty()) null else UnsupportedDevice(advertisement.address, name)
        }.sortedWith(compareBy({ it.name.lowercase(Locale.ROOT) }, { it.address }))

    /** L'état qui ne vient ni du scan ni de la base : la phase, la question, l'acquittement. */
    private data class Transient(
        val gate: ScanGate = ScanGate.READY,
        val scanning: Boolean = false,
        val started: Boolean = false,
        val proposalAddress: String? = null,
        val pairedScaleId: String? = null,
    )

    companion object {
        /** FR-SCALE-011, à la lettre : trente secondes, puis l'offre de recommencer. */
        internal const val SCAN_DURATION_MILLIS: Long = 30_000L

        private const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as MueApplication
                ScaleScanViewModel(
                    scales = app.container.scale.scaleRepository,
                    drivers = app.container.scale.scaleDrivers,
                    // La **même** instance que `ScalesViewModel.Factory` : `Profile > Scales` et ce
                    // flux sont composés ensemble le temps d'une transition, et c'est le comptage
                    // partagé qui empêche l'écran qui s'en va de couper le scan de celui qui arrive.
                    discovery = app.container.scale.scaleDiscovery,
                )
            }
        }
    }
}

@Composable
internal fun scaleScanViewModel(): ScaleScanViewModel = viewModel(factory = ScaleScanViewModel.Factory)
