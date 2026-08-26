package fr.kristenjestin.mue.data.scale.protocol

import fr.kristenjestin.mue.domain.model.ScaleAdvertisement
import fr.kristenjestin.mue.domain.model.ScaleCapabilities
import fr.kristenjestin.mue.domain.model.ScaleDriver
import fr.kristenjestin.mue.domain.model.ScaleDriverSession
import fr.kristenjestin.mue.domain.model.ScaleFrameEvent
import fr.kristenjestin.mue.domain.model.ScaleGattProfile
import fr.kristenjestin.mue.domain.model.ScaleLinkMode
import fr.kristenjestin.mue.domain.model.ScaleWrite
import java.util.Locale

/** Noms de modèle affichés (PRD_SCALE 9.3). Interface en anglais. */
private const val FAKE_MODEL_NAME = "Mue Fake Scale"
private const val FAKE_LITE_MODEL_NAME = "Mue Fake Scale Lite"

/** Noms annoncés reconnus, comme pour un vrai appareil : par le nom, jamais par une marque. */
private const val FAKE_ADVERTISED_NAME = "MUE FAKE SCALE"
private const val FAKE_LITE_ADVERTISED_NAME = "MUE FAKE SCALE LITE"

/** Profil GATT fictif : un service propriétaire dans la plage réservée aux essais. */
private const val FAKE_SERVICE_SHORT_UUID = 0xFEF0
private const val FAKE_NOTIFY_SHORT_UUID = 0xFEF1
private const val FAKE_WRITE_SHORT_UUID = 0xFEF2

/**
 * Enveloppe du protocole fictif : `[0xFA][type][valeur poids fort][valeur poids faible][contrôle]`.
 *
 * **Volontairement différente de celle de la famille HB.** Un pilote fictif qui réutiliserait le
 * codec de la HB9027 ne prouverait rien : il montrerait qu'on sait ajouter un appareil du même
 * protocole, alors que FR-SCALE-030 demande qu'un protocole *étranger* entre dans l'abstraction
 * sans toucher un seul écran. Cinq octets et un OU exclusif suffisent à faire cette démonstration,
 * y compris pour le chemin de rejet (BR-SCALE-003).
 */
private const val FAKE_HEADER = 0xFA
private const val FAKE_FRAME_SIZE = 5

internal const val FAKE_TYPE_WEIGHT_IN_PROGRESS: Int = 0x01
internal const val FAKE_TYPE_WEIGHT_STABLE: Int = 0x02
internal const val FAKE_TYPE_IMPEDANCE: Int = 0x03

/** Comme sur la HB9027, `0xFFFF` est une absence de mesure et non 65535 ohms (BR-SCALE-005). */
private const val FAKE_IMPEDANCE_NOT_MEASURABLE = 0xFFFF

/**
 * Le pilote fictif (PRD_SCALE 23, critère d'extensibilité).
 *
 * Il existe pour deux raisons, et la seconde est celle qui le rend indispensable au quotidien :
 *
 * 1. **Prouver l'extensibilité.** « Ajouter un pilote fictif au registre le rend découvrable sans
 *    modifier un seul écran » : ce pilote parle un protocole qui n'a rien de commun avec celui de
 *    la HB9027, et pourtant aucun écran, aucun état d'interface et aucune table ne le connaissent
 *    (FR-SCALE-030).
 * 2. **Piloter l'application sur émulateur**, où le BLE réel est tout simplement impossible.
 *
 * **Inertie en production.** Ce pilote n'est enregistré que par
 * [MueScaleDrivers.forBuild] avec `debuggable = true` ; le registre par défaut,
 * celui que l'application utilise telle quelle, ne le contient pas. Il ne peut donc reconnaître
 * aucun appareil dans une version publiée, quelle que soit la façon dont un appareil s'annonce.
 * Voir le KDoc de [MueScaleDrivers] pour la raison pour laquelle le drapeau vient de l'extérieur
 * plutôt que de `BuildConfig.DEBUG`.
 */
internal object FakeScaleDriver : ScaleDriver {

    const val ID: String = "mue-fake"

    override val id: String get() = ID

    override val modelName: String get() = FAKE_MODEL_NAME

    override val linkMode: ScaleLinkMode get() = ScaleLinkMode.GATT

