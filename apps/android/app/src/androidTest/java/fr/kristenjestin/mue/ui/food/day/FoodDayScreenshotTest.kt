package fr.kristenjestin.mue.ui.food.day

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Pictures of the populated day, at the ordinary font scale and at a doubled one.
 *
 * This suite exists because assertions cannot see. `MueText` ellipsises, and
 * `onNodeWithText` matches the semantics string rather than the glyphs — so an 80-character food
 * name cut down to `Golden chicken grain bowl with…` passes every check in
 * `FoodDayScreenTest` while reading wrong on the phone. The only way to know is to look, and the
 * only way to look later is to keep the picture.
 *
 * The two font scales are the point of the second half. PRD_FOOD 18 asks for the module to hold
 * at the system's own text size, and the app's bottom bar is already known to truncate `Progress`
 * and `Profile` at scale 2.0 — a defect outside this screen's scope, and a warning that a row
 * assumed to be comfortably wide is not.
 *
 * The files land beside the app's own data so `adb pull` can fetch them:
 * `/sdcard/Android/data/fr.kristenjestin.mue/files/screenshots/`.
 */
class FoodDayScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theDayAtTheOrdinaryFontScale() {
        setDay(fontScale = 1f)

        capture("food-day-populated-top")

        scrollTo(FoodTestTags.slot(MealSlot.SNACK))
        capture("food-day-populated-bottom")
    }

    /** PRD_FOOD 18: the system's text size is the reader's choice, not a suggestion. */
    @Test
    fun theDayAtTwiceTheFontScale() {
        setDay(fontScale = 2f)

        capture("food-day-scale2-top")

        scrollTo(FoodTestTags.slot(MealSlot.SNACK))
        capture("food-day-scale2-bottom")
    }

    /** The empty day, which is what the tab opens on before anything has been logged. */
    @Test
    fun theEmptyDay() {
        compose.setContent {
            MueTheme {
                FoodDayScreen(
                    state = FoodDayUiState.of(
                        date = FoodDayPreviewData.TODAY,
                        today = FoodDayPreviewData.TODAY,
                    ),
                    onPreviousDay = {},
                    onNextDay = {},
                    onOpenDatePicker = {},
                    onDismissDatePicker = {},
                    onDayPicked = {},
                    onAddToSlot = {},
                    onEditEntry = {},
                    onConfirmPlan = {},
                    onSwapPlan = {},
                    onDismissPlan = {},
                )
            }
        }

        capture("food-day-empty")
    }

    // region harness

    private fun setDay(fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                MueTheme {
                    FoodDayScreen(
                        state = previewDayState(),
                        onPreviousDay = {},
                        onNextDay = {},
                        onOpenDatePicker = {},
                        onDismissDatePicker = {},
                        onDayPicked = {},
                        onAddToSlot = {},
                        onEditEntry = {},
                        onConfirmPlan = {},
                        onSwapPlan = {},
                        onDismissPlan = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag(FoodTestTags.DAY).performScrollToNode(hasTestTag(tag))
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
