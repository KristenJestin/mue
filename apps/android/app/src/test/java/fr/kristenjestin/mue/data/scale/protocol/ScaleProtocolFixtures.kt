package fr.kristenjestin.mue.data.scale.protocol

import fr.kristenjestin.mue.domain.model.ScaleAdvertisement

/*
 * Les trames relevées sur la Homebuds HB9027 réelle le 26 août 2026, **verbatim**
 * (PRD_SCALE 14.3, 14.4, 14.5 — exigé par FR-SCALE-031).
 *
 * Elles sont ici, en un seul endroit, sous la forme hexadécimale exacte du PRD : c'est ce qui
 * permet de les relire à côté du document sans les traduire mentalement, et de les comparer
 * caractère par caractère si un jour un pilote de la même famille se comporte différemment.
 * « Ce qui a été observé une fois ne doit jamais avoir besoin d'être réobservé » (PRD_SCALE 15).
 */

/** PRD_SCALE 14.4 — `0x217F` = 8575 centièmes = 85,75 kg, stabilité `0x02`. */
internal const val REAL_STABLE_WEIGHT_FRAME = "5A 0A 00 10 02 00 00 21 21 7F 67 AA"

/** PRD_SCALE 14.5 — `0x0221` = 545 Ω. */
internal const val REAL_IMPEDANCE_FRAME = "5A 0B 00 11 00 00 00 FF FF 02 21 39 AA"

/** PRD_SCALE 14.5 — `0xFFFF` : aucune mesure possible (BR-SCALE-005). */
internal const val REAL_IMPEDANCE_ABSENT_FRAME = "5A 0B 00 11 00 00 00 FF FF FF FF 1A AA"

/** PRD_SCALE 14.3 — la réponse de l'appareil à la séquence d'initialisation. */
internal const val REAL_INIT_REPLY_FRAME = "5A 04 00 17 13 AA"

/** PRD_SCALE 14.3 — les trois commandes d'initialisation, dans l'ordre d'émission. */
internal val REAL_INIT_COMMANDS = listOf(
    "A5 05 26 33 00 10 AA",
    "A5 04 26 44 66 AA",
    "A5 05 26 17 01 35 AA",
)

/** PRD_SCALE 14.3 — l'acquittement du premier poids stable. Sans lui, aucune impédance. */
internal const val REAL_WEIGHT_ACK = "A5 05 26 10 02 31 AA"

/** PRD_SCALE 14.3 — l'acquittement de la trame d'impédance. */
internal const val REAL_IMPEDANCE_ACK = "A5 05 26 11 00 32 AA"

/** L'annonce BLE de l'appareil réel (PRD_SCALE 14.1) : nom annoncé, adresse statique aléatoire. */
internal val REAL_HB9027_ADVERTISEMENT = ScaleAdvertisement(
    address = "FF:10:00:1F:52:C3",
    name = "HB BODY FAT",
    serviceUuids = listOf("0000fff0-0000-1000-8000-00805f9b34fb"),
    manufacturerData = emptyMap(),
)

/** Une annonce d'un appareil sans rapport, qu'aucun pilote ne doit revendiquer. */
internal val UNRELATED_ADVERTISEMENT = ScaleAdvertisement(
    address = "C4:1B:33:0A:9E:71",
    name = "Mi Body Composition Scale 2",
    serviceUuids = listOf("0000181b-0000-1000-8000-00805f9b34fb"),
    manufacturerData = mapOf(0x0157 to byteArrayOf(0x01, 0x02)),
)

/** `"5A 0A 00"` → les octets correspondants. Tolère les espaces multiples et les sauts de ligne. */
internal fun hexToBytes(hex: String): ByteArray {
    val tokens = hex.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return ByteArray(tokens.size) { tokens[it].toInt(16).toByte() }
}

/** L'inverse de [hexToBytes], pour que l'échec d'une assertion se lise comme le PRD. */
internal fun ByteArray.toHex(): String = HbFrames.hex(this)

/** Une annonce dont seul le nom compte, les autres champs étant sans intérêt ici. */
internal fun advertisementNamed(name: String?) = ScaleAdvertisement(
    address = "00:11:22:33:44:55",
    name = name,
    serviceUuids = emptyList(),
    manufacturerData = emptyMap(),
)
