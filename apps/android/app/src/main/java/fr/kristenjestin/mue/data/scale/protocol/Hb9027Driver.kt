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
import java.util.UUID

/** Nom de modèle affiché par défaut à l'appairage (PRD_SCALE 9.3). Interface en anglais. */
private const val MODEL_NAME = "Homebuds HB9027"

/**
 * Nom **annoncé en BLE** par l'appareil (PRD_SCALE 14.1).
 *
 * Ce n'est pas une marque : la balance ne diffuse ni « Homebuds » ni le nom de son application
 * constructeur. Un pilote qui chercherait une marque commerciale ne trouverait jamais cet
 * appareil — c'est le piège que PRD_SCALE 14.1 consigne explicitement.
 */
private const val ADVERTISED_NAME = "HB BODY FAT"

/** Service propriétaire, notification et écriture (PRD_SCALE 14.1). */
private const val SERVICE_SHORT_UUID = 0xFFF0
private const val NOTIFY_SHORT_UUID = 0xFFF1
private const val WRITE_SHORT_UUID = 0xFFF2

/** Types de trames de la famille HB (PRD_SCALE 14.4, 14.5). */
internal const val HB_TYPE_WEIGHT: Int = 0x10
internal const val HB_TYPE_IMPEDANCE: Int = 0x11

/** Type de la réponse d'initialisation `5A 04 00 17 13 AA` (PRD_SCALE 14.3). */
internal const val HB_TYPE_INIT_REPLY: Int = 0x17

/** Position 4 d'une trame de poids : `0x01` en cours, `0x02` stable, `0x03` après acquittement. */
internal const val HB_STABILITY_IN_PROGRESS: Int = 0x01
internal const val HB_STABILITY_STABLE: Int = 0x02
internal const val HB_STABILITY_ACKNOWLEDGED: Int = 0x03

/** Positions absolues des grandeurs, telles que relevées sur matériel (PRD_SCALE 14.4, 14.5). */
private const val WEIGHT_STABILITY_INDEX = 4
private const val WEIGHT_VALUE_INDEX = 8
private const val IMPEDANCE_VALUE_INDEX = 9

/**
 * Marqueur de mesure impossible sur une trame d'impédance (PRD_SCALE 14.5, BR-SCALE-005).
 *
 * `0xFFFF` est une **absence**, jamais 65535 ohms. Il est fréquent : il apparaît systématiquement
 * quand le contact avec les deux pieds n'est pas établi, chaussures ou chaussettes par exemple.
 */
private const val IMPEDANCE_NOT_MEASURABLE = 0xFFFF

/**
 * Le pilote de la balance Homebuds HB9027, premier pilote de Mue (PRD_SCALE 14).
 *
 * Tout ce qui suit a été relevé sur l'appareil réel le 26 août 2026 ; cette classe est la mémoire
 * de cette session de validation, pour que ce qui a été observé une fois n'ait jamais besoin
 * d'être réobservé (PRD_SCALE 15).
 *
 * **Le service standard n'est pas utilisable (PRD_SCALE 14.6).** L'appareil déclare bien le
 * service Bluetooth SIG `0x181B` *Body Composition*, dont la caractéristique `0x2A9C` annonce des
 * propriétés d'indication : l'abonnement **réussit** et n'émet **jamais** la moindre notification.
 * Le service `0x181D` *Weight Scale* est, lui, purement absent. Sur les 201 notifications reçues
 * pendant la session de validation, la totalité provenait de `0xFFF1`. La présence d'un service
 * normalisé dans la table des attributs ne dit donc rien de son alimentation : un futur pilote
 * peut tenter la voie standard, aucun ne doit s'y fier sans l'avoir vérifiée sur matériel.
 *
 * **Résolution.** Toutes les valeurs observées, sur plusieurs dizaines de trames, sont des
 * multiples de cinq centièmes de kilogramme : la résolution native de l'appareil coïncide
 * exactement avec le pas de `0.05 kg` de PRD BR-003, et la valeur brute entre dans le domaine sans
 * conversion. C'est une propriété de cet appareil, pas une garantie de la famille — l'arrondi au
 * pas reste appliqué à la frontière du domaine, par la couche supérieure.
 *
 * Un `object` et non une classe : un pilote est sans état, tout l'état d'une pesée vit dans la
 * [Hb9027Session] que [newSession] fabrique.
 */
