package fr.kristenjestin.mue.ui.navigation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import fr.kristenjestin.mue.domain.model.ActivityEnvironment
import fr.kristenjestin.mue.domain.model.ActivityPreset
import fr.kristenjestin.mue.domain.model.MetricKind
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.ui.activity.ActivityIcons
import fr.kristenjestin.mue.ui.components.MueIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val RasterSize = 48
private const val Painted = '#'
private const val Blank = '.'

/**
 * The imported Lucide vectors (PRD_ACTIVITIES 14.1).
 *
 * The names are hand-authored on both sides — a constant in `ActivityIcons`, a file in
 * `res/drawable` — so the point of these tests is that neither side can drift alone. AAPT
 * happily compiles a vector whose path data is nonsense, so every icon is also drawn once:
 * only the runtime parser can tell an icon from an empty square.
 */
class MueIconTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun everyDeclaredIconIsDrawnByAnImportedVector() {
        MueIcons.names.forEach { name ->
            val resource = MueIcons.resource(name)
            assertNotNull("`$name` resolves to nothing", ContextCompat.getDrawable(context, resource))
            assertTrue(
                "`$name` draws nothing at all",
                paintedPixels(context, resource) > 0,
            )
        }
    }

    @Test
    fun anIconTheAppNeverImportedIsRefusedRatherThanDrawnBlank() {
        val failure = runCatching { MueIcons.resource("ic_teleporter") }.exceptionOrNull()

        assertNotNull("an unknown icon silently resolved", failure)
    }

    @Test
    fun everyVectorKeepsTheTwentyFourGrid() {
        val expected = (24 * context.resources.displayMetrics.density).toInt()

        MueIcons.names.forEach { name ->
            val drawable = requireNotNull(ContextCompat.getDrawable(context, MueIcons.resource(name)))
            assertEquals("`$name` is not 24 dp wide", expected, drawable.intrinsicWidth)
            assertEquals("`$name` is not 24 dp tall", expected, drawable.intrinsicHeight)
        }
    }

    /** The tab table of PRD 14.1, read back through the bar's own destinations. */
    @Test
    fun eachTabCarriesTheIconThePrdNames() {
        val expected = mapOf(
            MueDestination.ENTRY to ActivityIcons.TAB_ENTRY,
            MueDestination.PROGRESS to ActivityIcons.TAB_PROGRESS,
            MueDestination.ACTIVITY to ActivityIcons.TAB_ACTIVITY,
            MueDestination.PROFILE to ActivityIcons.TAB_PROFILE,
        )

        MueDestination.entries.forEach { destination ->
            assertEquals(expected.getValue(destination), destination.iconName)
            assertEquals(MueIcons.resource(destination.iconName), destination.iconRes)
        }
    }

    /** Whatever the module screens ask `ActivityIcons` for has to exist as a drawable. */
    @Test
    fun everyIconTheModuleCanAskForWasImported() {
        ActivityPreset.entries.forEach { preset ->
            assertTrue(paintedPixels(context, MueIcons.resource(ActivityIcons.forPreset(preset))) > 0)
        }
        MetricKind.entries.forEach { kind ->
            assertTrue(paintedPixels(context, MueIcons.resource(ActivityIcons.forMetric(kind))) > 0)
        }
        ActivityEnvironment.entries.forEach { environment ->
            val name = ActivityIcons.forEnvironment(environment)
            assertTrue(paintedPixels(context, MueIcons.resource(name)) > 0)
        }
        Movement.entries.forEach { movement ->
            val name = ActivityIcons.forMovement(movement)
            assertTrue("`$movement` draws nothing", paintedPixels(context, MueIcons.resource(name)) > 0)
        }
    }

    /**
     * Every movement is told apart by its glyph.
     *
     * PRD 14.1 tabulates the six presets only, so everything reached through the `Other` builder
     * used to share `shapes` and a mixed history read as one repeated card. `shapes` now belongs
     * to `Other` alone, and no two movements answer the same vector unless the PRD says so.
     */
    @Test
    fun onlyTheOtherMovementFallsBackOnTheGenericGlyph() {
        Movement.entries.filterNot { it == Movement.OTHER }.forEach { movement ->
            assertNotEquals(
                "`$movement` still has no icon of its own",
                ActivityIcons.SHAPES,
                ActivityIcons.forMovement(movement),
            )
        }
        assertEquals(ActivityIcons.SHAPES, ActivityIcons.forMovement(Movement.OTHER))

        // The two walks share `footprints`, but they are one movement; no two movements do.
        val shared = Movement.entries
            .groupBy(ActivityIcons::forMovement)
            .filterValues { it.size > 1 }
        assertTrue("movements share a glyph: $shared", shared.isEmpty())
    }

    /**
     * The two halves of `MueIcons` cannot drift apart.
     *
     * A name reachable from `resource` but absent from `names` is walked by none of the tests
     * above, so it ships unverified; two names resolving to one drawable is a copy-paste that
     * would put the wrong glyph on screen. The compiler sees neither.
     */
    @Test
    fun theIconTableAndTheIconListAgree() {
        val listedTwice = MueIcons.names.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue("names listed twice: $listedTwice", listedTwice.isEmpty())

        val sharedResource = MueIcons.names
            .groupBy(MueIcons::resource)
            .filterValues { it.size > 1 }
            .values
        assertTrue("names sharing one drawable: $sharedResource", sharedResource.isEmpty())

        MueIcons.timerNames.forEach {
            assertTrue("`$it` is not in MueIcons.names and would ship undrawn", it in MueIcons.names)
        }
    }

    /**
     * The Activity Timer glyphs (PRD_ACTIVITY_TIMER 6), drawn rather than merely resolved.
     *
     * SVG lets an arc pack its two flags into one token; Android's `PathParser` does not, and
     * AAPT compiles such a file without complaint — the failure arrives as a
     * `Resources$NotFoundException` at inflate, on a device, which is exactly what drawing here
     * forces. `bell`, `bell-ring`, `circle-dot`, `history`, `rotate-cw`, `more-horizontal`,
     * `pause` and `square` are all arcs, so this is the whole set bar `play`.
     */
    @Test
    fun everyTimerGlyphDrawsAShapeOfItsOwn() {
        val drawn = MueIcons.timerNames.associateWith { name ->
            mask(context, MueIcons.resource(name)).also {
                assertTrue("`$name` draws nothing at all", it.contains(Painted))
            }
        }

        // A file copied onto another would pass every check above; only the pixels tell them apart.
        val identical = drawn.entries.groupBy({ it.value }, { it.key }).values.filter { it.size > 1 }
        assertTrue("timer glyphs draw the same shape: $identical", identical.isEmpty())
    }

    private fun paintedPixels(context: Context, @DrawableRes resource: Int): Int =
        mask(context, resource).count { it == Painted }

    /** One icon rasterised to a coverage mask, so two glyphs can be compared by what they paint. */
    private fun mask(context: Context, @DrawableRes resource: Int): String {
        val drawable = requireNotNull(ContextCompat.getDrawable(context, resource))
        val bitmap = Bitmap.createBitmap(RasterSize, RasterSize, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, RasterSize, RasterSize)
        drawable.setTint(Color.WHITE)
        drawable.draw(Canvas(bitmap))

        val mask = buildString(RasterSize * RasterSize) {
            for (y in 0 until RasterSize) {
                for (x in 0 until RasterSize) {
                    append(if (Color.alpha(bitmap.getPixel(x, y)) > 0) Painted else Blank)
                }
            }
        }
        bitmap.recycle()
        return mask
    }
}
