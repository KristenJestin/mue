package fr.kristenjestin.mue.ui.food

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import fr.kristenjestin.mue.ui.components.MuePreviewHost
import fr.kristenjestin.mue.ui.components.MueStepper
import fr.kristenjestin.mue.ui.food.add.FoodAddActions
import fr.kristenjestin.mue.ui.food.add.FoodAddMessages
import fr.kristenjestin.mue.ui.food.add.FoodAddScreen
import fr.kristenjestin.mue.ui.food.add.RecipePickerScreen
import fr.kristenjestin.mue.ui.food.add.previewPathsState
import fr.kristenjestin.mue.ui.food.add.previewPortionsState
import fr.kristenjestin.mue.ui.food.add.previewQuickState
import fr.kristenjestin.mue.ui.food.add.previewRecipePickerState
import fr.kristenjestin.mue.ui.food.add.previewRecipeServingsState
import fr.kristenjestin.mue.ui.food.add.previewScanRefusedState
import fr.kristenjestin.mue.ui.food.catalogue.FoodEditorActions
import fr.kristenjestin.mue.ui.food.catalogue.FoodEditorScreen
import fr.kristenjestin.mue.ui.food.catalogue.previewFoodEditorState
import fr.kristenjestin.mue.ui.profile.FoodPreferencesScreen
import fr.kristenjestin.mue.ui.profile.FoodPreferencesUiState
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Pictures of the five things the owner reported, at the ordinary font scale and at a doubled one.
 *
 * The suite exists for the reason `FoodAddScreenshotTest` gives — assertions cannot see — and for
 * one more that is particular to this work: **three of the five defects are invisible to any
 * matcher by construction.** A control faded to nothing by the header's ramp is still in the
 * semantics tree with all of its text; a title that names the wrong screen is a perfectly valid
 * string; and a track eight dp too tall reads as a correct 48 dp touch target in every assertion
 * written about it. What is left is to look.
 *
 * What to look for in each pair:
 *
 * - `sheet-paths`, `sheet-scan`, `sheet-quick`, `sheet-amount`, `sheet-servings` — **one** control
 *   in the header, an arrow and never a cross, and a title naming *that* stage rather than
 *   `Add food` on all five. No `Choose another way` row under the header.
 * - `sheet-amount` and `sheet-servings` also carry the stepper: a bordered field with `−` and `+`,
 *   not a pair of chevrons, and at scale 2.0 the two buttons drop **under** the value rather than
 *   squeezing it.
 * - `new-food` — the provenance card's top border is whole. That edge was dissolved, which is the
 *   defect he could not scroll to.
 * - `food-preferences`, `recipe-picker` — the same, on the two screens where no gesture could
 *   have rescued it.
 * - `stepper` — the shared component alone, both ends of its range, enabled and disabled.
 *
 * The files land beside the app's own data so `adb pull` can fetch them:
 * `/sdcard/Android/data/fr.kristenjestin.mue/files/screenshots/`.
 */
class FoodSheetConsistencyScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    // region the five stages of the sheet (defects 1, 3 and 4)

    @Test
    fun theWaysInAtBothFontScales() {
        show(1f) { FoodAddScreen(state = previewPathsState(), actions = FoodAddActions()) }
        capture("sheet-paths")
        rescale(2f)
        capture("sheet-paths-scale2")
    }

    /** The panel he named first: it used to say `Add food` over a cross. */
    @Test
    fun theScanAtBothFontScales() {
        show(1f) { FoodAddScreen(state = previewScanRefusedState(), actions = FoodAddActions()) }
        capture("sheet-scan")
        rescale(2f)
        capture("sheet-scan-scale2")
    }

    /** The panel he named second, for the same reason. */
    @Test
    fun theQuickAddAtBothFontScales() {
        show(1f) { FoodAddScreen(state = previewQuickState(), actions = FoodAddActions()) }
        capture("sheet-quick")
        rescale(2f)
        capture("sheet-quick-scale2")
    }

    /** The usual-portions counter, which is where the carets were. */
    @Test
    fun theAmountAtBothFontScales() {
        show(1f) { FoodAddScreen(state = previewPortionsState(), actions = FoodAddActions()) }
        capture("sheet-amount")
        rescale(2f)
        capture("sheet-amount-scale2")
    }

    /** The recipe line, which was a required empty text field. */
    @Test
    fun theServingsAtBothFontScales() {
        show(1f) {
            FoodAddScreen(state = previewRecipeServingsState(), actions = FoodAddActions())
        }
        capture("sheet-servings")
        rescale(2f)
        capture("sheet-servings-scale2")
    }

    // endregion

    // region the screens whose header hid their content (defect 2)

    @Test
    fun theFoodEditorAtBothFontScales() {
        show(1f) {
            FoodEditorScreen(state = previewFoodEditorState(), actions = FoodEditorActions())
        }
        capture("new-food")
        rescale(2f)
        capture("new-food-scale2")
    }

    /**
     * A `Profile` screen now, pictured by this suite anyway.
     *
     * The suite is the record of five reported defects, and this screen is where one of them was
     * total — one card shorter than its own viewport, so no gesture could reach what the ramp had
     * dissolved. Moving the picture out with the package would leave that pair unwatched and buy a
     * second copy of the harness above. What it shows is `MueSubScreenScaffold`'s ramp, which is
     * the same component on either tab.
     */
    @Test
    fun theFoodPreferencesAtBothFontScales() {
        show(1f) {
            FoodPreferencesScreen(
                state = FoodPreferencesUiState(showEnergy = true),
                onShowEnergyChange = {},
                onBack = {},
            )
        }
        capture("food-preferences")
        rescale(2f)
        capture("food-preferences-scale2")
    }

    @Test
    fun theRecipePickerAtBothFontScales() {
        show(1f) {
            RecipePickerScreen(
                state = previewRecipePickerState(),
                onQueryChange = {},
                onClearQuery = {},
                onPicked = {},
                onCreateRecipe = {},
                onBack = {},
            )
        }
        capture("recipe-picker")
        rescale(2f)
        capture("recipe-picker-scale2")
    }

    // endregion

    // region the shared stepper (defect 3)

    /**
     * The component on its own, at both ends of a range and in the middle of one.
     *
     * At scale 2.0 the two buttons must sit **under** the value rather than beside it — that is
     * [fr.kristenjestin.mue.ui.components.MueSplitRow] inside `MueFieldContainer` doing the work
     * a `Row` with a weight would have got wrong.
     */
    @Test
    fun theStepperAtBothFontScales() {
        show(1f) {
            MuePreviewHost(padding = 16) {
                MueStepper(
                    label = FoodAddMessages.SERVINGS_LABEL,
                    value = "1",
                    onDecrement = {},
                    onIncrement = {},
                    decrementLabel = FoodAddMessages.FEWER_SERVINGS,
                    incrementLabel = FoodAddMessages.MORE_SERVINGS,
                    canDecrement = false,
                )
                MueStepper(
                    label = FoodAddMessages.PORTIONS_LABEL,
                    value = "1.5 × 1 apple",
                    onDecrement = {},
                    onIncrement = {},
                    decrementLabel = FoodAddMessages.FEWER_PORTIONS,
                    incrementLabel = FoodAddMessages.MORE_PORTIONS,
                )
            }
        }
        capture("stepper")
        rescale(2f)
        capture("stepper-scale2")
    }

    // endregion

    // region harness

    private val scale = mutableStateOf(1f)
    private val screen = mutableStateOf<(@Composable () -> Unit)?>(null)
    private var started = false

    /** PRD 15's narrowest phone is where a doubled font scale hurts, so that is what is pictured. */
    private fun show(fontScale: Float, content: @Composable () -> Unit) {
        screen.value = content
        scale.value = fontScale
        if (!started) {
            started = true
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, scale.value),
                ) {
                    MueTheme { screen.value?.invoke() }
                }
            }
        }
        compose.waitForIdle()
    }

    /** The same content again at another text size; `setContent` may only be called once. */
    private fun rescale(fontScale: Float) {
        scale.value = fontScale
        compose.waitForIdle()
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
