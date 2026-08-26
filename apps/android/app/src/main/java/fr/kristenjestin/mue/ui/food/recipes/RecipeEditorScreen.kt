package fr.kristenjestin.mue.ui.food.recipes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.model.RecipeType
import fr.kristenjestin.mue.ui.activity.ActivityIcons
import fr.kristenjestin.mue.ui.components.MueDashedAction
import fr.kristenjestin.mue.ui.components.MueDivider
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MueIcons
import fr.kristenjestin.mue.ui.components.MuePeriodPill
import fr.kristenjestin.mue.ui.components.MuePreviewHost
import fr.kristenjestin.mue.ui.components.MuePrimaryButton
import fr.kristenjestin.mue.ui.components.MueStickyActionRamp
import fr.kristenjestin.mue.ui.components.MueStickyBottomAction
import fr.kristenjestin.mue.ui.components.MueSubScreenScaffold
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.components.MueTextField
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.food.day.announcedAs
import fr.kristenjestin.mue.ui.theme.MueTheme

private val BackIconSize: Dp = 18.dp

/**
 * The recipe form (PRD_FOOD 11, FR-RECIPE-001 to 003), wired to the saved recipes.
 *
 * [recipeId] is null while creating, which is what tells `Save recipe` from `Save changes` — the
 * same distinction `Log activity` draws from a session id.
 */
@Composable
internal fun RecipeEditorRoute(
    recipeId: RecipeId?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecipeEditorViewModel = recipeEditorViewModel(),
) {
    // Idempotent: a recomposition must not throw away what has been typed.
    LaunchedEffect(recipeId) { viewModel.start(recipeId) }

    val state by viewModel.uiState.collectAsStateWithLifecycle()

    RecipeEditorScreen(
        state = state,
        actions = RecipeEditorActions(
            onBack = onBack,
            onNameChange = viewModel::onNameChange,
            onTypeSelected = viewModel::onTypeSelected,
            onBaseServingsChange = viewModel::onBaseServingsChange,
            onPrepTimeChange = viewModel::onPrepTimeChange,
            onDescriptionChange = viewModel::onDescriptionChange,
            onStepsChange = viewModel::onStepsChange,
            onQuantityChange = viewModel::onQuantityChange,
            onRemoveIngredient = viewModel::onRemoveIngredient,
            onOpenPicker = viewModel::onOpenPicker,
            onPickerQueryChange = viewModel::onPickerQueryChange,
            onPickFood = viewModel::onPickFood,
            onClosePicker = viewModel::onClosePicker,
            onSave = viewModel::onSave,
            onSaved = {
                viewModel.onSaved()
                onSaved()
            },
        ),
        modifier = modifier,
    )
}

/** What the form can do, gathered so the screen takes one parameter instead of fourteen. */
internal data class RecipeEditorActions(
    val onBack: () -> Unit = {},
    val onNameChange: (String) -> Unit = {},
    val onTypeSelected: (RecipeType) -> Unit = {},
    val onBaseServingsChange: (String) -> Unit = {},
    val onPrepTimeChange: (String) -> Unit = {},
    val onDescriptionChange: (String) -> Unit = {},
    val onStepsChange: (String) -> Unit = {},
    val onQuantityChange: (Int, String) -> Unit = { _, _ -> },
    val onRemoveIngredient: (Int) -> Unit = {},
    val onOpenPicker: () -> Unit = {},
    val onPickerQueryChange: (String) -> Unit = {},
    val onPickFood: (String) -> Unit = {},
    val onClosePicker: () -> Unit = {},
    val onSave: () -> Unit = {},
    /** Fired once the save button has finished discharging (contract decision 8). */
    val onSaved: () -> Unit = {},
)

/**
 * Name, moment, servings, preparation time, description, ingredients and steps.
 *
 * Every sentence beside a field is [fr.kristenjestin.mue.domain.logic.FoodValidation]'s, arriving
 * on [RecipeEditorUiState] already worded; this screen decides where an error sits and never
 * what it says. A refused save reveals them and empties nothing (PRD_FOOD 15).
 *
 * The pinned save uses the shipped [MueStickyBottomAction], whose ramp holds no pointer input —
 * so a thumb landing in the fade still scrolls the form instead of meeting the invisible dead
 * zone `Log activity` once had.
 */
