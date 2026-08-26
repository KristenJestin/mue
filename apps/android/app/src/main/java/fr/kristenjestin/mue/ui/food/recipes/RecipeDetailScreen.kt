package fr.kristenjestin.mue.ui.food.recipes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.ui.components.MueDivider
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MueIcons
import fr.kristenjestin.mue.ui.components.MuePreviewHost
import fr.kristenjestin.mue.ui.components.MuePrimaryButton
import fr.kristenjestin.mue.ui.components.MueSecondaryButton
import fr.kristenjestin.mue.ui.components.MueStickyActionRamp
import fr.kristenjestin.mue.ui.components.MueStickyBottomAction
import fr.kristenjestin.mue.ui.components.MueSubScreenScaffold
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.food.day.announcedAs
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueTheme

private val BackIconSize: Dp = 18.dp

/** How far a disabled servings step fades — visible, plainly inert, as on the `Day` header. */
private const val DisabledStepAlpha = 0.2f

/**
 * The card of one recipe (PRD_FOOD 11), wired to the saved recipes and the catalogue.
 *
 * [onDeleted] is called once the deletion has been acknowledged, which is what takes the card
 * off the stack — never before, because the moments the deletion freed are shown on this screen
 * and nowhere else.
 */
@Composable
internal fun RecipeDetailRoute(
    recipeId: RecipeId,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecipeDetailViewModel = recipeDetailViewModel(),
) {
    LaunchedEffect(recipeId) { viewModel.start(recipeId) }

    val state by viewModel.uiState.collectAsStateWithLifecycle()

    /*
     * A deletion that freed no proposal has nothing to report, so the card leaves at once. One
     * that freed some stays until `Done`, because PRD_FOOD 17 requires the freed moment to be
     * signalled and this is the only screen that knows which they were.
     */
    LaunchedEffect(state.isDeleted, state.freedPlans) {
        if (state.isDeleted && state.freedPlans.isEmpty()) onDeleted()
    }

    RecipeDetailScreen(
        state = state,
        onBack = onBack,
        onEdit = onEdit,
        onToggleFavourite = viewModel::onToggleFavourite,
        onFewerServings = viewModel::onFewerServings,
        onMoreServings = viewModel::onMoreServings,
        onRequestDelete = viewModel::onRequestDelete,
        onCancelDelete = viewModel::onCancelDelete,
        onConfirmDelete = viewModel::onConfirmDelete,
        onDeletionAcknowledged = onDeleted,
        modifier = modifier,
    )
}

/**
 * The recipe as it reads: its facts, the servings on screen, what a serving is worth, the
 * ingredients rescaled to that count, and the steps (PRD_FOOD 11, FR-RECIPE-003 and 004).
 *
 * Every figure arrives already drawn from [RecipeDetailUiState], which computed it through
 * `NutritionMath` and rendered it through `FoodLabels`. Nothing on this screen adds anything up,
 * and nothing here can turn a `—` into a `0`.
 */
@Composable
internal fun RecipeDetailScreen(
    state: RecipeDetailUiState,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onToggleFavourite: () -> Unit,
    onFewerServings: () -> Unit,
    onMoreServings: () -> Unit,
    onRequestDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onDeletionAcknowledged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MueTheme.spacing
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    var actionHeight by remember { mutableStateOf(0.dp) }

    Box(modifier = modifier.fillMaxSize().testTag(FoodTestTags.RECIPE_DETAIL)) {
        MueSubScreenScaffold(
            title = state.screenTitle,
            onNavigateBack = onBack,
            navigationIcon = {
                MueIcon(
                    iconName = MueIcons.ARROW_LEFT,
                    tint = MueTheme.colors.textSecondary,
                    size = BackIconSize,
                )
            },
            navigationContentDescription = RecipeMessages.BACK,
            trailing = {
                if (!state.isMissing) {
                    FavouriteStar(
                        isFavourite = state.isFavourite,
                        label = state.favouriteLabel,
                        testTag = FoodTestTags.favouriteRecipe(state.recipeId?.value.orEmpty()),
                        onClick = onToggleFavourite,
                    )
                }
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // The pinned band's clearance, split where the band itself is split: the
                    // viewport ends above the solid block, and the ramp draws over live content.
                    .padding(bottom = (actionHeight - MueStickyActionRamp).coerceAtLeast(0.dp))
                    .verticalScroll(scroll)
                    .padding(bottom = MueStickyActionRamp),
                verticalArrangement = Arrangement.spacedBy(spacing.lg),
            ) {
                if (state.isMissing) {
                    MissingRecipe()
                } else {
                    RecipeFacts(state)
                    ServingsChooser(state, onFewerServings, onMoreServings)
                    NutritionBlocks(state)
                    IngredientList(state)
                    StepList(state)
                }
            }
        }

        if (!state.isMissing) {
            MueStickyBottomAction(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { size -> actionHeight = with(density) { size.height.toDp() } },
                coversContent = scroll.canScrollForward,
            ) {
                MuePrimaryButton(
                    label = RecipeMessages.EDIT_RECIPE,
                    onClick = onEdit,
                    modifier = Modifier.testTag(FoodTestTags.EDIT_RECIPE),
                )
                MueSecondaryButton(
                    label = RecipeMessages.DELETE_RECIPE,
                    onClick = onRequestDelete,
                    contentColor = MueTheme.colors.error,
                    modifier = Modifier.testTag(RecipeTestTags.DELETE_RECIPE),
                )
            }
        }
    }

    RecipeDeletionDialogs(
        state = state,
        onCancel = onCancelDelete,
        onConfirm = onConfirmDelete,
        onAcknowledge = onDeletionAcknowledged,
    )
}

