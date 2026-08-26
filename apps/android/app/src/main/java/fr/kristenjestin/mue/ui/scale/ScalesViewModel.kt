package fr.kristenjestin.mue.ui.scale

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.runtime.Composable
import fr.kristenjestin.mue.MueApplication
import fr.kristenjestin.mue.domain.model.ScaleDevice
import fr.kristenjestin.mue.domain.model.ScaleDriverRegistry
import fr.kristenjestin.mue.domain.repository.ScaleRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * L'état de `Profile > Scales` : la liste, le renommage, l'oubli, et la présence à l'écran ouvert
 * (FR-SCALE-010, 013, 014).
 *
 * Une seule instance sert la liste **et** la fiche d'une balance : les deux écrans lisent le même
 * `Flow` du même repository, et une fiche qui rechargerait la sienne montrerait un nom périmé la
 * seconde qui suit un renommage fait ailleurs. C'est aussi ce qui permet à l'oubli confirmé depuis
 * la fiche de faire disparaître la ligne de la liste sans que rien ne se parle.
 *
 * **Ce ViewModel ne demande jamais de permission** (FR-SCALE-025). Il ne connaît même pas
 * l'existence d'Android : c'est l'écran qui lit `rememberScalePermissions()`, et qui n'appelle
 * [onScreenVisible] que lorsque le scan est effectivement possible. Sans permission, sans radio ou
 * sans localisation système, la liste s'affiche entière et chaque balance se lit simplement
 * `Not in range` — l'état normal d'une balance endormie (PRD_SCALE 18.2), jamais une anomalie.
 */
internal class ScalesViewModel(
    private val scales: ScaleRepository,
    private val drivers: ScaleDriverRegistry,
    private val discovery: ScaleDiscovery,
) : ViewModel() {

    /** Les adresses vues depuis l'ouverture de l'écran. Vidées quand il se ferme. */
    private val seenAddresses = MutableStateFlow<Set<String>>(emptySet())

    private val forgetTargetId = MutableStateFlow<String?>(null)

    /** Le scan de présence en cours, ou `null`. */
    private var presence: Job? = null

    /**
     * Combien d'écrans veulent voir les balances à portée.
     *
     * La liste et la fiche s'appuient sur ce même ViewModel et affichent la même information ;
     * `AnimatedContent` les compose ensemble le temps d'une transition. Sans ce compte, le
     * `onDispose` de la liste — qui survient *après* l'arrivée de la fiche — couperait le scan que
     * la fiche vient de demander, et l'appareil passerait « hors de portée » en changeant d'écran.
     */
    private var presenceRequests = 0

    val state: StateFlow<ScalesUiState> = combine(
        scales.observeAll(),
        seenAddresses,
        forgetTargetId,
    ) { devices, addresses, target ->
        val rows = devices.map { device -> device.toRow(addresses) }
        ScalesUiState(
            loading = false,
            scales = rows,
            forgetTarget = rows.firstOrNull { it.id == target },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
        initialValue = ScalesUiState(),
    )

    /**
     * Commence à repérer les balances à portée, pour la durée d'ouverture de l'écran
     * (FR-SCALE-013).
     *
     * Appelé par l'écran **seulement** si le scan est possible. Aucune date de dernier contact
     * n'est écrite ici : voir une annonce prouve qu'un appareil est allumé, pas que Mue a su lui
     * parler, et `Last seen` répond de la seconde question (FR-SCALE-013).
     */
    fun onScreenVisible() {
        presenceRequests++
        if (presence != null) return
        discovery.start()
        presence = viewModelScope.launch {
            discovery.advertisements.collect { advertisement ->
                seenAddresses.update { it + advertisement.address }
            }
        }
    }

    /** Un écran de moins veut la présence. Le scan ne s'arrête qu'au dernier — voir [presenceRequests]. */
    fun onScreenHidden() {
        presenceRequests = (presenceRequests - 1).coerceAtLeast(0)
        if (presenceRequests > 0) return
        stopPresence()
    }

    private fun stopPresence() {
        val running = presence ?: return
        presence = null
        running.cancel()
        discovery.stop()
        seenAddresses.value = emptySet()
    }

    /**
     * Renomme, et rien d'autre (PRD_SCALE 9.3).
     *
     * Un nom vide ou fait d'espaces est refusé sans message : la balance garde celui qu'elle a,
     * qui est au pire le nom du modèle proposé à l'appairage (FR-SCALE-012). Une ligne sans nom
     * dans une liste d'appareils serait une balance qu'on ne sait plus désigner.
     */
    fun onRenamed(id: String, displayName: String) {
        val trimmed = displayName.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { scales.rename(id, trimmed.take(MAX_NAME_LENGTH)) }
    }

    /** FR-SCALE-014 : l'oubli passe par une question, jamais par un seul geste. */
    fun onForgetRequested(id: String) {
        forgetTargetId.value = id
    }

    fun onForgetCancelled() {
        forgetTargetId.value = null
    }

    /**
     * Oublie la balance, **et rien d'autre** (FR-SCALE-014, BR-SCALE-010).
     *
     * Aucune mesure n'est touchée : le schéma met `measurements.source_scale_id` à `null` par sa
     * contrainte `ON DELETE SET NULL`, et les poids gardent leur provenance. Une mesure appartient
     * à l'utilisateur, pas à l'appareil qui l'a produite — et la confirmation le dit avant de
     * demander.
     */
    fun onForgetConfirmed() {
        val id = forgetTargetId.value ?: return
        forgetTargetId.value = null
        viewModelScope.launch { scales.forget(id) }
    }

    override fun onCleared() {
        presenceRequests = 0
        stopPresence()
    }

    private fun ScaleDevice.toRow(seen: Set<String>): PairedScale = PairedScale(
        id = id,
        displayName = displayName,
        // Un pilote retiré depuis l'appairage se lit, il ne fait pas disparaître la balance :
        // une balance invisible serait une balance qu'on ne peut plus oublier (PRD_SCALE 9.2).
        modelName = drivers.byId(driverId)?.modelName ?: PairedScale.UNKNOWN_MODEL_NAME,
        driverId = driverId,
        address = address,
        advertisedName = advertisedName,
        lastSeenAt = lastSeenAt,
        inRange = address in seen,
    )

    companion object {
        private const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L

        /** La même borne que le nom d'affichage du profil : une ligne de liste, pas un texte. */
        internal const val MAX_NAME_LENGTH: Int = 40

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as MueApplication
                ScalesViewModel(
                    scales = app.container.scale.scaleRepository,
                    drivers = app.container.scale.scaleDrivers,
                    discovery = scaleDiscovery(app),
                )
            }
        }
    }
}

/**
 * Le même ViewModel pour la liste et pour la fiche.
 *
 * Sans clé : la portée est le `ViewModelStoreOwner` de l'activité, donc les deux destinations de
 * `ProfileNavHost` obtiennent la même instance, exactement comme `Log activity` et l'éditeur de
 * séance détaillée partagent un brouillon.
 */
@Composable
internal fun scalesViewModel(): ScalesViewModel = viewModel(factory = ScalesViewModel.Factory)
