package fr.kristenjestin.mue.data.scale.protocol

/**
 * Le codec d'enveloppe de la famille de protocoles « HB » (PRD_SCALE 14.2).
 *
 * Les deux sens de la liaison partagent la même enveloppe :
 *
 * ```text
 * [en-tête][longueur][produit][type][données…][contrôle][fin]
 * ```
 *
 * | Champ | Valeur |
 * |---|---|
 * | En-tête | [HEADER_FROM_SCALE] balance → téléphone, [HEADER_FROM_PHONE] téléphone → balance |
 * | Longueur | Nombre d'octets **suivant le champ de longueur lui-même**, donc `taille - 2` |
 * | Produit | Identifiant de modèle, **jamais validé** (BR-SCALE-004) |
 * | Contrôle | OU exclusif de la longueur jusqu'au dernier octet de données |
 * | Fin | [TRAILER] |
 *
 * **Pourquoi un objet sans état, séparé des pilotes.** L'enveloppe appartient à la famille, pas à
 * un modèle : la HB9027 et ses appareils apparentés la partagent, et un pilote fictif peut s'y
 * greffer. La séparer du pilote permet de la couvrir intégralement par des tests de trames réelles
 * (PRD_SCALE 15, FR-SCALE-031) sans jamais instancier de machine à états. Aucune fonction ici ne
 * lève : elles rendent toutes une valeur, y compris devant des octets absurdes.
 *
 * **L'octet de produit ne participe jamais à la validation (BR-SCALE-004).** Il vaut `0x00` sur la
 * HB9027 réelle et `0x26` sur un appareil apparenté (PRD_SCALE 14.2) ; le code du spike de
 * validation matériel exigeait `0x26` et rejetait donc toutes les trames de l'appareil réel. Le
 * contrôle par OU exclusif, lui, est vérifié et fait autorité (BR-SCALE-003).
 *
 * **Rien d'Android ici**, et rien de métier non plus : les bornes de poids de BR-SCALE-002 sont
 * appliquées par la couche supérieure. Ce codec rend ce que la balance a dit.
 */
internal object HbFrames {

    /** En-tête d'une trame émise par la balance. */
    const val HEADER_FROM_SCALE: Int = 0x5A

    /** En-tête d'une trame émise par le téléphone. */
    const val HEADER_FROM_PHONE: Int = 0xA5

    /** Dernier octet de toute trame, dans les deux sens. */
    const val TRAILER: Int = 0xAA

    /**
     * Octet de produit que l'application constructeur place dans **ses commandes**, et que la
     * HB9027 accepte telle quelle (PRD_SCALE 14.3 : `A5 05 26 33 00 10 AA`).
     *
     * Il diffère volontairement de celui que l'appareil émet dans ses propres trames (`0x00`) :
     * puisque l'octet n'est jamais validé dans un sens comme dans l'autre, la valeur retenue est
     * celle qui a été observée fonctionnelle sur matériel le 26/08/2026, et rien d'autre.
     */
    const val PRODUCT_IN_COMMANDS: Int = 0x26

    /**
     * Taille minimale d'une trame : en-tête, longueur, produit, type, contrôle, fin.
     * Une trame de cette taille exacte ne porte aucune donnée — c'est le cas de la réponse
     * d'initialisation `5A 04 00 17 13 AA` (PRD_SCALE 14.3).
     */
    const val MIN_FRAME_SIZE: Int = 6

    /** Index du premier octet de données. Les positions de PRD_SCALE 14.4/14.5 sont absolues. */
    const val FIRST_DATA_INDEX: Int = 4

    /**
     * Encode une commande téléphone → balance.
     *
     * @param type Octet de type, en position 3.
     * @param data Charge utile, éventuellement vide.
     * @param product Octet de produit ; [PRODUCT_IN_COMMANDS] par défaut, ce qui reproduit
     *   octet pour octet les commandes de PRD_SCALE 14.3.
     */
    fun command(
        type: Int,
        data: ByteArray = EMPTY_DATA,
        product: Int = PRODUCT_IN_COMMANDS,
    ): ByteArray = frame(HEADER_FROM_PHONE, product, type, data)

