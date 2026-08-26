package fr.kristenjestin.mue.ui.food.recipes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.model.RecipeType
import fr.kristenjestin.mue.ui.activity.ActivityIcons
import fr.kristenjestin.mue.ui.components.MueContentTopFade
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MuePeriodPill
import fr.kristenjestin.mue.ui.components.MuePreviewHost
import fr.kristenjestin.mue.ui.components.MuePrimaryButton
import fr.kristenjestin.mue.ui.components.MueScreenTitle
import fr.kristenjestin.mue.ui.components.MueSearchField
import fr.kristenjestin.mue.ui.components.MueStickyBottomAction
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.food.FoodIcons
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.food.FoodViewScaffold
import fr.kristenjestin.mue.ui.food.day.announcedAs
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueTheme

/** The prototype's glyph tile, at the touch minimum PRD_FOOD 18 sets for anything tappable. */
private val IconTileSize: Dp = MueMinTouchTarget

/** The type filters FR-RECIPE-005 offers: every type, then the three of `RecipeType`. */
private val TYPE_FILTERS: List<RecipeType?> = listOf(null) + RecipeType.entries

/**
 * The `Recipes` view (PRD_FOOD 11), wired to the saved recipes.
 *
 * [viewModel] defaults to the one `AppContainer.food` builds and is a parameter only so a test
 * can hand its own in — the arrangement `FoodDayRoute` uses.
 */
@Composable
internal fun RecipeListRoute(
    onOpenRecipe: (RecipeId) -> Unit,
    onCreateRecipe: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecipeListViewModel = recipeListViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RecipeListScreen(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onClearQuery = viewModel::onClearQuery,
        onTypeSelected = viewModel::onTypeSelected,
        onToggleFavourites = viewModel::onToggleFavourites,
        onToggleFavourite = viewModel::onToggleFavourite,
        onOpenRecipe = onOpenRecipe,
        onCreateRecipe = onCreateRecipe,
        modifier = modifier,
    )
}

/**
 * The saved recipes: a search line, FR-RECIPE-005's filters, and the cards under them.
 *
 * `New recipe` is pinned to the bottom through the shipped [MueStickyBottomAction], whose band
 * is two parts: a painted ramp that holds no pointer input, so a thumb landing in the fade still
 * scrolls the list, and a solid block below it that is opaque to touch. The list's own bottom
 * padding is the whole measured height of the band, so the last card comes to rest clear of it
 * rather than under it — which is what the 112 dp dead zone on `Log activity` came from.
 *
 * State is handed in whole, so every test drives what reaches the screen rather than how a
 * ViewModel got there.
 */
@Composable
internal fun RecipeListScreen(
    state: RecipeListUiState,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onTypeSelected: (RecipeType?) -> Unit,
    onToggleFavourites: () -> Unit,
    onToggleFavourite: (RecipeId, Boolean) -> Unit,
    onOpenRecipe: (RecipeId) -> Unit,
    onCreateRecipe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MueTheme.spacing
    val density = LocalDensity.current
    val list = rememberLazyListState()
    var actionHeight by remember { mutableStateOf(0.dp) }

    Box(modifier = modifier.fillMaxSize().testTag(FoodTestTags.RECIPES)) {
        FoodViewScaffold(topFade = MueContentTopFade) {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().testTag(FoodTestTags.RECIPE_LIST),
                contentPadding = PaddingValues(
                    top = MueContentTopFade,
                    bottom = actionHeight + spacing.lg,
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                item(key = "header") {
                    MueScreenTitle(
                        title = RecipeMessages.LIST_TITLE,
                        eyebrow = RecipeMessages.LIST_EYEBROW,
                        modifier = Modifier.padding(bottom = spacing.sm),
                    )
                }

                item(key = "search") {
                    MueSearchField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        placeholder = RecipeMessages.SEARCH_PLACEHOLDER,
                        label = RecipeMessages.SEARCH_LABEL,
                        leadingIcon = {
                            MueIcon(
                                iconName = ActivityIcons.SEARCH,
                                tint = MueTheme.colors.textTertiary,
                                size = 18.dp,
                            )
                        },
                        onClear = onClearQuery,
                        clearContentDescription = RecipeMessages.CLEAR_SEARCH,
                        modifier = Modifier.testTag(FoodTestTags.RECIPE_SEARCH),
                    )
                }

                item(key = "filters") {
                    RecipeFilters(
                        state = state,
                        onTypeSelected = onTypeSelected,
                        onToggleFavourites = onToggleFavourites,
                    )
                }

                if (state.showsInvitation || state.showsNoMatch) {
                    item(key = "empty") {
                        RecipeListEmptyState(state)
                    }
                }

                items(items = state.recipes, key = { it.id.value }) { card ->
                    RecipeCard(
                        state = card,
                        onClick = { onOpenRecipe(card.id) },
                        onToggleFavourite = { onToggleFavourite(card.id, !card.isFavourite) },
                    )
                }
            }
        }

        MueStickyBottomAction(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { size -> actionHeight = with(density) { size.height.toDp() } },
            coversContent = list.canScrollForward,
        ) {
            MuePrimaryButton(
                label = RecipeMessages.CREATE_RECIPE,
                onClick = onCreateRecipe,
                modifier = Modifier.testTag(FoodTestTags.CREATE_RECIPE),
            )
        }
    }
}

