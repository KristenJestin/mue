package fr.kristenjestin.mue.ui.food.add

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.ui.activity.ActivityIcons
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MueIcons
import fr.kristenjestin.mue.ui.components.MuePreviewHost
import fr.kristenjestin.mue.ui.components.MueSearchField
import fr.kristenjestin.mue.ui.components.MueSecondaryButton
import fr.kristenjestin.mue.ui.components.MueSubScreenScaffold
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.food.day.announcedAs
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueTheme

private val BackIconSize: Dp = 18.dp

/** The glyph tile in front of a row, the 44 dp every picker row in this module uses. */
private val ResultTileSize: Dp = 44.dp

/**
 * FR-FOOD-004: the recipe a line is built from, chosen **without leaving the sheet**.
 *
 * This is the screen `Use a recipe` was missing. It used to answer with `stack.select(Recipes)`,
 * which is a change of *view*: the whole Food tab was replaced, the sheet closed, the switcher
 * and the bottom bar came back, and somebody three taps into logging dinner was left on a
 * browsing screen with no way back to what they were writing — "on dirait qu'on tombe sur la
 * mauvaise page", which is exactly what it was.
 *
 * It is pushed **over** the sheet, like [FoodPickerRoute], and hands what it chose to
 * [FoodAddViewModel] — which is where the recipe is going. Back returns to `Add food` with the
 * draft untouched, because pushing a sheet over another never leaves it.
 *
 * [onCreateRecipe] is PRD_FOOD 17's invitation rather than a dead end: a person with no recipes
 * can write one from here, and the editor pops back to this list when it is saved.
 */
@Composable
internal fun RecipePickerRoute(
    onPicked: (RecipeId) -> Unit,
    onCreateRecipe: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecipePickerViewModel = recipePickerViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    RecipePickerScreen(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onClearQuery = viewModel::onClearQuery,
        onPicked = onPicked,
        onCreateRecipe = onCreateRecipe,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * The picker as it is drawn: one search bar, one list, and the way out of an empty one.
 *
 * Deliberately the same screen as `Choose a food` less the source filter — a recipe has no
 * provenance — so that the two ways in look like two ways into the same sheet rather than two
 * different places. No name is truncated here either: PRD_FOOD 15 lets a recipe name run to 80
 * characters and a one-line row would cut it in the middle.
 */
@Composable
internal fun RecipePickerScreen(
    state: RecipePickerUiState,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onPicked: (RecipeId) -> Unit,
    onCreateRecipe: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MueTheme.spacing

    MueSubScreenScaffold(
        title = FoodAddMessages.RECIPE_PICKER_TITLE,
        onNavigateBack = onBack,
        navigationIcon = {
            MueIcon(
                iconName = MueIcons.ARROW_LEFT,
                tint = MueTheme.colors.textSecondary,
                size = BackIconSize,
            )
        },
        modifier = modifier.testTag(FoodTestTags.RECIPE_PICKER),
    ) {
        MueSearchField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .padding(top = spacing.md)
                .testTag(FoodTestTags.RECIPE_SEARCH),
            placeholder = FoodAddMessages.RECIPE_SEARCH_PLACEHOLDER,
            label = FoodAddMessages.RECIPE_SEARCH_LABEL,
            leadingIcon = {
                MueIcon(
                    iconName = ActivityIcons.SEARCH,
                    tint = MueTheme.colors.textTertiary,
                    size = 16.dp,
                )
            },
            onClear = onClearQuery,
            clearContentDescription = FoodAddMessages.CLEAR_SEARCH,
        )

        MueText(
            text = state.sectionTitle,
            style = MueTheme.typography.label,
            color = MueTheme.colors.textTertiary,
            modifier = Modifier.padding(top = spacing.lg, bottom = spacing.sm),
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag(FoodTestTags.RECIPE_LIST),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
            contentPadding = PaddingValues(bottom = spacing.xxxl),
        ) {
            items(items = state.results, key = { it.id }) { row ->
                RecipeResultRow(row = row, onClick = { onPicked(RecipeId(row.id)) })
            }

            state.emptyMessage?.let { message ->
                item(key = "empty") {
                    MueText(
                        text = message,
                        style = MueTheme.typography.body,
                        color = MueTheme.colors.textTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xl),
                    )
                }
            }

            /*
             * PRD_FOOD 17: a catalogue nobody has written in yet gets the invitation, not an
             * apology. Only when there is genuinely nothing saved — a search that matched none of
             * several recipes wants another word, and a `New recipe` button there would send
             * somebody to write the dish they already have.
             */
            if (!state.hasAnyRecipe) {
                item(key = "create") {
                    MueSecondaryButton(
                        label = FoodAddMessages.CREATE_RECIPE,
                        onClick = onCreateRecipe,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(FoodTestTags.CREATE_RECIPE),
                    )
                }
            }
        }
    }
}