/** PRD_FOOD 17: the recipe was deleted while its card was open, or the id is stale. */
@Composable
private fun MissingRecipe() {
    MueSurfaceCard(modifier = Modifier.padding(top = MueTheme.spacing.xl)) {
        MueText(RecipeMessages.MISSING_RECIPE, MueTheme.typography.body)
    }
}

/** The recipe's own facts: what it is for, how many it serves, how long it takes. */
@Composable
private fun RecipeFacts(state: RecipeDetailUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MueTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.xs),
    ) {
        RecipeFactRow(facts = state.facts)
        state.description?.let { description ->
            MueText(description, MueTheme.typography.body, color = MueTheme.colors.textSecondary)
        }
    }
}

/**
 * FR-RECIPE-004: "la fiche permet de faire varier le nombre de portions affichées ; les
 * quantités d'ingrédients suivent proportionnellement".
 *
 * The count changes what is *shown* and never what is stored. Both ends of the range are
 * PRD_FOOD 15's, asked of `FoodValidation` in [RecipeDetailUiState.stepped] rather than restated
 * here, and a step that would leave the range simply disables its control.
 */
@Composable
private fun ServingsChooser(
    state: RecipeDetailUiState,
    onFewer: () -> Unit,
    onMore: () -> Unit,
) {
    MueSurfaceCard(shape = MueTheme.shapes.field, contentPadding = PaddingValues(MueTheme.spacing.md)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.md),
        ) {
            StepButton(
                iconName = MueIcons.CHEVRON_RIGHT,
                label = RecipeMessages.FEWER_SERVINGS,
                enabled = state.canRemoveServing,
                mirrored = true,
                testTag = RecipeTestTags.FEWER_SERVINGS,
                onClick = onFewer,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .announcedAs("${RecipeMessages.SERVINGS}, ${state.servingsLabel}"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MueText(
                    text = RecipeMessages.SERVINGS,
                    style = MueTheme.typography.label,
                    color = MueTheme.colors.textTertiary,
                )
                MueText(
                    text = state.servingsLabel,
                    style = MueTheme.typography.bodyStrong,
                    modifier = Modifier.testTag(FoodTestTags.RECIPE_SERVINGS),
                )
            }

            StepButton(
                iconName = MueIcons.CHEVRON_RIGHT,
                label = RecipeMessages.MORE_SERVINGS,
                enabled = state.canAddServing,
                mirrored = false,
                testTag = RecipeTestTags.MORE_SERVINGS,
                onClick = onMore,
            )
        }
    }
}

/**
 * One step of the servings counter.
 *
 * `chevron-left` is the one Lucide glyph the app has never imported and a drawable is not this
 * screen's to add, so the right-hand chevron is mirrored — the same answer `Day` gave, and a
 * stroked chevron is symmetrical about its own axis so the reflection is the missing vector.
 */
@Composable
private fun StepButton(
    iconName: String,
    label: String,
    enabled: Boolean,
    mirrored: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    val colors = MueTheme.colors
    Box(
        modifier = Modifier
            .size(MueMinTouchTarget)
            .clip(MueTheme.shapes.field)
            .background(colors.surfaceStrong)
            .alpha(if (enabled) 1f else DisabledStepAlpha)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        MueIcon(
            iconName = iconName,
            contentDescription = label,
            tint = colors.textSecondary,
            size = 18.dp,
            // A stroked chevron is symmetrical about its own axis, so the reflection is the
            // very shape the `chevron-left` nobody imported would have drawn.
            modifier = if (mirrored) Modifier.graphicsLayer { scaleX = -1f } else Modifier,
        )
    }
}

