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
 * at the system's own text size, and the app's bottom bar was truncating `Progress` and `Profile`
 * to the same `Pro…` at scale 2.0 — a warning that a row assumed to be comfortably wide is not.
 * At that scale this screen's own lines were worse: the energy figures took the row, the name was
 * left a ribbon and broke mid-word, and `1 × serving` came out one letter per line.
 *
 * Each day is pictured as far as it goes and no further. The populated day needs two, and the
 * second scrolls to the **dinner proposal** rather than to the snack — the snack is already on
 * the first picture at the ordinary scale, so scrolling to it moved nothing and wrote the same
 * bytes twice while PRD_FOOD 12's proposal card went unphotographed. A day holding one line, or
 * none, fits on one screen and gets one picture.
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

        scrollTo(FoodTestTags.plan(MealSlot.DINNER))
        capture("food-day-populated-bottom")
    }

    /** PRD_FOOD 18: the system's text size is the reader's choice, not a suggestion. */
    @Test
    fun theDayAtTwiceTheFontScale() {
        setDay(fontScale = 2f)

        capture("food-day-scale2-top")

        scrollTo(FoodTestTags.plan(MealSlot.DINNER))
        capture("food-day-scale2-bottom")
    }

    /**
     * The empty day, which is what the tab opens on before anything has been logged.
     *
     * Its pair is [theDayWhoseProteinIsUnknown], and the two are the picture of PRD_FOOD 13.2.
     * `food-day-empty.png` must show four headings with **no figure beside any of them**;
     * `food-day-unknown-protein.png` must show a snack reading `≈ 420 kcal` and `— protein`.
     * If those two images ever look alike, the module has lost the difference between "nobody
     * wrote this down" and "this is zero", and no assertion in the suite will say so.
     */
    @Test
    fun theEmptyDay() {
        setDay(state = emptyDayState())

        capture("food-day-empty")
    }

    /** The other half of PRD_FOOD 13.2: one line, an energy known and a protein that is not. */
    @Test
    fun theDayWhoseProteinIsUnknown() {
        setDay(state = unknownProteinDayState())

        capture("food-day-unknown-protein")
    }

    // region harness

    private fun setDay(
        fontScale: Float = 1f,
        state: FoodDayUiState = previewDayState(),
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                MueTheme {
                    FoodDayScreen(
                        state = state,
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
