package fr.kristenjestin.mue.ui.scale

import fr.kristenjestin.mue.MueApplication
import fr.kristenjestin.mue.data.scale.ble.ScaleTransport
import fr.kristenjestin.mue.data.scale.ble.ScaleTransportException
import fr.kristenjestin.mue.domain.model.ScaleAdvertisement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Le scan réel, rendu partageable (FR-SCALE-011, 013).
 *
 * `ScaleTransport.scan()` est un flux **froid** : le scan démarre à la collecte et s'arrête à son
 * annulation, ce qui est exactement ce que veut la machine de pesée — collecter jusqu'à la première
 * candidate, puis éteindre la radio. Les écrans d'appairage, eux, veulent l'inverse : un scan qui
 * survit à une transition entre `Profile > Scales` et le flux d'appairage, alors même que
 * `AnimatedContent` compose les deux en même temps et que l'écran qui s'en va se ferme *après* que
 * l'écran qui arrive s'est ouvert. Un flux froid par écran couperait la radio au mauvais moment, et
 * le symptôme serait une liste vide pendant trente secondes sans que rien ne l'explique.
 *
 * Cet adaptateur est donc la seule chose que [ScaleDiscovery] ajoute au transport : un compte
 * d'ouvertures, et une seule collecte tant qu'il n'est pas retombé à zéro. `ScaleContainer` désigne
 * nommément `ui/scale` comme l'endroit où cette adaptation doit vivre — une dépendance de la couche
 * data vers la couche interface serait à l'envers.
 *
 * **Une panne de scan ne s'affiche jamais** (PRD_SCALE 18.5). Un
 * [ScaleTransportException] ferme la collecte en silence : l'écran voit une absence d'annonces,
 * c'est-à-dire ce qu'il verrait d'une balance endormie, et propose de recommencer au bout de trente
 * secondes. Les quatre causes sur lesquelles l'utilisateur peut agir — permission, radio,
 * localisation système, refus définitif — sont lues séparément par `rememberScalePermissions()` et
 * barrent le scan avant même qu'il démarre.
 */
internal class TransportScaleDiscovery(
    private val transport: ScaleTransport,
    private val scope: CoroutineScope,
) : ScaleDiscovery {

    private val received = MutableSharedFlow<ScaleAdvertisement>(extraBufferCapacity = BUFFER)

    override val advertisements: Flow<ScaleAdvertisement> = received.asSharedFlow()

    /**
     * Combien d'appelants ont ouvert le scan sans l'avoir refermé.
     *
     * Confiné au fil principal, d'où les écrans appellent [start] et [stop] : ce compte n'a aucune
     * synchronisation et n'en a pas besoin.
     */
    private var openCount = 0

    private var collection: Job? = null

    override fun start() {
        openCount++
        if (collection != null) return
        collection = scope.launch {
            try {
                transport.scan().collect(received::emit)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: ScaleTransportException) {
                // Silence délibéré : voir le KDoc de la classe.
            }
        }
    }

    override fun stop() {
        openCount = (openCount - 1).coerceAtLeast(0)
        if (openCount > 0) return
        collection?.cancel()
        collection = null
    }

    private companion object {
        /**
         * Une balance annonce plusieurs fois par seconde et un salon en contient des dizaines. La
         * marge évite qu'un abonné momentanément en retard fasse manquer une annonce à l'autre.
         */
        const val BUFFER = 64
    }
}

/**
 * L'unique scan partagé du processus.
 *
 * **Point de raccordement, pas une décision d'architecture.** Sa place est une propriété `by lazy`
 * de `ScaleContainer`, aux côtés de `scaleTransport` et de `scaleSessionSource` ; elle n'y est pas
 * parce que ce fichier n'a pas le droit de modifier le conteneur. Les deux fabriques de ce paquet
 * doivent pourtant partager la **même** instance : deux adaptateurs compteraient chacun leurs
 * ouvertures et ouvriraient deux scans BLE simultanés, ce qui reviendrait à écrire le comptage pour
 * ne pas s'en servir.
 *
 * Construit sur le fil principal, à la première fabrique de ViewModel qui en a besoin — c'est-à-dire
 * à l'ouverture de `Profile > Scales`, jamais au démarrage de l'application.
 */
private var shared: ScaleDiscovery? = null

internal fun scaleDiscovery(application: MueApplication): ScaleDiscovery =
    shared ?: TransportScaleDiscovery(
        transport = application.container.scale.scaleTransport,
        /*
         * `Main.immediate`, comme la source de session : [TransportScaleDiscovery.openCount] est
         * confiné au fil dont les écrans l'appellent, et `immediate` évite en prime un saut de
         * dispatch entre le `start()` d'un écran et l'ouverture du scan.
         */
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    ).also { shared = it }
