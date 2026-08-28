package fr.kristenjestin.mue.ui.food.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * A real EAN-13 symbol, drawn from the GS1 specification, so a device test can decode one.
 *
 * ## Why it is generated rather than photographed
 *
 * A camera cannot run in a test and the emulator's camera is a virtual scene that cannot be made
 * to hold a jar of Nutella. What *can* be checked on a device is the half that actually does the
 * work: that ML Kit, configured exactly as `MlKitBarcodeDecoder` configures it, turns an image of
 * a barcode into the digits printed under it. That needs an image of a barcode, and the honest
 * way to get one without a binary blob in the repository is to encode it from the specification.
 *
 * A bug in this file cannot make the test pass by accident. The last digit of an EAN-13 is a
 * checksum over the first twelve, and the parity pattern of the left-hand group is chosen *by*
 * the first digit — so a symbol built with the wrong tables decodes to nothing at all, not to the
 * wrong number. Getting `3017620422003` back therefore proves both that the encoder is right and
 * that the detector read it.
 *
 * ## The encoding, in one paragraph
 *
 * Thirteen digits are drawn as twelve. The first digit is not drawn at all: it is carried by
 * *which* of the L and G alphabets each of the next six digits uses ([PARITY]). Then a centre
 * guard, then the last six digits in the R alphabet, then the end guard. Every digit is seven
 * modules wide, and the whole symbol is 95 modules plus a quiet zone — which is the part most
 * hand-rolled generators forget, and without which no detector will look at it.
 */
internal object Ean13Fixture {

    /** The left-hand odd alphabet, one seven-bit pattern per digit. */
    private val L = intArrayOf(
        0b0001101, 0b0011001, 0b0010011, 0b0111101, 0b0100011,
        0b0110001, 0b0101111, 0b0111011, 0b0110111, 0b0001011,
    )

    /** The left-hand even alphabet: [L] reversed, bit for bit. */
    private val G = intArrayOf(
        0b0100111, 0b0110011, 0b0011011, 0b0100001, 0b0011101,
        0b0111001, 0b0000101, 0b0010001, 0b0001001, 0b0010111,
    )

    /** The right-hand alphabet: [L] complemented, which is what makes it self-orienting. */
    private val R = IntArray(10) { L[it].inv() and 0b1111111 }

    /**
     * How the first digit is encoded: which of the six left-hand digits use [G] rather than [L].
     *
     * This is the table that makes EAN-13 hold thirteen digits in twelve positions, and it is why
     * a symbol built with the wrong first digit decodes to nothing rather than to a near miss.
     */
    private val PARITY = arrayOf(
        "LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG",
        "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL", "LGGLGL",
    )

    /** One module in pixels: wide enough that no resampling can close a bar. */
    private const val MODULE = 4

    /**
     * The quiet zone, in modules, either side.
     *
     * The specification asks for eleven and detectors enforce something like it. It is the single
     * most common reason a hand-made barcode "does not scan": the bars are perfect and there is
     * nothing around them for the detector to find an edge against.
     */
    private const val QUIET_MODULES = 12

    private const val HEIGHT_MODULES = 60

    /**
     * The symbol for [digits], as a black-on-white bitmap.
     *
     * @throws IllegalArgumentException when [digits] is not thirteen digits whose checksum agrees
     * — a fixture that is not a valid EAN-13 would make a failing test look like a broken
     * detector.
     */
    fun bitmap(digits: String): Bitmap {
        require(digits.length == 13 && digits.all(Char::isDigit)) { "not 13 digits: $digits" }
        require(checkDigit(digits.take(12)) == digits[12].digitToInt()) {
            "checksum of $digits is wrong; the fixture is not a real EAN-13"
        }

        val modules = buildModules(digits)
        val width = (modules.size + QUIET_MODULES * 2) * MODULE
        val height = HEIGHT_MODULES * MODULE
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint().apply { color = Color.BLACK }
        modules.forEachIndexed { index, dark ->
            if (!dark) return@forEachIndexed
            val left = ((index + QUIET_MODULES) * MODULE).toFloat()
            canvas.drawRect(left, 0f, left + MODULE, height.toFloat(), paint)
        }
        return bitmap
    }

    /** The 95 modules of the symbol proper, `true` where a bar is. */
    private fun buildModules(digits: String): BooleanArray {
        val bits = StringBuilder()
        bits.append("101")
        val parity = PARITY[digits[0].digitToInt()]
        digits.substring(1, 7).forEachIndexed { index, digit ->
            val alphabet = if (parity[index] == 'L') L else G
            bits.append(seven(alphabet[digit.digitToInt()]))
        }
        bits.append("01010")
        digits.substring(7).forEach { digit -> bits.append(seven(R[digit.digitToInt()])) }
        bits.append("101")
        return BooleanArray(bits.length) { bits[it] == '1' }
    }

    private fun seven(pattern: Int): String =
        pattern.toString(2).padStart(7, '0')

    /** GS1's modulo-10 check digit: odd positions weigh one, even positions weigh three. */
    private fun checkDigit(first12: String): Int {
        val sum = first12.mapIndexed { index, digit ->
            digit.digitToInt() * if (index % 2 == 0) 1 else 3
        }.sum()
        return (10 - sum % 10) % 10
    }
}