@Composable
internal fun RecipeEditorScreen(
    state: RecipeEditorUiState,
    actions: RecipeEditorActions,
    modifier: Modifier = Modifier,
) {
    val spacing = MueTheme.spacing
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    var actionHeight by remember { mutableStateOf(0.dp) }

    Box(modifier = modifier.fillMaxSize().testTag(FoodTestTags.RECIPE_EDITOR)) {
        MueSubScreenScaffold(
            title = state.screenTitle,
            onNavigateBack = actions.onBack,
            navigationIcon = {
                MueIcon(
                    iconName = MueIcons.ARROW_LEFT,
                    tint = MueTheme.colors.textSecondary,
                    size = BackIconSize,
                )
            },
            navigationContentDescription = RecipeMessages.BACK,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // The band's clearance, split where the band is split: the viewport ends
                    // above the solid block, and the ramp draws over live content.
                    .padding(bottom = (actionHeight - MueStickyActionRamp).coerceAtLeast(0.dp))
                    .verticalScroll(scroll)
                    .padding(bottom = MueStickyActionRamp, top = spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.lg),
            ) {
                MueTextField(
                    label = RecipeMessages.NAME_LABEL,
                    value = state.name,
                    onValueChange = actions.onNameChange,
                    placeholder = RecipeMessages.NAME_PLACEHOLDER,
                    errorMessage = state.nameError,
                    modifier = Modifier.testTag(FoodTestTags.RECIPE_NAME_FIELD),
                )

                TypeChooser(state, actions)

                MueTextField(
                    label = RecipeMessages.SERVINGS_LABEL,
                    value = state.baseServings,
                    onValueChange = actions.onBaseServingsChange,
                    errorMessage = state.baseServingsError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.testTag(FoodTestTags.RECIPE_SERVINGS_FIELD),
                )

                MueTextField(
                    label = RecipeMessages.PREP_TIME_LABEL,
                    value = state.prepTime,
                    onValueChange = actions.onPrepTimeChange,
                    placeholder = RecipeMessages.OPTIONAL_PLACEHOLDER,
                    suffix = RecipeMessages.PREP_TIME_SUFFIX,
                    errorMessage = state.prepTimeError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.testTag(FoodTestTags.RECIPE_PREP_TIME_FIELD),
                )

                MueTextField(
                    label = RecipeMessages.DESCRIPTION_LABEL,
                    value = state.description,
                    onValueChange = actions.onDescriptionChange,
                    placeholder = RecipeMessages.OPTIONAL_PLACEHOLDER,
                    singleLine = false,
                    modifier = Modifier.testTag(RecipeTestTags.RECIPE_DESCRIPTION_FIELD),
                )

                IngredientEditor(state, actions)

                PerServingPreview(state)

                MueTextField(
                    label = RecipeMessages.STEPS_LABEL,
                    value = state.steps,
                    onValueChange = actions.onStepsChange,
                    placeholder = RecipeMessages.OPTIONAL_PLACEHOLDER,
                    errorMessage = state.stepsError,
                    singleLine = false,
                    modifier = Modifier.testTag(FoodTestTags.STEPS_FIELD),
                )
            }
        }

        MueStickyBottomAction(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { size -> actionHeight = with(density) { size.height.toDp() } },
            coversContent = scroll.canScrollForward,
        ) {
            MuePrimaryButton(
                label = state.saveLabel,
                onClick = actions.onSave,
                success = state.justSaved,
                onSuccessFinished = actions.onSaved,
                modifier = Modifier.testTag(RecipeTestTags.SAVE_RECIPE),
            )
        }
    }

    RecipeIngredientPickerSheet(
        state = state.picker,
        onQueryChange = actions.onPickerQueryChange,
        onPick = actions.onPickFood,
        onDismiss = actions.onClosePicker,
    )
}