/**
 * FR-RECIPE-005's filters: one type at a time, and favourites on or off.
 *
 * A flow row of pills rather than a segmented control. Four equal segments would each be a
 * quarter of the row whatever the text size, so `Breakfast` at a doubled font scale would be
 * ellipsised into `Break…` — a filter nobody can read. Pills size to their label and wrap onto a
 * second line instead, which costs a line and loses no word.
 */
@Composable
private fun RecipeFilters(
    state: RecipeListUiState,
    onTypeSelected: (RecipeType?) -> Unit,
    onToggleFavourites: () -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().testTag(RecipeTestTags.TYPE_FILTER),
        horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.xs),
    ) {
        TYPE_FILTERS.forEach { type ->
            MuePeriodPill(
                label = RecipeMessages.typeLabel(type),
                selected = state.type == type,
                onClick = { onTypeSelected(type) },
                modifier = Modifier.testTag(RecipeTestTags.typeFilter(type?.id ?: TYPE_ALL_ID)),
            )
        }

        FavouritesFilter(selected = state.favouritesOnly, onClick = onToggleFavourites)
    }
}

/**
 * The favourites filter, which is a toggle and not a fifth type.
 *
 * `Role.Checkbox` rather than the `Role.Tab` of the pills beside it: it is on or off
 * independently of which type is chosen, and a screen reader that called it a tab would imply
 * that choosing it deselects one. The star repeats the state so nothing is carried by colour
 * alone (PRD_FOOD 18).
 */
@Composable
private fun FavouritesFilter(selected: Boolean, onClick: () -> Unit) {
    val colors = MueTheme.colors
    Box(
        modifier = Modifier
            .heightIn(min = MueMinTouchTarget)
            .toggleable(
                value = selected,
                role = Role.Checkbox,
                onValueChange = { onClick() },
            )
            .semantics {
                contentDescription = if (selected) {
                    RecipeMessages.SHOW_EVERY_RECIPE
                } else {
                    RecipeMessages.SHOW_FAVOURITES_ONLY
                }
            }
            .testTag(RecipeTestTags.FAVOURITES_FILTER),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(FilterPillHeight)
                .clip(MueTheme.shapes.pill)
                .background(if (selected) colors.accent else colors.surfaceStrong)
                .padding(horizontal = MueTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.xs),
        ) {
            MueIcon(
                iconName = FoodIcons.STAR,
                tint = if (selected) colors.onAccent else colors.textTertiary,
                size = 14.dp,
            )
            MueText(
                text = RecipeMessages.FAVOURITES,
                style = MueTheme.typography.chip,
                color = if (selected) colors.onAccent else colors.textTertiary,
                maxLines = 1,
            )
        }
    }
}

/**
 * PRD_FOOD 17's two empty lists, which are two different facts.
 *
 * A catalogue nobody has written in yet gets the invitation and the create button beside it — no
 * fake recipe, and nothing that reads as an error. A filter that matches nothing says exactly
 * that instead, because telling someone with forty recipes that they have none would be a lie.
 */
@Composable
private fun RecipeListEmptyState(state: RecipeListUiState) {
    val title = if (state.showsInvitation) {
        RecipeMessages.NO_RECIPES_TITLE
    } else {
        RecipeMessages.NO_MATCH_TITLE
    }
    val body = if (state.showsInvitation) {
        RecipeMessages.NO_RECIPES_BODY
    } else {
        RecipeMessages.NO_MATCH_BODY
    }

    MueSurfaceCard(
        modifier = Modifier
            .padding(top = MueTheme.spacing.md)
            .testTag(RecipeTestTags.EMPTY_STATE),
        verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.xs),
    ) {
        MueText(title, MueTheme.typography.sectionTitle)
        MueText(body, MueTheme.typography.body, color = MueTheme.colors.textSecondary)
    }
}

