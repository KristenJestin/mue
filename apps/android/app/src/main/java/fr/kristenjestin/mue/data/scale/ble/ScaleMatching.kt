package fr.kristenjestin.mue.data.scale.ble

import fr.kristenjestin.mue.domain.model.ScaleAdvertisement
import fr.kristenjestin.mue.domain.model.ScaleDevice
import fr.kristenjestin.mue.domain.model.ScaleDriver
import fr.kristenjestin.mue.domain.model.ScaleDriverRegistry
import java.util.Locale

/**
 * L'identité composite de FR-SCALE-001, en fonctions **pures**.
 *
 * Deux questions distinctes vivent ici, et les confondre serait exactement la panne que
 * FR-SCALE-001 cherche à éviter :
 *
 * 1. **« Cette annonce est-elle une balance que je connais ? »** — [matchByAddress]. C'est la seule
 *    question que la session de mesure pose. Elle répond par l'adresse, et par elle seule.
 * 2. **« Cette annonce est-elle *peut-être* une balance que je connais, dont l'adresse a
 *    changé ? »** — [proposeReattachment]. Cette question-là n'a **jamais** de réponse
 *    automatique : « le rattachement est proposé, jamais silencieux : deux balances identiques
 *    dans un même foyer ne doivent pas fusionner à l'insu de l'utilisateur ».
 *
 * C'est pour cette raison que la seconde n'est pas appelée par la machine à états : une session de
 * pesée n'a aucun moyen de demander quoi que ce soit à l'utilisateur, et se lier à une balance
 * « probablement la même » y serait un rattachement silencieux déguisé. Elle est ici, pure et
 * réutilisable, pour le flux d'appairage de l'écran `Scales`, qui lui peut poser la question.
 *
 * Aucune de ces fonctions ne touche à Android : elles se testent en JVM pure (PRD_SCALE 21.3).
 */
internal object ScaleMatching {

    /**
     * La balance enregistrée que cette annonce désigne, par l'adresse (FR-SCALE-001).
     *
     * « Une balance enregistrée est reconnue par son adresse **lorsque celle-ci répond**. » La
     * comparaison ignore la casse : Android restitue les adresses en majuscules, mais rien ne
     * garantit la casse d'une adresse relue d'une base écrite par une version antérieure.
     */
    fun matchByAddress(
        devices: List<ScaleDevice>,
        advertisement: ScaleAdvertisement,
    ): ScaleDevice? = devices.firstOrNull { it.address.equals(advertisement.address, ignoreCase = true) }

    /**
     * Le rattachement d'adresse **à proposer**, ou `null` quand il n'y a rien à proposer
     * (FR-SCALE-001).
     *
     * Le cas qu'elle couvre est celui de PRD_SCALE 10.1 : l'adresse de la balance de référence est
     * une adresse statique aléatoire, qu'un changement de piles peut régénérer. Ce jour-là la
     * balance enregistrée devient invisible, et l'application n'a aucun moyen d'expliquer pourquoi
     * — de son point de vue c'est un appareil jamais vu. Trois conditions, toutes nécessaires :
     *
     * - aucune balance enregistrée ne répond à cette adresse (sinon il n'y a pas de problème) ;
     * - un pilote reconnaît l'annonce (sinon ce n'est pas une balance que Mue sait piloter) ;
     * - au moins une balance enregistrée partage **le même pilote et le même nom annoncé**.
     *
     * [ScaleReattachment.candidates] est une liste et non un unique appareil, parce que deux
     * balances identiques dans un foyer produisent deux candidates légitimes. L'appelant doit alors
     * demander laquelle, ou renoncer ; il ne doit jamais en choisir une.
     */
    fun proposeReattachment(
        devices: List<ScaleDevice>,
        advertisement: ScaleAdvertisement,
        drivers: ScaleDriverRegistry,
    ): ScaleReattachment? {
        if (matchByAddress(devices, advertisement) != null) return null
        val name = advertisement.name?.normalisedName() ?: return null
        val driver = drivers.recognise(advertisement) ?: return null
        val candidates = devices.filter {
            it.driverId == driver.id && it.advertisedName.normalisedName() == name
        }
        if (candidates.isEmpty()) return null
        return ScaleReattachment(advertisement, driver, candidates)
    }

    /**
     * Comparaison des noms annoncés, bord à bord et sans casse.
     *
     * La pile BLE d'Android restitue tantôt le nom complet de l'annonce, tantôt le nom court, avec
     * une casse qui n'est garantie par rien — c'est déjà la raison pour laquelle `Hb9027Driver`
     * normalise avant de comparer.
     */
    private fun String.normalisedName(): String = trim().uppercase(Locale.ROOT)
}

/**
 * Une proposition de rattachement d'adresse, prête à être présentée (FR-SCALE-001).
 *
 * Accepter la proposition consiste à écrire l'adresse et le nom annoncé de [advertisement] sur la
 * balance choisie — ce que fait déjà `ScaleRepository.markSeen`, qui conserve l'`id`, le nom donné
 * par l'utilisateur et tout l'historique de mesures. Refuser ne coûte rien : « une balance
 * enregistrée dont l'adresse a changé n'est jamais supprimée automatiquement ».
 *
 * @property advertisement L'annonce dont l'adresse est inconnue.
 * @property driver Le pilote qui la reconnaît ; il est nécessairement celui des [candidates].
 * @property candidates Les balances enregistrées qui partagent ce pilote et ce nom annoncé.
 *   Plusieurs valent ambiguïté, jamais choix automatique.
 */
internal data class ScaleReattachment(
    val advertisement: ScaleAdvertisement,
    val driver: ScaleDriver,
    val candidates: List<ScaleDevice>,
)
