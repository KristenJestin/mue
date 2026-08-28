package fr.kristenjestin.mue.ui.food

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** The narrowest phone the app supports, which is where equal segments are tightest. */
private val NarrowWidth = 360.dp

/**
 * Pictures of the view switcher, at the ordinary font scale and at a doubled one.
 *
 * The track is a set of **equal segments**, which is the layout a long name gets cut in. No
 * assertion in the suite can see that: `MueText` ellipsises, and every matcher reads the semantics
 * string, which stays `Recipes` however the glyphs fall. `MueBottomBarLabelTest` exists because
 * `Progress` and `Profile` both reached the glass as `Pro…` with every test green. So the switcher
 * gets the same two things that bar got — a measurement it obeys, and a picture somebody looks at.
 *
 * `food-switcher-scale1.png` must show three named segments across the track, `Day` filled amber.
 * `food-switcher-scale2.png` must show three **glyphs and no words**: at twice the font scale
 * `Recipes` does not fit a third of 360 dp, so the labels go and the icons stay. If the second
 * picture ever shows a cut word, the measurement has stopped working.
 *
 * The files land beside the app's own data so `adb pull` can fetch them:
 * `/sdcard/Android/data/fr.kristenjestin.mue/files/screenshots/`.
 */
class FoodViewSwitcherScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theTrackAtTheOrdinaryFontScale() {
        setSwitcher(fontScale = 1f)

        capture("food-switcher-scale1")
    }

    /** PRD_FOOD 18: the system's text size is the reader's choice, not a suggestion. */
    @Test
    fun theTrackAtTwiceTheFontScale() {
        setSwitcher(fontScale = 2f)

        capture("food-switcher-scale2")
    }

    // region harness

    /**
     * Every selection in one picture, so the amber segment can be checked in each position.
     *
     * Pinned to [NarrowWidth] rather than to the device's own, so the picture is of the case that
     * decides — a wider emulator would fit the words at any scale and photograph nothing.
     */
    private fun setSwitcher(fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                MueTheme {
                    Column(
                        modifier = Modifier
                            .width(NarrowWidth)
                            .background(MueTheme.colors.canvas)
                            .padding(vertical = MueTheme.spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.lg),
                    ) {
                        FoodRoute.SWITCHABLE.forEach { view ->
                            FoodViewSwitcher(
                                views = FoodRoute.SWITCHABLE,
                                selected = view,
                                onSelect = {},
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(screenshotDirectory(), "$name.png")
        file.outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }

        assertTrue("$name captured nothing", bitmap.width > 0 && bitmap.height > 0)
        assertTrue("$name was not written", file.length() > 0)
    }

    private fun screenshotDirectory(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
    }

    // endregion
}