internal object Hb9027Driver : ScaleDriver {

    /** Identifiant stable stocké dans `ScaleDevice.driverId`. Le renommer orpheline les balances. */
    const val ID: String = "hb9027"

    override val id: String get() = ID

    override val modelName: String get() = MODEL_NAME

    override val linkMode: ScaleLinkMode get() = ScaleLinkMode.GATT

    override val capabilities: ScaleCapabilities =
        ScaleCapabilities(providesWeight = true, providesImpedance = true)

    override val gattProfile: ScaleGattProfile = ScaleGattProfile(
        service = bluetoothUuid16(SERVICE_SHORT_UUID),
        notify = bluetoothUuid16(NOTIFY_SHORT_UUID),
        write = bluetoothUuid16(WRITE_SHORT_UUID),
    )

    /**
     * Reconnaissance par le **nom annoncé**, et par lui seul (PRD_SCALE 14.1).
     *
     * Ni marque commerciale, ni préfixe d'adresse : l'adresse observée est statique aléatoire, donc
     * sans valeur d'identification (PRD_SCALE 10.1). Le nom est comparé en majuscules et sans
     * espaces de bord, parce que la pile BLE d'Android restitue parfois le nom complet et parfois
     * le nom court de l'annonce, avec une casse qui n'est garantie par rien.
     *
     * Les UUID de service ne servent pas de critère : `0xFFF0` est un service propriétaire
     * générique, présent sur des milliers d'appareils sans rapport, et l'annonce de la HB9027 ne
     * le publie pas systématiquement.
     */
    override fun recognises(advertisement: ScaleAdvertisement): Boolean =
        advertisement.name?.trim()?.uppercase(Locale.ROOT) == ADVERTISED_NAME

    override fun newSession(): ScaleDriverSession = Hb9027Session()
}

/**
 * La machine du protocole d'**une** liaison avec une HB9027 (PRD_SCALE 14.3).
 *
 * Séquence exacte, telle que validée sur matériel :
 *
 * 1. s'abonner aux notifications de `0xFFF1` ;
 * 2. émettre les trois écritures d'initialisation, **une à la fois**, chacune après
 *    l'acquittement de la précédente ; l'appareil répond `5A 04 00 17 13 AA` ;
 * 3. la balance émet un flux de trames de poids ;
 * 4. sur le **premier** poids stable, écrire `A5 05 26 10 02 31 AA` — **sans cet acquittement, la
 *    mesure d'impédance n'est jamais lancée** ;
 * 5. la trame d'impédance arrive dans les dix secondes ; l'acquitter par `A5 05 26 11 00 32 AA`.
 *
 * **Un seul acquittement par liaison, pour chacun des deux (PRD_SCALE 14.3 point 6).** La balance
 * répète sa trame stable ; acquitter chaque répétition empilerait des écritures dans une fenêtre
 * déjà courte, et la pile BLE d'Android n'accepte qu'une opération GATT en vol. C'est exactement
 * pourquoi cet état vit dans la session et non dans le pilote : deux pesées successives ne
 * doivent jamais partager leur compteur d'acquittements, alors qu'elles partagent leur pilote.
 *
 * Enchaîner les écritures sans attendre les acquittements est le mode d'échec le plus probable de
 * ce protocole, et il est **silencieux** : c'est ce que `ScaleWrite.awaitAck` exprime.
 *
 * Aucune dépendance Android, aucun Bluetooth : la classe s'instancie et se pilote entièrement
 * dans un test JVM (PRD_SCALE 21.3, FR-SCALE-031).
 */