/**
 * PRD_FOOD 13.1's two readings, side by side: what one serving is worth, and what the servings
 * currently on screen are worth.
 *
 * Both are null exactly while the recipe holds no ingredient. An empty strict sum is a **known**
 * zero, so a block built from one would print `≈ 0 kcal` over a recipe nobody has filled in —
 * and PRD_FOOD 15 refuses to save such a recipe in the first place.
 */
@Composable
private fun NutritionBlocks(state: RecipeDetailUiState) {
    val perServing = state.perServing
    val forServings = state.forServings
    if (perServing == null || forServings == null) return

    Column(verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.md)) {
        NutritionCard(perServing, FoodTestTags.RECIPE_PER_SERVING)
        NutritionCard(forServings, RecipeTestTags.RECIPE_TOTAL)

        if (state.hasOrphanIngredient) {
            MueText(
                text = RecipeMessages.ORPHAN_INGREDIENT,
                style = MueTheme.typography.micro,
                color = MueTheme.colors.textTertiary,
            )
        }
    }
}

/**
 * One nutrition block.
 *
 * The heading announces the whole block and the figures beside it are silenced, so a screen
 * reader hears one sentence rather than nine fragments (PRD_FOOD 18). The drawn strings stay in
 * the semantics tree, which is the only way a test can prove that a `—` has not become a `0` on
 * its way to the glass.
 */
@Composable
private fun NutritionCard(state: RecipeNutritionUiState, testTag: String) {
    val colors = MueTheme.colors
    val type = MueTheme.typography

    MueSurfaceCard(
        modifier = Modifier.testTag(testTag).announcedAs(state.description),
        shape = MueTheme.shapes.field,
        contentPadding = PaddingValues(MueTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
    ) {
        RecipeSplitRow(
            start = {
                // No `heading()`: the card is announced whole, so its parts are not addressed.
                MueText(text = state.title, style = type.label, color = colors.textTertiary)
            },
            end = { MueText(state.energyLabel, type.bodyStrong) },
        )

        MueDivider()

        state.macros.forEach { macro ->
            RecipeSplitRow(
                start = { MueText(macro.name, type.micro, color = colors.textTertiary) },
                end = { MueText(macro.value, type.micro) },
            )
        }
    }
}

/**
 * The ingredients, with their quantities rescaled to the servings on screen (FR-RECIPE-004).
 *
 * An orphan row — a food PRD_FOOD 21.2 says may not have reached this device — is drawn from its
 * snapshot with a quiet note under it and `—` where its energy would be. It is not an error and
 * it is not hidden: hiding it would make the recipe look complete while its total is unknown.
 */
@Composable
private fun IngredientList(state: RecipeDetailUiState) {
    val colors = MueTheme.colors

    Column(
        modifier = Modifier.fillMaxWidth().testTag(RecipeTestTags.RECIPE_INGREDIENTS),
        verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
    ) {
        SectionHeading(RecipeMessages.INGREDIENTS)

        if (!state.hasIngredients) {
            MueText(
                text = RecipeMessages.NO_INGREDIENTS,
                style = MueTheme.typography.body,
                color = colors.textTertiary,
            )
            return@Column
        }

        state.ingredients.forEach { ingredient ->
            MueSurfaceCard(
                modifier = Modifier
                    .testTag(RecipeTestTags.detailIngredient(ingredient.id))
                    .announcedAs(ingredient.description),
                shape = MueTheme.shapes.field,
                contentPadding = PaddingValues(MueTheme.spacing.md),
            ) {
                val facts: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.xxs)) {
                        MueText(ingredient.name, MueTheme.typography.bodyStrong)
                        RecipeFactRow(facts = listOf(ingredient.quantityLabel))
                        if (ingredient.isOrphan) {
                            MueText(
                                text = RecipeMessages.ORPHAN_INGREDIENT,
                                style = MueTheme.typography.micro,
                                color = colors.textQuiet,
                                modifier = Modifier.testTag(
                                    RecipeTestTags.orphanIngredient(ingredient.id),
                                ),
                            )
                        }
                    }
                }

                // FR-FOOD-010: with the figures hidden there is no second half to split against,
                // so the row is simply the name and its quantity.
                val energy = ingredient.energyLabel
                if (energy == null) {
                    facts()
                } else {
                    RecipeSplitRow(
                        start = facts,
                        end = { MueText(energy, MueTheme.typography.bodyStrong) },
                    )
                }
            }
        }
    }
}

