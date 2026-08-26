package fr.kristenjestin.mue.ui.food.add

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Pictures of the add flow, at the ordinary font scale and at a doubled one.
 *
 * This suite exists because assertions cannot see. `MueText` ellipsises and `onNodeWithText`
 * matches the semantics string rather than the glyphs, so an 80-character food name cut to
 * `Golden chicken grain bowl with…` passes every check in [FoodAddScreenTest] while reading wrong
 * on the phone. The only way to know is to look, and the only way to look later is to keep the
 * picture.
 *
 * The three that matter most are here: **the cooked rice**, where the state word and the
 * converted weight have to be legible or the number means something else; **the quick add**,
 * where four dashes sit beside a real energy; and **the picker**, where the catalogue's longest
 * names are listed.
 *
 * The files land beside the app's own data so `adb pull` can fetch them:
 * `/sdcard/Android/data/fr.kristenjestin.mue/files/screenshots/`.
 */
class FoodAddScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theSheetAtTheOrdinaryFontScale() {
        showSheet(previewCookedState(), fontScale = 1f)

        capture("food-add-cooked-top")

        compose.onNodeWithTag(FoodTestTags.SLOT_PICKER).performScrollTo()
        compose.waitForIdle()
        capture("food-add-cooked-bottom")
    }

    /** PRD_FOOD 18: the system's text size is the reader's choice, not a suggestion. */
    @Test
    fun theSheetAtTwiceTheFontScale() {
        showSheet(previewCookedState(), fontScale = 2f)

        capture("food-add-cooked-scale2-top")

        compose.onNodeWithTag(FoodTestTags.SLOT_PICKER).performScrollTo()
        compose.waitForIdle()
        capture("food-add-cooked-scale2-bottom")
    }

    /** PRD_FOOD 13.1 in one picture: an energy that is known beside four values that are not. */
    @Test
    fun theQuickAddAtBothFontScales() {
        showSheet(previewQuickState(), fontScale = 1f)
        capture("food-add-quick")

        showSheet(previewQuickState(), fontScale = 2f, again = true)
        capture("food-add-quick-scale2")
    }

    @Test
    fun thePickerAtBothFontScales() {
        showPicker(fontScale = 1f)
        capture("food-picker")

        showPicker(fontScale = 2f, again = true)
        capture("food-picker-scale2")
    }

    // region harness

    private var scale = androidx.compose.runtime.mutableStateOf(1f)
    private var sheet = androidx.compose.runtime.mutableStateOf<FoodAddUiState?>(null)
    private var picker = androidx.compose.runtime.mutableStateOf<FoodPickerUiState?>(null)
    private var started = false

    private fun showSheet(state: FoodAddUiState, fontScale: Float, again: Boolean = false) {
        sheet.value = state
        picker.value = null
        scale.value = fontScale
        if (!again) start()
        compose.waitForIdle()
    }

    private fun showPicker(fontScale: Float, again: Boolean = false) {
        sheet.value = null
        picker.value = previewPickerState()
        scale.value = fontScale
        if (!again) start()
        compose.waitForIdle()
    }

    /**
     * One `setContent` per test, whatever is being pictured.
     *
     * The scale and the screen are held in state so a test can photograph the same content twice
     * at two sizes: `setContent` may only be called once.
     */
    private fun start() {
        if (started) return
        started = true
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, scale.value),
            ) {
                MueTheme {
                    sheet.value?.let { FoodAddScreen(state = it, actions = FoodAddActions()) }
                    picker.value?.let {
                        FoodPickerScreen(
                            state = it,
                            onQueryChange = {},
                            onClearQuery = {},
                            onSourceSelected = {},
                            onPicked = {},
                            onCreateFood = {},
                            onBack = {},
                        )
                    }
                }
            }
        }
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "screenshots",
        )
        directory.mkdirs()
        val file = File(directory, "$name.png")
        file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        assertTrue("$file was not written", file.length() > 0L)
    }

    // endregion
}