    override val capabilities: ScaleCapabilities =
        ScaleCapabilities(providesWeight = true, providesImpedance = true)

    override val gattProfile: ScaleGattProfile = ScaleGattProfile(
        service = bluetoothUuid16(FAKE_SERVICE_SHORT_UUID),
        notify = bluetoothUuid16(FAKE_NOTIFY_SHORT_UUID),
        write = bluetoothUuid16(FAKE_WRITE_SHORT_UUID),
    )

    override fun recognises(advertisement: ScaleAdvertisement): Boolean =
        advertisement.name?.trim()?.uppercase(Locale.ROOT) == FAKE_ADVERTISED_NAME

    override fun newSession(): ScaleDriverSession = FakeScaleSession(providesImpedance = true)
}

/**
 * Un second pilote fictif qui **ne fournit pas l'impédance** (FR-SCALE-030).
 *
 * « Un pilote qui ne fournit pas l'impédance déclare simplement ne pas en fournir ; les écrans
 * s'adaptent par les règles déjà définies en FR-BODY-001. » Cette phrase du PRD ne vaut que si
 * quelque chose la met à l'épreuve : ce pilote est ce quelque chose. Il n'est enregistré nulle
 * part en dehors des tests — l'enregistrer ailleurs n'apporterait rien, puisque toute sa valeur
 * tient à ce que le registre le traite exactement comme les autres.
 *
 * Il partage l'enveloppe du protocole fictif, mais **ignore** les trames d'impédance : un pilote
 * qui déclare ne pas fournir une grandeur ne doit jamais en produire une, même si des octets
 * plausibles arrivent sur la liaison.
 */
internal object FakeWeightOnlyScaleDriver : ScaleDriver {

    const val ID: String = "mue-fake-lite"

    override val id: String get() = ID

    override val modelName: String get() = FAKE_LITE_MODEL_NAME

    override val linkMode: ScaleLinkMode get() = ScaleLinkMode.GATT

    override val capabilities: ScaleCapabilities =
        ScaleCapabilities(providesWeight = true, providesImpedance = false)

    override val gattProfile: ScaleGattProfile get() = FakeScaleDriver.gattProfile

    override fun recognises(advertisement: ScaleAdvertisement): Boolean =
        advertisement.name?.trim()?.uppercase(Locale.ROOT) == FAKE_LITE_ADVERTISED_NAME

    override fun newSession(): ScaleDriverSession = FakeScaleSession(providesImpedance = false)
}

/**
 * La machine du protocole fictif, pour une liaison.
 *
 * Elle reproduit fidèlement les deux propriétés du vrai protocole qui comptent pour les couches
 * supérieures : une écriture d'amorçage à l'abonnement, et **un seul acquittement par liaison**
 * malgré la répétition des trames stables (PRD_SCALE 14.3 point 6). Sans cette fidélité, le pilote
 * fictif validerait sur émulateur un enchaînement que le matériel réel ne suit pas.
 *
 * @property providesImpedance Reflète `ScaleCapabilities.providesImpedance` du pilote qui l'a
 *   créée. À `false`, une trame d'impédance est [ScaleFrameEvent.Ignored].
 */
internal class FakeScaleSession(private val providesImpedance: Boolean) : ScaleDriverSession {

    var weightAcknowledged: Boolean = false
        private set

    override fun onSubscribed(): List<ScaleWrite> = START_WRITES