internal class Hb9027Session : ScaleDriverSession {

    /** Vrai dès que `5A 04 00 17 13 AA` a été reçue : la séquence d'initialisation a porté. */
    var initialisationConfirmed: Boolean = false
        private set

    /** Vrai dès que l'acquittement de poids stable a été **demandé** une fois (14.3 point 6). */
    var weightAcknowledged: Boolean = false
        private set

    /** Idem pour l'acquittement d'impédance. */
    var impedanceAcknowledged: Boolean = false
        private set

    /**
     * Les trois écritures d'initialisation de PRD_SCALE 14.3, dans l'ordre, chacune en attente de
     * l'acquittement de la précédente.
     *
     * Appelée une fois par liaison, une fois l'abonnement **effectif** : une balance qui reçoit sa
     * commande de démarrage avant que le descripteur `0x2902` soit écrit répond dans le vide, et la
     * pesée reste bloquée sans la moindre erreur.
     */
    override fun onSubscribed(): List<ScaleWrite> = INIT_WRITES

    /**
     * Décodage **pur** d'une notification (FR-SCALE-031).
     *
     * Ne lève jamais : une trame trop courte, tronquée, de type inconnu ou au contrôle faux produit
     * [ScaleFrameEvent.Rejected] ou [ScaleFrameEvent.Ignored] (BR-SCALE-003). Aucune borne métier
     * n'est appliquée ici — un poids de 4 kg est rendu tel quel, et c'est la couche supérieure qui
     * lui oppose BR-SCALE-002, afin qu'une valeur hors domaine reste distinguable d'une trame
     * illisible.
     *
     * La méthode est pure au sens du décodage — même trame, même lecture — mais la session
     * mémorise les acquittements déjà demandés : c'est la seule mutation, et elle est le sujet
     * même de PRD_SCALE 14.3 point 6.
     */
    override fun onFrame(frame: ByteArray): ScaleFrameEvent =
        when (val decoded = HbFrames.decode(frame)) {
            is HbFrame.Malformed -> ScaleFrameEvent.Rejected(decoded.reason)
            is HbFrame.Valid -> when (decoded.type) {
                HB_TYPE_WEIGHT -> onWeightFrame(decoded)
                HB_TYPE_IMPEDANCE -> onImpedanceFrame(decoded)
                HB_TYPE_INIT_REPLY -> {
                    initialisationConfirmed = true
                    ScaleFrameEvent.Ignored
                }
                // Trame bien formée d'un type que ce pilote ne connaît pas : rien d'anormal n'est
                // arrivé, l'appareil bavarde. Rejeter ferait remonter du bruit dans le journal.
                else -> ScaleFrameEvent.Ignored
            }
        }

    /**
     * Trame de poids, type `0x10` (PRD_SCALE 14.4) :
     * position 4 la stabilité, positions 8–9 le poids en centièmes de kilogramme, gros-boutiste.
     *
     * `0x03` — l'état que la balance adopte *après* notre acquittement — est un poids stable, mais
     * il n'en déclenche aucun : c'est la répétition dont parle 14.3 point 6.
     */
    private fun onWeightFrame(decoded: HbFrame.Valid): ScaleFrameEvent {
        val stability = decoded.byteAt(WEIGHT_STABILITY_INDEX)
            ?: return ScaleFrameEvent.Rejected(tooShort("weight", decoded, WEIGHT_STABILITY_INDEX))
        val hundredths = decoded.uint16At(WEIGHT_VALUE_INDEX)
            ?: return ScaleFrameEvent.Rejected(tooShort("weight", decoded, WEIGHT_VALUE_INDEX))

        val stable = stability == HB_STABILITY_STABLE || stability == HB_STABILITY_ACKNOWLEDGED
        val acknowledge = stability == HB_STABILITY_STABLE && !weightAcknowledged
        if (acknowledge) weightAcknowledged = true

        return ScaleFrameEvent.Weight(
            hundredthsKg = hundredths,
            stable = stable,
            replies = if (acknowledge) WEIGHT_ACK_WRITES else emptyList(),
        )
    }