    /**
     * Encode une trame quelconque, en-tête compris.
     *
     * Exposé au-delà de [command] parce que les tests doivent pouvoir fabriquer des trames
     * *entrantes* — dont la variante `0x26` d'une trame réelle, qui prouve que l'octet de produit
     * ne change rien au décodage (BR-SCALE-004). Le contrôle est toujours recalculé : il est
     * impossible de construire ici une trame au contrôle faux par distraction.
     */
    fun frame(header: Int, product: Int, type: Int, data: ByteArray): ByteArray {
        val bytes = ByteArray(data.size + MIN_FRAME_SIZE)
        bytes[0] = header.toByte()
        // La longueur compte tout ce qui suit le champ de longueur : produit, type, données,
        // contrôle et fin, soit `taille - 2`.
        bytes[1] = (bytes.size - 2).toByte()
        bytes[2] = product.toByte()
        bytes[3] = type.toByte()
        data.copyInto(bytes, FIRST_DATA_INDEX)
        bytes[bytes.size - 2] = checksum(bytes, 1, bytes.size - 2).toByte()
        bytes[bytes.size - 1] = TRAILER.toByte()
        return bytes
    }

    /** OU exclusif de `bytes[fromIndex until toIndexExclusive]`, ramené à un octet. */
    fun checksum(bytes: ByteArray, fromIndex: Int, toIndexExclusive: Int): Int {
        var accumulator = 0
        for (index in fromIndex until toIndexExclusive) {
            accumulator = accumulator xor (bytes[index].toInt() and 0xFF)
        }
        return accumulator and 0xFF
    }

    /**
     * Décode et **valide** une trame reçue. Fonction pure, qui ne lève jamais (FR-SCALE-031).
     *
     * L'ordre des contrôles va du plus structurel au plus fin — taille, en-tête, fin, longueur,
     * contrôle — pour que le motif de rejet désigne la première anomalie et non une conséquence :
     * une trame tronquée a presque toujours un contrôle faux *aussi*, mais dire « tronquée » est
     * ce qui aide à la lecture du journal.
     *
     * @param expectedHeader En-tête attendu. [HEADER_FROM_SCALE] par défaut, puisqu'on décode des
     *   notifications ; les tests s'en servent pour relire une commande qu'ils viennent d'encoder.
     * @return [HbFrame.Valid] dont l'enveloppe est cohérente, ou [HbFrame.Malformed] portant un
     *   motif de diagnostic **interne, en anglais**, jamais affiché (BR-SCALE-003, PRD_SCALE 18.5).
     */
    fun decode(frame: ByteArray, expectedHeader: Int = HEADER_FROM_SCALE): HbFrame {
        if (frame.size < MIN_FRAME_SIZE) {
            return HbFrame.Malformed("frame too short: ${frame.size} bytes, minimum $MIN_FRAME_SIZE")
        }

        val header = frame[0].toInt() and 0xFF
        if (header != expectedHeader) {
            return HbFrame.Malformed("unexpected header ${hex(header)}, expected ${hex(expectedHeader)}")
        }

        val trailer = frame[frame.size - 1].toInt() and 0xFF
        if (trailer != TRAILER) {
            return HbFrame.Malformed("unexpected trailer ${hex(trailer)}, expected ${hex(TRAILER)}")
        }

        val declaredLength = frame[1].toInt() and 0xFF
        val actualLength = frame.size - 2
        if (declaredLength != actualLength) {
            return HbFrame.Malformed(
                "length ${hex(declaredLength)} does not match frame size ${frame.size} " +
                    "(expected ${hex(actualLength)})",
            )
        }

        val expectedChecksum = checksum(frame, 1, frame.size - 2)
        val actualChecksum = frame[frame.size - 2].toInt() and 0xFF
        if (expectedChecksum != actualChecksum) {
            return HbFrame.Malformed(
                "checksum mismatch: computed ${hex(expectedChecksum)}, received ${hex(actualChecksum)}",
            )
        }

        // L'octet de produit est lu, exposé, et volontairement jamais comparé (BR-SCALE-004).
        return HbFrame.Valid(
            bytes = frame,
            product = frame[2].toInt() and 0xFF,
            type = frame[3].toInt() and 0xFF,
        )
    }