/**
 * One saved recipe.
 *
 * The name is never truncated: PRD_FOOD 15 lets it run to 80 characters and a `maxLines = 1`
 * would ellipsise it into something that still satisfies every assertion — a semantics string is
 * the whole name however the glyphs fall — while reading wrong on the phone. A long name makes a
 * taller card, which is the honest outcome.
 *
 * The two blocks either side of it are a **fixed 48 dp** each, so neither grows with the text
 * size and the name keeps the same share of the row at every scale. That is why this one is a
 * plain `Row`: what broke the journal card was an unweighted block of *text* taking the row for
 * itself, which cannot happen to a glyph tile.
 */
@Composable
private fun RecipeCard(
    state: RecipeCardUiState,
    onClick: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    val colors = MueTheme.colors
    val spacing = MueTheme.spacing

    MueSurfaceCard(
        modifier = Modifier.testTag(FoodTestTags.recipeCard(state.id.value)),
        shape = MueTheme.shapes.field,
        contentPadding = PaddingValues(spacing.md),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(IconTileSize)
                    .clip(MueTheme.shapes.field)
                    .background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                // Decorative: the name right beside it says what this is.
                MueIcon(iconName = state.iconName, tint = colors.onAccentSoft, size = 18.dp)
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = spacing.md)
                    .announcedAs(state.description),
                verticalArrangement = Arrangement.spacedBy(spacing.xxs),
            ) {
                MueText(state.name, MueTheme.typography.bodyStrong)
                RecipeFactRow(facts = state.facts)
            }

            FavouriteStar(
                isFavourite = state.isFavourite,
                label = state.favouriteLabel,
                testTag = FoodTestTags.favouriteRecipe(state.id.value),
                onClick = onToggleFavourite,
            )
        }
    }
}

/** FR-RECIPE-005: a recipe is a favourite or it is not, at the 48 dp PRD_FOOD 18 requires. */
@Composable
internal fun FavouriteStar(
    isFavourite: Boolean,
    label: String,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MueTheme.colors
    Box(
        modifier = modifier
            .size(MueMinTouchTarget)
            .clip(MueTheme.shapes.pill)
            .toggleable(value = isFavourite, role = Role.Checkbox, onValueChange = { onClick() })
            .semantics { contentDescription = label }
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        MueIcon(
            iconName = FoodIcons.STAR,
            tint = if (isFavourite) colors.accent else colors.textQuiet,
            size = 18.dp,
        )
    }
}

/** The visible pill, kept low like `MuePeriodPill`'s while the target around it is 48 dp. */
private val FilterPillHeight: Dp = 40.dp

/** The id the `All` filter answers to, which is not a `RecipeType` and never will be. */
private const val TYPE_ALL_ID = "all"

// region previews

@Preview(name = "Recipes — populated", showBackground = true, backgroundColor = 0xFF101012, heightDp = 900)
@Composable
private fun RecipeListPreview() {
    MuePreviewHost(padding = 0) {
        RecipeListScreen(
            state = previewRecipeListState(),
            onQueryChange = {},
            onClearQuery = {},
            onTypeSelected = {},
            onToggleFavourites = {},
            onToggleFavourite = { _, _ -> },
            onOpenRecipe = {},
            onCreateRecipe = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The same list on the narrowest phone the app supports and at the largest font scale.
 *
 * What to look for: four type filters and the favourites toggle wrapped onto as many lines as
 * they need with no label cut, every recipe name wrapped at a space, and the facts under it
 * whole rather than broken across a bullet.
 */
@Preview(
    name = "Recipes — 360 dp · largest font",
    showBackground = true,
    backgroundColor = 0xFF101012,
    widthDp = 360,
    heightDp = 900,
    fontScale = 2.0f,
)
@Composable
private fun RecipeListNarrowPreview() {
    MuePreviewHost(padding = 0) {
        RecipeListScreen(
            state = previewRecipeListState(),
            onQueryChange = {},
            onClearQuery = {},
            onTypeSelected = {},
            onToggleFavourites = {},
            onToggleFavourite = { _, _ -> },
            onOpenRecipe = {},
            onCreateRecipe = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Preview(name = "Recipes — nothing saved", showBackground = true, backgroundColor = 0xFF101012, heightDp = 900)
@Composable
private fun RecipeListEmptyPreview() {
    MuePreviewHost(padding = 0) {
        RecipeListScreen(
            state = emptyRecipeListState(),
            onQueryChange = {},
            onClearQuery = {},
            onTypeSelected = {},
            onToggleFavourites = {},
            onToggleFavourite = { _, _ -> },
            onOpenRecipe = {},
            onCreateRecipe = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// endregion