/**
 * PRD_FOOD 8.3's three moments, as pills that wrap.
 *
 * Three equal segments would each be a third of the row whatever the text size, so `Breakfast`
 * at a doubled font scale would be ellipsised into something unreadable. Pills size to their
 * label and take a second line instead.
 */
@Composable
private fun TypeChooser(state: RecipeEditorUiState, actions: RecipeEditorActions) {
    Column(verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm)) {
        MueText(
            text = RecipeMessages.TYPE_LABEL,
            style = MueTheme.typography.label,
            color = MueTheme.colors.textTertiary,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth().testTag(FoodTestTags.RECIPE_TYPE_PICKER),
            horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.xs),
        ) {
            RecipeType.entries.forEach { type ->
                MuePeriodPill(
                    label = type.label,
                    selected = state.type == type,
                    onClick = { actions.onTypeSelected(type) },
                )
            }
        }
    }
}

/**
 * The ingredients (FR-RECIPE-002), each with the quantity for the **whole recipe** (PRD_FOOD 8.3)
 * and the energy that quantity is worth (PRD_FOOD 11).
 *
 * An empty list is not silently allowed to become a `0 kcal` recipe: PRD_FOOD 15 refuses to save
 * one, `FoodValidation.validateIngredientCount` is what refuses it, and the sentence it returns
 * is drawn here once a save has been attempted.
 */
@Composable
private fun IngredientEditor(state: RecipeEditorUiState, actions: RecipeEditorActions) {
    val colors = MueTheme.colors

    Column(
        modifier = Modifier.fillMaxWidth().testTag(FoodTestTags.INGREDIENT_LIST),
        verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
    ) {
        MueText(
            text = RecipeMessages.INGREDIENTS,
            style = MueTheme.typography.sectionTitle,
            modifier = Modifier.semantics { heading() },
        )
        MueText(
            text = RecipeMessages.INGREDIENTS_HINT,
            style = MueTheme.typography.micro,
            color = colors.textTertiary,
        )

        state.ingredients.forEachIndexed { index, ingredient ->
            IngredientRow(
                state = ingredient,
                index = index,
                onQuantityChange = { raw -> actions.onQuantityChange(index, raw) },
                onRemove = { actions.onRemoveIngredient(index) },
            )
        }

        MueDashedAction(
            label = RecipeMessages.ADD_INGREDIENT,
            onClick = actions.onOpenPicker,
            icon = { MueIcon(ActivityIcons.PLUS, tint = colors.textSecondary, size = 14.dp) },
            modifier = Modifier.fillMaxWidth().testTag(FoodTestTags.ADD_INGREDIENT),
        )

        state.ingredientCountError?.let { message ->
            MueText(
                text = message,
                style = MueTheme.typography.caption,
                color = colors.error,
                modifier = Modifier
                    .semantics { error(message) }
                    .testTag(RecipeTestTags.INGREDIENT_COUNT_ERROR),
            )
        }
    }
}

/**
 * One ingredient row: what it is, how much of it, what it is worth, and how to remove it.
 *
 * The name and the contribution are split by [RecipeSplitRow] rather than by a weighted `Row`,
 * so `≈ 541 kcal` at a doubled font scale drops onto its own line instead of squeezing the name
 * into a ribbon that breaks mid-word.
 *
 * An orphan row — a food PRD_FOOD 21.2 says may not have reached this device — keeps its
 * snapshot name and its quantity and reads `—`. It is still editable and the recipe is still
 * saveable: refusing would lose the ingredient the snapshot exists to preserve.
 */