    override fun onFrame(frame: ByteArray): ScaleFrameEvent {
        if (frame.size != FAKE_FRAME_SIZE) {
            return ScaleFrameEvent.Rejected(
                "fake frame of ${frame.size} bytes, expected $FAKE_FRAME_SIZE",
            )
        }
        val header = frame[0].toInt() and 0xFF
        if (header != FAKE_HEADER) {
            return ScaleFrameEvent.Rejected(
                "unexpected fake header ${HbFrames.hex(header)}, expected ${HbFrames.hex(FAKE_HEADER)}",
            )
        }
        val expected = HbFrames.checksum(frame, 1, FAKE_FRAME_SIZE - 1)
        val actual = frame[FAKE_FRAME_SIZE - 1].toInt() and 0xFF
        if (expected != actual) {
            return ScaleFrameEvent.Rejected(
                "fake checksum mismatch: computed ${HbFrames.hex(expected)}, " +
                    "received ${HbFrames.hex(actual)}",
            )
        }

        val type = frame[1].toInt() and 0xFF
        val value = ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
        return when (type) {
            FAKE_TYPE_WEIGHT_IN_PROGRESS ->
                ScaleFrameEvent.Weight(hundredthsKg = value, stable = false, replies = emptyList())

            FAKE_TYPE_WEIGHT_STABLE -> {
                val acknowledge = !weightAcknowledged
                if (acknowledge) weightAcknowledged = true
                ScaleFrameEvent.Weight(
                    hundredthsKg = value,
                    stable = true,
                    replies = if (acknowledge) STABLE_ACK_WRITES else emptyList(),
                )
            }

            FAKE_TYPE_IMPEDANCE ->
                if (!providesImpedance) {
                    ScaleFrameEvent.Ignored
                } else {
                    ScaleFrameEvent.Impedance(
                        ohm = if (value == FAKE_IMPEDANCE_NOT_MEASURABLE) null else value,
                        replies = emptyList(),
                    )
                }

            else -> ScaleFrameEvent.Ignored
        }
    }

    private companion object {
        val START_WRITES: List<ScaleWrite> = listOf(ScaleWrite(FakeScaleFrames.START_COMMAND))
        val STABLE_ACK_WRITES: List<ScaleWrite> = listOf(ScaleWrite(FakeScaleFrames.STABLE_ACK))
    }
}

/**
 * Les trames du protocole fictif, pour qui doit en produire — couche de liaison simulée sur
 * émulateur, tests d'écran, démonstration.
 *
 * Les fabriques sont ici et non dans la session parce qu'une session *décode* : elle n'a aucune
 * raison de savoir construire ce qu'elle reçoit. C'est aussi ce qui permet à un scénario de démo
 * de vivre hors du pilote.
 */
internal object FakeScaleFrames {

    /** Écriture d'amorçage, émise dès l'abonnement, à l'image des commandes d'initialisation HB. */
    val START_COMMAND: ByteArray = byteArrayOf(FAKE_HEADER.toByte(), 0x10, 0x00, 0x00, 0x10)

    /** Acquittement d'un poids stable, émis **une seule fois** par liaison. */
    val STABLE_ACK: ByteArray = byteArrayOf(FAKE_HEADER.toByte(), 0x11, 0x00, 0x00, 0x11)

    /** Une trame de poids, en centièmes de kilogramme. */
    fun weight(hundredthsKg: Int, stable: Boolean): ByteArray = frame(
        type = if (stable) FAKE_TYPE_WEIGHT_STABLE else FAKE_TYPE_WEIGHT_IN_PROGRESS,
        value = hundredthsKg,
    )

    /** Une trame d'impédance ; `null` produit le marqueur de mesure impossible (BR-SCALE-005). */
    fun impedance(ohm: Int?): ByteArray =
        frame(FAKE_TYPE_IMPEDANCE, ohm ?: FAKE_IMPEDANCE_NOT_MEASURABLE)

    /**
     * Un scénario de pesée complet et plausible, dans l'ordre d'émission : la montée sur le
     * plateau, la stabilisation à 85,75 kg — le poids relevé sur matériel en PRD_SCALE 14.4 —, la
     * répétition de la trame stable qui met l'unicité de l'acquittement à l'épreuve, puis
     * l'impédance de 545 Ω de PRD_SCALE 14.5.
     */
    val DEMO_SCRIPT: List<ByteArray> = listOf(
        weight(hundredthsKg = 3_240, stable = false),
        weight(hundredthsKg = 7_910, stable = false),
        weight(hundredthsKg = 8_575, stable = false),
        weight(hundredthsKg = 8_575, stable = true),
        weight(hundredthsKg = 8_575, stable = true),
        impedance(ohm = 545),
    )

    private fun frame(type: Int, value: Int): ByteArray {
        val bytes = ByteArray(FAKE_FRAME_SIZE)
        bytes[0] = FAKE_HEADER.toByte()
        bytes[1] = type.toByte()
        bytes[2] = ((value ushr 8) and 0xFF).toByte()
        bytes[3] = (value and 0xFF).toByte()
        bytes[4] = HbFrames.checksum(bytes, 1, FAKE_FRAME_SIZE - 1).toByte()
        return bytes
    }
}
