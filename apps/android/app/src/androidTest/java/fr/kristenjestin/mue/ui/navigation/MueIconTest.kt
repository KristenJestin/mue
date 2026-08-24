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

    private fun paintedPixels(context: Context, @DrawableRes resource: Int): Int {
        val drawable = requireNotNull(ContextCompat.getDrawable(context, resource))
        val bitmap = Bitmap.createBitmap(RasterSize, RasterSize, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, RasterSize, RasterSize)
        drawable.setTint(Color.WHITE)
        drawable.draw(Canvas(bitmap))

        var painted = 0
        for (x in 0 until RasterSize) {
            for (y in 0 until RasterSize) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 0) painted++
            }
        }
        bitmap.recycle()
        return painted
    }
}