    /**
     * Représentation hexadécimale d'un octet, préfixée `0x`.
     *
     * Écrite à la main plutôt qu'avec `String.format` : le format par défaut suit la locale
     * courante, et une locale à chiffres non latins produirait des motifs de journal illisibles
     * pour une trame parfaitement ordinaire.
     */
    fun hex(value: Int): String {
        val byte = value and 0xFF
        return "0x${HEX_DIGITS[byte ushr 4]}${HEX_DIGITS[byte and 0x0F]}"
    }

    /** Trame entière en hexadécimal séparé par des espaces, à l'image de PRD_SCALE 14.4. */
    fun hex(bytes: ByteArray): String = bytes.joinToString(" ") {
        val byte = it.toInt() and 0xFF
        "${HEX_DIGITS[byte ushr 4]}${HEX_DIGITS[byte and 0x0F]}"
    }

    private const val HEX_DIGITS = "0123456789ABCDEF"

    private val EMPTY_DATA = ByteArray(0)
}

/**
 * Le résultat du décodage d'enveloppe de [HbFrames.decode].
 *
 * Type intermédiaire, interne au paquet protocole : il décrit la *forme* d'une trame, là où
 * `ScaleFrameEvent` décrit son *sens* pour le domaine. Les deux sont séparés parce qu'une trame
 * peut être parfaitement formée et pourtant sans intérêt — la réponse d'initialisation
 * `5A 04 00 17 13 AA` en est une (PRD_SCALE 14.3).
 */
internal sealed interface HbFrame {

    /**
     * Une trame dont l'enveloppe est cohérente : en-tête, fin, longueur et contrôle vérifiés.
     *
     * Les accesseurs travaillent en **positions absolues dans la trame**, exactement comme les
     * tableaux de PRD_SCALE 14.4 et 14.5 (« position 4 : stabilité », « positions 8–9 : poids »).
     * Traduire ces positions en décalages relatifs aux données serait une occasion d'erreur pour
     * chaque pilote de la famille, sans aucun gain.
     *
     * @property bytes La trame complète, telle qu'elle est arrivée.
     * @property product Octet de produit, exposé pour le journal, **jamais** pour valider
     *   (BR-SCALE-004).
     * @property type Octet de type, en position 3.
     */
    class Valid internal constructor(
        val bytes: ByteArray,
        val product: Int,
        val type: Int,
    ) : HbFrame {

        /**
         * Dernier index appartenant aux données : les deux derniers octets sont le contrôle et la
         * fin. C'est ce qui rend [byteAt] sûr face à une trame tronquée — une trame de poids de
         * onze octets n'a pas de position 9, et le décodage doit le dire au lieu de lire l'octet
         * de contrôle en croyant lire un poids.
         */
        val lastDataIndex: Int get() = bytes.size - 3

        /** Octet de données en position absolue [index], ou `null` si la trame n'en a pas. */
        fun byteAt(index: Int): Int? =
            if (index in HbFrames.FIRST_DATA_INDEX..lastDataIndex) bytes[index].toInt() and 0xFF else null

        /**
         * Entier 16 bits **gros-boutiste** lu en positions `[index]` et `[index] + 1`, ou `null`
         * si l'une des deux manque. Poids fort en tête : c'est l'ordre de la famille HB
         * (PRD_SCALE 14.4, 14.5).
         */
        fun uint16At(index: Int): Int? {
            val high = byteAt(index) ?: return null
            val low = byteAt(index + 1) ?: return null
            return (high shl 8) or low
        }

        override fun toString(): String =
            "HbFrame.Valid(type=${HbFrames.hex(type)}, product=${HbFrames.hex(product)}, " +
                "bytes=${HbFrames.hex(bytes)})"
    }

    /**
     * Une trame rejetée (BR-SCALE-003) : elle est journalisée et **jamais interprétée**.
     *
     * @property reason Diagnostic technique en anglais, destiné au journal interne.
     */
    data class Malformed(val reason: String) : HbFrame
}