@Composable
private fun IngredientRow(
    state: RecipeEditorIngredientUiState,
    index: Int,
    onQuantityChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = MueTheme.colors

    MueSurfaceCard(
        modifier = Modifier.testTag(FoodTestTags.ingredient(index)),
        shape = MueTheme.shapes.field,
        contentPadding = PaddingValues(MueTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
    ) {
        val name: @Composable () -> Unit = {
            Column(
                modifier = Modifier.announcedAs(state.description),
                verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.xxs),
            ) {
                MueText(state.name, MueTheme.typography.bodyStrong)
                if (state.isOrphan) {
                    MueText(
                        text = RecipeMessages.ORPHAN_INGREDIENT,
                        style = MueTheme.typography.micro,
                        color = colors.textQuiet,
                    )
                }
            }
        }

        // FR-FOOD-010: with the figures hidden there is no second half to split against.
        val energy = state.energyLabel
        if (energy == null) {
            name()
        } else {
            RecipeSplitRow(
                start = name,
                end = { MueText(energy, MueTheme.typography.bodyStrong) },
            )
        }

        MueDivider()

        MueTextField(
            label = RecipeMessages.QUANTITY_LABEL,
            value = state.quantity,
            onValueChange = onQuantityChange,
            placeholder = "0",
            suffix = state.unitSymbol,
            errorMessage = state.quantityError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.testTag(FoodTestTags.ingredientQuantity(index)),
        )

        MueDashedAction(
            label = state.removeLabel,
            onClick = onRemove,
            icon = { MueIcon(MueIcons.TRASH, tint = colors.textSecondary, size = 14.dp) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(FoodTestTags.removeIngredient(index)),
        )
    }
}

/**
 * FR-RECIPE-003: "le formulaire affiche en direct les valeurs par portion, recalculées à chaque
 * modification".
 *
 * Absent while the form holds no ingredient, because an empty strict sum is a known zero and
 * `≈ 0 kcal` over a blank form would be a number nobody typed. A row whose quantity is still
 * being typed makes the block read `—` rather than totalling half a recipe.
 */
@Composable
private fun PerServingPreview(state: RecipeEditorUiState) {
    val block = state.perServing ?: return
    val colors = MueTheme.colors
    val type = MueTheme.typography

    MueSurfaceCard(
        modifier = Modifier
            .testTag(RecipeTestTags.EDITOR_PER_SERVING)
            .announcedAs(block.description),
        shape = MueTheme.shapes.field,
        contentPadding = PaddingValues(MueTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
    ) {
        RecipeSplitRow(
            start = { MueText(block.title, type.label, color = colors.textTertiary) },
            end = { MueText(block.energyLabel, type.bodyStrong) },
        )
        MueDivider()
        block.macros.forEach { macro ->
            RecipeSplitRow(
                start = { MueText(macro.name, type.micro, color = colors.textTertiary) },
                end = { MueText(macro.value, type.micro) },
            )
        }
    }
}

// region previews

@Preview(name = "Recipe editor — filled", showBackground = true, backgroundColor = 0xFF101012, heightDp = 1400)
@Composable
private fun RecipeEditorPreview() {
    MuePreviewHost(padding = 0) {
        RecipeEditorScreen(
            state = previewRecipeEditorState(),
            actions = RecipeEditorActions(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * A form with nothing in it, after a save has been attempted (PRD_FOOD 15).
 *
 * Every sentence on it is a validator's, and the one under `Add an ingredient` is the reason a
 * recipe can never be saved into a `0 kcal` card.
 */
@Preview(name = "Recipe editor — refused", showBackground = true, backgroundColor = 0xFF101012, heightDp = 1200)
@Composable
private fun RecipeEditorRefusedPreview() {
    MuePreviewHost(padding = 0) {
        RecipeEditorScreen(
            state = refusedRecipeEditorState(),
            actions = RecipeEditorActions(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** The same form on the narrowest phone the app supports and at the largest font scale. */
@Preview(
    name = "Recipe editor — 360 dp · largest font",
    showBackground = true,
    backgroundColor = 0xFF101012,
    widthDp = 360,
    heightDp = 1600,
    fontScale = 2.0f,
)
@Composable
private fun RecipeEditorNarrowPreview() {
    MuePreviewHost(padding = 0) {
        RecipeEditorScreen(
            state = previewRecipeEditorState(),
            actions = RecipeEditorActions(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// endregion