    /**
     * Trame d'impédance, type `0x11` (PRD_SCALE 14.5) : positions 9–10, gros-boutiste, en ohms.
     *
     * `0xFFFF` est rendu comme une **absence** et non comme 65535 ohms (BR-SCALE-005). L'absence
     * est acquittée comme une valeur : c'est bien une trame d'impédance, la balance en attend la
     * réponse, et le poids reste parfaitement valide (FR-BODY-002).
     */
    private fun onImpedanceFrame(decoded: HbFrame.Valid): ScaleFrameEvent {
        val raw = decoded.uint16At(IMPEDANCE_VALUE_INDEX)
            ?: return ScaleFrameEvent.Rejected(tooShort("impedance", decoded, IMPEDANCE_VALUE_INDEX))

        val acknowledge = !impedanceAcknowledged
        if (acknowledge) impedanceAcknowledged = true

        return ScaleFrameEvent.Impedance(
            ohm = if (raw == IMPEDANCE_NOT_MEASURABLE) null else raw,
            replies = if (acknowledge) IMPEDANCE_ACK_WRITES else emptyList(),
        )
    }

    private fun tooShort(kind: String, decoded: HbFrame.Valid, index: Int): String =
        "$kind frame of ${decoded.bytes.size} bytes carries no data at position $index"

    private companion object {

        /**
         * `A5 05 26 33 00 10 AA`, `A5 04 26 44 66 AA`, `A5 05 26 17 01 35 AA` (PRD_SCALE 14.3).
         *
         * La signification de `0x33`, `0x44` et `0x17` n'est pas connue : elle a été relevée sur
         * la liaison de l'application constructeur. Elles sont reproduites telles quelles, ce qui
         * est la seule chose honnête à faire d'une séquence dont on n'a que le comportement.
         */
        val INIT_WRITES: List<ScaleWrite> = listOf(
            ScaleWrite(HbFrames.command(0x33, byteArrayOf(0x00))),
            ScaleWrite(HbFrames.command(0x44)),
            ScaleWrite(HbFrames.command(0x17, byteArrayOf(0x01))),
        )

        /** `A5 05 26 10 02 31 AA` — sans lui, aucune impédance ne sera jamais mesurée. */
        val WEIGHT_ACK_WRITES: List<ScaleWrite> = listOf(
            ScaleWrite(HbFrames.command(HB_TYPE_WEIGHT, byteArrayOf(HB_STABILITY_STABLE.toByte()))),
        )

        /** `A5 05 26 11 00 32 AA`. */
        val IMPEDANCE_ACK_WRITES: List<ScaleWrite> = listOf(
            ScaleWrite(HbFrames.command(HB_TYPE_IMPEDANCE, byteArrayOf(0x00))),
        )
    }
}

/**
 * L'UUID 128 bits canonique d'un UUID Bluetooth court de 16 bits.
 *
 * Le SIG définit `0000xxxx-0000-1000-8000-00805f9b34fb` ; Android n'accepte que la forme longue
 * dans ses API GATT. La chaîne est assemblée à la main plutôt que par `String.format`, dont le
 * résultat dépend de la locale courante.
 */
internal fun bluetoothUuid16(shortUuid: Int): UUID {
    val digits = "0123456789abcdef"
    val value = shortUuid and 0xFFFF
    val hex = buildString {
        for (shift in intArrayOf(12, 8, 4, 0)) append(digits[(value ushr shift) and 0x0F])
    }
    return UUID.fromString("0000$hex-0000-1000-8000-00805f9b34fb")
}