/** PRD_FOOD 15: nought to thirty lines, numbered as they were written. */
@Composable
private fun StepList(state: RecipeDetailUiState) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(RecipeTestTags.RECIPE_STEPS),
        verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
    ) {
        SectionHeading(RecipeMessages.STEPS)

        if (!state.hasSteps) {
            MueText(
                text = RecipeMessages.NO_STEPS,
                style = MueTheme.typography.body,
                color = MueTheme.colors.textTertiary,
            )
            return@Column
        }

        state.steps.forEachIndexed { index, step ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.md),
            ) {
                MueText(
                    text = "${index + 1}",
                    style = MueTheme.typography.bodyStrong,
                    color = MueTheme.colors.accent,
                )
                MueText(step, MueTheme.typography.body, color = MueTheme.colors.textSecondary)
            }
        }
    }
}

@Composable
private fun SectionHeading(title: String) {
    MueText(
        text = title,
        style = MueTheme.typography.sectionTitle,
        modifier = Modifier.semantics { heading() },
    )
}

// region previews

@Preview(name = "Recipe — every ingredient known", showBackground = true, backgroundColor = 0xFF101012, heightDp = 1100)
@Composable
private fun RecipeDetailPreview() {
    MuePreviewHost(padding = 0) {
        RecipeDetailScreen(
            state = previewRecipeDetailState(),
            onBack = {},
            onEdit = {},
            onToggleFavourite = {},
            onFewerServings = {},
            onMoreServings = {},
            onRequestDelete = {},
            onCancelDelete = {},
            onConfirmDelete = {},
            onDeletionAcknowledged = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The picture to look at when PRD_FOOD 13.1 is in question (PRD_FOOD 21.2).
 *
 * One ingredient references a food this device has never received. Its row is drawn from the
 * snapshot, its energy reads `—`, and **every figure of the recipe reads `—` as well** — because
 * a strict sum is unknown as soon as one contribution is. Not one of them reads `≈ 0`.
 */
@Preview(name = "Recipe — an orphan ingredient", showBackground = true, backgroundColor = 0xFF101012, heightDp = 1100)
@Composable
private fun RecipeDetailOrphanPreview() {
    MuePreviewHost(padding = 0) {
        RecipeDetailScreen(
            state = orphanRecipeDetailState(),
            onBack = {},
            onEdit = {},
            onToggleFavourite = {},
            onFewerServings = {},
            onMoreServings = {},
            onRequestDelete = {},
            onCancelDelete = {},
            onConfirmDelete = {},
            onDeletionAcknowledged = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * A recipe with no ingredient at all, which shows **no total whatsoever**.
 *
 * Held beside the preview above it is PRD_FOOD 13.1 in two pictures: an unknown total reads `—`,
 * and a recipe with nothing to total shows nothing. Neither of them shows a `0`.
 */
@Preview(name = "Recipe — nothing to total", showBackground = true, backgroundColor = 0xFF101012, heightDp = 900)
@Composable
private fun RecipeDetailEmptyPreview() {
    MuePreviewHost(padding = 0) {
        RecipeDetailScreen(
            state = emptyRecipeDetailState(),
            onBack = {},
            onEdit = {},
            onToggleFavourite = {},
            onFewerServings = {},
            onMoreServings = {},
            onRequestDelete = {},
            onCancelDelete = {},
            onConfirmDelete = {},
            onDeletionAcknowledged = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * FR-FOOD-010: the same recipe with the figures hidden.
 *
 * Held beside the preview above it, the pair says what the preference does and what it does not:
 * every value is gone, and the ingredients, the quantities, the steps and the servings counter
 * are exactly where they were.
 */
@Preview(name = "Recipe — energy hidden", showBackground = true, backgroundColor = 0xFF101012, heightDp = 1000)
@Composable
private fun RecipeDetailHiddenEnergyPreview() {
    MuePreviewHost(padding = 0) {
        RecipeDetailScreen(
            state = hiddenEnergyRecipeDetailState(),
            onBack = {},
            onEdit = {},
            onToggleFavourite = {},
            onFewerServings = {},
            onMoreServings = {},
            onRequestDelete = {},
            onCancelDelete = {},
            onConfirmDelete = {},
            onDeletionAcknowledged = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** The same card on the narrowest phone the app supports and at the largest font scale. */
@Preview(
    name = "Recipe — 360 dp · largest font",
    showBackground = true,
    backgroundColor = 0xFF101012,
    widthDp = 360,
    heightDp = 1200,
    fontScale = 2.0f,
)
@Composable
private fun RecipeDetailNarrowPreview() {
    MuePreviewHost(padding = 0) {
        RecipeDetailScreen(
            state = previewRecipeDetailState(),
            onBack = {},
            onEdit = {},
            onToggleFavourite = {},
            onFewerServings = {},
            onMoreServings = {},
            onRequestDelete = {},
            onCancelDelete = {},
            onConfirmDelete = {},
            onDeletionAcknowledged = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// endregion