/**
 * One recipe in the list: what it is, and the three facts that fit under the name.
 *
 * The whole row is announced once, through [announcedAs], as every repeated row in this module
 * is — the name and the facts are one thing to choose, not three fragments to hear.
 */
@Composable
private fun RecipeResultRow(row: RecipePickerRowUiState, onClick: () -> Unit) {
    val colors = MueTheme.colors
    val type = MueTheme.typography
    val spacing = MueTheme.spacing

    MueSurfaceCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(FoodTestTags.recipeCard(row.id)),
        shape = MueTheme.shapes.field,
        contentPadding = PaddingValues(spacing.md),
        onClick = onClick,
        onClickLabel = row.name,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MueMinTouchTarget)
                .announcedAs(row.description),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(ResultTileSize)
                    .clip(MueTheme.shapes.field)
                    .background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                MueIcon(iconName = row.iconName, tint = colors.onAccentSoft, size = 18.dp)
            }

            /*
             * A plain `Row` with one weighted text block, which is safe here for the reason the
             * `Recipes` card gives: the only other child is a fixed-size glyph tile, so nothing
             * competes with the name for width at a large font scale. There is no second text
             * block to crush it — a recipe row carries no figure (PRD_FOOD 8.3).
             */
            Column(
                modifier = Modifier.weight(1f).padding(start = spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.xxs),
            ) {
                MueText(row.name, type.bodyStrong)
                MueText(row.meta, type.micro, color = colors.textTertiary)
            }
        }
    }
}

// region previews

@Preview(name = "Recipe picker — saved recipes", showBackground = true, backgroundColor = 0xFF101012, heightDp = 800)
@Composable
private fun RecipePickerPreview() {
    MuePreviewHost(padding = 0) {
        RecipePickerScreen(
            state = previewRecipePickerState(),
            onQueryChange = {},
            onClearQuery = {},
            onPicked = {},
            onCreateRecipe = {},
            onBack = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The same list on the narrowest phone at the largest font scale.
 *
 * What to look for: the whole name, wrapped rather than cut, and its facts under it.
 */
@Preview(
    name = "Recipe picker — 360 dp · largest font",
    showBackground = true,
    backgroundColor = 0xFF101012,
    widthDp = 360,
    heightDp = 900,
    fontScale = 2.0f,
)
@Composable
private fun RecipePickerNarrowPreview() {
    MuePreviewHost(padding = 0) {
        RecipePickerScreen(
            state = previewRecipePickerState(),
            onQueryChange = {},
            onClearQuery = {},
            onPicked = {},
            onCreateRecipe = {},
            onBack = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** PRD_FOOD 17: nothing saved yet is an invitation, and never a fake recipe. */
@Preview(name = "Recipe picker — nothing saved", showBackground = true, backgroundColor = 0xFF101012, heightDp = 600)
@Composable
private fun RecipePickerEmptyPreview() {
    MuePreviewHost(padding = 0) {
        RecipePickerScreen(
            state = previewEmptyRecipePickerState(),
            onQueryChange = {},
            onClearQuery = {},
            onPicked = {},
            onCreateRecipe = {},
            onBack = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// endregion
