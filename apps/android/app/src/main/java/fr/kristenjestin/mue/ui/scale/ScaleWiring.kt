package fr.kristenjestin.mue.ui.scale

import fr.kristenjestin.mue.data.scale.ble.ScaleTransport
import fr.kristenjestin.mue.data.scale.ble.ScaleTransportException
import fr.kristenjestin.mue.domain.model.ScaleAdvertisement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * d'ouvertures, et une seule collecte tant qu'il n'est pas retombé à zéro. Il est écrit ici, à côté
 * du port qu'il réalise — le mettre dans `data/scale/ble` ferait importer la couche interface par
 * la couche data, ce qui n'arrive nulle part ailleurs dans ce dépôt. Il n'est **instancié** qu'à un
 * seul endroit, `ScaleContainer.scaleDiscovery`, dont le KDoc porte la justification complète : une
 * seconde instance ouvrirait un second scan BLE et rendrait le comptage inopérant.
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

