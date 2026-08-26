package fr.kristenjestin.muespike

/**
 * The frame format we believe the HB BODY FAT scale speaks, and nothing more.
 *
 * Every value here is a hypothesis to be confirmed against the real device. The decoder is
 * therefore deliberately permissive: it reports what it thinks a frame means, it reports when
 * the checksum disagrees, and it never drops bytes. The raw hex log stays the source of truth.
 *
 * Frame layout, both directions:
 *
 *     [hdr][len][0x26][type][data…][chk][0xAA]
 *
 * `hdr` is 0x5A scale→phone and 0xA5 phone→scale; `len` counts every byte after itself, i.e.
 * 0x26 through the trailer; `chk` is the XOR of `len` through the last data byte.
 */
object ScaleProtocol {

    const val HEADER_SCALE: Int = 0x5A
    const val HEADER_PHONE: Int = 0xA5
    const val PRODUCT_ID: Int = 0x26
    const val TRAILER: Int = 0xAA

    const val TYPE_WEIGHT: Int = 0x10
    const val TYPE_IMPEDANCE: Int = 0x11
    const val STABLE_FLAG: Int = 0x02

    /** The three frames the vendor app sends before the scale streams anything. */
    val INIT_FRAMES: List<ByteArray> = listOf(
        buildCommand(0x33, byteArrayOf(0x00)),
        buildCommand(0x44, byteArrayOf()),
        buildCommand(0x17, byteArrayOf(0x01)),
    )

    /** Sent once a stable weight arrives; believed to be what triggers the impedance sweep. */
    val ACK_WEIGHT: ByteArray = buildCommand(TYPE_WEIGHT, byteArrayOf(STABLE_FLAG.toByte()))

    /** Sent once impedance arrives, mirroring the vendor app. */
    val ACK_IMPEDANCE: ByteArray = buildCommand(TYPE_IMPEDANCE, byteArrayOf(0x00))

    fun buildCommand(type: Int, data: ByteArray): ByteArray {
        val frame = ByteArray(data.size + 6)
        frame[0] = HEADER_PHONE.toByte()
        frame[1] = (data.size + 4).toByte()
        frame[2] = PRODUCT_ID.toByte()
        frame[3] = type.toByte()
        data.copyInto(frame, 4)
        frame[frame.size - 2] = xor(frame, 1, frame.size - 2).toByte()
        frame[frame.size - 1] = TRAILER.toByte()
        return frame
    }

    /** XOR of `frame[from until toExclusive]`. */
    fun xor(frame: ByteArray, from: Int, toExclusive: Int): Int {
        var acc = 0
        for (i in from until toExclusive) acc = acc xor (frame[i].toInt() and 0xFF)
        return acc and 0xFF
    }

    /**
     * What we make of one notification payload.
     *
     * [checksumOk] is reported rather than enforced. A frame whose checksum is wrong is still
     * decoded and still logged: on an unknown device, a checksum that never matches is far
     * more likely to mean our formula is wrong than that the scale is broken.
     */
    data class Decoded(
        val shapeOk: Boolean,
        val checksumOk: Boolean,
        val type: Int?,
        val stabilityFlag: Int?,
        val weightHundredthsKg: Int?,
        val impedanceOhm: Int?,
        val notes: List<String>,
    ) {
        val isStableWeight: Boolean
            get() = type == TYPE_WEIGHT && stabilityFlag == STABLE_FLAG && weightHundredthsKg != null

        val isImpedance: Boolean
            get() = type == TYPE_IMPEDANCE && impedanceOhm != null
    }

    fun decode(frame: ByteArray): Decoded {
        val notes = mutableListOf<String>()

        if (frame.size < 6) {
            return Decoded(false, false, null, null, null, null, listOf("trop court (${frame.size} o)"))
        }

        val hdr = frame[0].toInt() and 0xFF
        val len = frame[1].toInt() and 0xFF
        val pid = frame[2].toInt() and 0xFF
        val type = frame[3].toInt() and 0xFF
        val trailer = frame[frame.size - 1].toInt() and 0xFF

        var shapeOk = true
        if (hdr != HEADER_SCALE) {
            shapeOk = false
            notes += "entête ${hex(hdr)} au lieu de 5A"
        }
        if (pid != PRODUCT_ID) {
            shapeOk = false
            notes += "octet produit ${hex(pid)} au lieu de 26"
        }
        if (trailer != TRAILER) {
            shapeOk = false
            notes += "fin ${hex(trailer)} au lieu de AA"
        }
        if (len != frame.size - 2) {
            shapeOk = false
            notes += "len=$len mais taille-2=${frame.size - 2}"
        }

        val expected = xor(frame, 1, frame.size - 2)
        val actual = frame[frame.size - 2].toInt() and 0xFF
        val checksumOk = expected == actual
        if (!checksumOk) notes += "checksum attendu ${hex(expected)}, reçu ${hex(actual)}"

        // Decoded even when the shape is off, because a near-miss is exactly what tells us
        // which part of the hypothesis to move.
        var flag: Int? = null
        var weight: Int? = null
        var ohm: Int? = null

        when (type) {
            TYPE_WEIGHT -> {
                flag = frame[4].toInt() and 0xFF
                if (frame.size >= 12) {
                    weight = ((frame[8].toInt() and 0xFF) shl 8) or (frame[9].toInt() and 0xFF)
                } else {
                    notes += "trame poids de ${frame.size} o, attendu >= 12"
                }
            }

            TYPE_IMPEDANCE -> {
                if (frame.size >= 13) {
                    ohm = ((frame[9].toInt() and 0xFF) shl 8) or (frame[10].toInt() and 0xFF)
                } else {
                    notes += "trame impédance de ${frame.size} o, attendu >= 13"
                }
            }

            else -> notes += "type ${hex(type)} inconnu"
        }

        return Decoded(shapeOk, checksumOk, type, flag, weight, ohm, notes)
    }

    /** A one-line human reading of [decode], for the log and the screen. */
    fun describe(frame: ByteArray): String {
        val d = decode(frame)
        val parts = mutableListOf<String>()
        d.type?.let { parts += "type=${hex(it)}" }
        d.stabilityFlag?.let { parts += "flag=${hex(it)}${if (it == STABLE_FLAG) " STABLE" else " (instable)"}" }
        d.weightHundredthsKg?.let { parts += "poids=${formatKg(it)} kg (brut $it)" }
        d.impedanceOhm?.let { parts += "impédance=$it Ω" }
        if (!d.shapeOk) parts += "FORME KO"
        if (!d.checksumOk) parts += "CHECKSUM KO"
        if (d.notes.isNotEmpty()) parts += d.notes.joinToString("; ")
        return if (parts.isEmpty()) "non décodée" else parts.joinToString(" | ")
    }

    fun formatKg(hundredths: Int): String = "%.2f".format(hundredths / 100.0)

    fun hex(value: Int): String = "%02X".format(value and 0xFF)

    fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
