package fr.kristenjestin.mue.ui.food.catalogue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.ui.activity.ActivityIcons
import fr.kristenjestin.mue.ui.components.MueContentTopFade
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MuePeriodPill
import fr.kristenjestin.mue.ui.components.MuePreviewHost
import fr.kristenjestin.mue.ui.components.MuePrimaryButton
import fr.kristenjestin.mue.ui.components.MueScreenTitle
import fr.kristenjestin.mue.ui.components.MueSearchField
import fr.kristenjestin.mue.ui.components.MueStickyActionRamp
import fr.kristenjestin.mue.ui.components.MueStickyBottomAction
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.food.FoodViewScaffold
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueTheme

/** PRD_FOOD 9.4's filter, as the row of pills draws it: every source, then one at a time. */
private val SOURCE_FILTERS: List<FoodSource?> = listOf(null) + FoodSource.entries

/**
 * The `Foods` view (PRD_FOOD 7 and 9), wired to the catalogue.
 *
 * [viewModel] defaults to the one `AppContainer.food` builds and is a parameter only so a test
 * can hand its own in, exactly as `FoodDayRoute` does.
 */
@Composable
internal fun FoodsRoute(
    onOpenFood: (FoodId) -> Unit,
    onCreateFood: (String?) -> Unit,
    onOpenPreferences: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FoodCatalogueViewModel = foodCatalogueViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    FoodsScreen(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onClearQuery = viewModel::onClearQuery,
        onSourceChange = viewModel::onSourceChange,
        onOpenFood = onOpenFood,
        onCreateFood = { onCreateFood(state.createPrefill) },
        onOpenPreferences = onOpenPreferences,
        modifier = modifier,
    )
}

/**
 * The catalogue: one search bar, one filter, one list (PRD_FOOD 9.4).
 *
 * `New food` is pinned to the bottom through the shipped [MueStickyBottomAction], which is two
 * parts on purpose — a painted ramp that holds no pointer input, and a solid block that does.
 * The list's own bottom padding is the **solid block alone**, `actionHeight - ramp`: subtracting
 * the whole band would leave a strip of scrollable content that no thumb can reach, which is the
 * 112 dp dead zone `Log activity` shipped with once.
 *
 * The way into `Preferences` sits in the header's trailing slot rather than on a card in the
 * list. PRD_FOOD 22's criterion about a permanent settings control is written of the **day**
 * screen, and PRD_FOOD 6.7 asks that the options themselves live in the preferences rather than
 * on a screen — a door is not an option.
 */
@Composable
internal fun FoodsScreen(
    state: FoodsUiState,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onSourceChange: (FoodSource?) -> Unit,
    onOpenFood: (FoodId) -> Unit,
    onCreateFood: () -> Unit,
    onOpenPreferences: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MueTheme.spacing
    val colors = MueTheme.colors
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    var actionHeight by remember { mutableStateOf(0.dp) }

    Box(modifier = modifier.fillMaxSize().testTag(FoodTestTags.FOODS)) {
        FoodViewScaffold(
            modifier = Modifier.fillMaxSize(),
            topFade = MueContentTopFade,
            trailing = { PreferencesButton(onOpenPreferences) },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    /*
                     * The pinned action's clearance, split exactly where the band is split.
                     * Outside the scroll the viewport ends above the **solid** block, so no row
                     * ever comes to rest under chrome that eats touches. The ramp is left in:
                     * it paints over live content, which is what dissolves the rows leaving the
                     * screen and what lets a thumb landing in the fade still scroll the list.
                     */
                    .padding(bottom = (actionHeight - MueStickyActionRamp).coerceAtLeast(0.dp))
                    .testTag(FoodTestTags.FOOD_LIST),
                contentPadding = PaddingValues(
                    top = MueContentTopFade,
                    // Inside the scroll, so the last card rests clear of the fade.
                    bottom = MueStickyActionRamp,
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.lg),
            ) {
                item(key = "title") {
                    MueScreenTitle(
                        title = FoodCatalogueMessages.TITLE,
                        eyebrow = FoodCatalogueMessages.EYEBROW,
                    )
                }

                item(key = "search") {
                    MueSearchField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.testTag(FoodTestTags.FOOD_SEARCH),
                        placeholder = FoodCatalogueMessages.SEARCH_PLACEHOLDER,
                        label = FoodCatalogueMessages.SEARCH_LABEL,
                        leadingIcon = {
                            MueIcon(ActivityIcons.SEARCH, tint = colors.textTertiary, size = 18.dp)
                        },
                        onClear = onClearQuery,
                        clearContentDescription = FoodCatalogueMessages.CLEAR_SEARCH,
                    )
                }

                item(key = "filter") {
                    SourceFilter(selected = state.source, onSelect = onSourceChange)
                }

                // PRD_FOOD 9.4: the recently used head an empty search, under their own name.
                if (state.hasRecent) {
                    item(key = "recentTitle") {
                        SectionTitle(FoodCatalogueMessages.RECENT_TITLE)
                    }
                    items(state.recent, key = { "recent:${it.id.value}" }) { row ->
                        FoodCatalogueRow(state = row, onClick = { onOpenFood(row.id) })
                    }
                    item(key = "resultsTitle") {
                        SectionTitle(FoodCatalogueMessages.RESULTS_TITLE)
                    }
                }

                items(state.results, key = { it.id.value }) { row ->
                    FoodCatalogueRow(state = row, onClick = { onOpenFood(row.id) })
                }

                /*
                 * PRD_FOOD 9.4 and 9.5: 1 038 entries, 60 of them on screen. Saying so is the
                 * difference between a list that is quick and a list that looks incomplete.
                 */
                if (state.isCapped) {
                    item(key = "capped") {
                        MueText(
                            text = FoodCatalogueMessages.showingFirst(state.resultLimit),
                            style = MueTheme.typography.caption,
                            color = colors.textQuiet,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                state.emptyMessage?.let { message ->
                    item(key = "empty") {
                        MueSurfaceCard(shape = MueTheme.shapes.field) {
                            /*
                             * PRD_FOOD 17 offers the creation rather than apologising, and the
                             * offer is the pinned action below, which already carries the term.
                             * Repeating it here would be two buttons for one intention.
                             */
                            MueText(message, MueTheme.typography.body)
                        }
                    }
                }
            }
        }

        MueStickyBottomAction(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { size ->
                    actionHeight = with(density) { size.height.toDp() }
                },
            coversContent = listState.canScrollForward,
        ) {
            MuePrimaryButton(
                label = state.createLabel,
                onClick = onCreateFood,
                modifier = Modifier.testTag(FoodTestTags.CREATE_FOOD),
            )
        }
    }
}

/** PRD_FOOD 9.4: one filter over the three sources, `All` included as its absence. */
@Composable
private fun SourceFilter(selected: FoodSource?, onSelect: (FoodSource?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
    ) {
        SOURCE_FILTERS.forEach { source ->
            MuePeriodPill(
                label = source?.let(FoodCatalogueMessages::sourceLabel)
                    ?: FoodCatalogueMessages.SOURCE_ALL,
                selected = source == selected,
                onClick = { onSelect(source) },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    MueText(
        text = text,
        style = MueTheme.typography.sectionTitle,
        color = MueTheme.colors.textSecondary,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The way into `Preferences` (PRD_FOOD 7).
 *
 * An icon alone inside a control, so PRD_FOOD 18 requires it to carry a label a screen reader
 * can read, and the target is the touch minimum whatever the glyph measures.
 */
@Composable
private fun PreferencesButton(onClick: () -> Unit) {
    val colors = MueTheme.colors
    Box(
        modifier = Modifier
            .size(MueMinTouchTarget)
            .clip(MueTheme.shapes.pill)
            .background(colors.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = FoodCatalogueMessages.OPEN_PREFERENCES }
            .testTag(FoodTestTags.OPEN_PREFERENCES),
        contentAlignment = Alignment.Center,
    ) {
        MueIcon(ActivityIcons.WRENCH, tint = colors.textSecondary, size = 18.dp)
    }
}

// region previews

@Preview(name = "Foods — catalogue", showBackground = true, backgroundColor = 0xFF101012, heightDp = 900)
@Composable
private fun FoodsScreenPreview() {
    MuePreviewHost(padding = 0) {
        FoodsScreen(
            state = previewFoodsState(),
            onQueryChange = {},
            onClearQuery = {},
            onSourceChange = {},
            onOpenFood = {},
            onCreateFood = {},
            onOpenPreferences = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The same catalogue at the largest text size on the narrowest phone.
 *
 * What to look for: every food name wrapped at a space rather than mid-word, no name ellipsised,
 * the energy dropped onto its own line under the name rather than crushed beside it, and the
 * `New food` band still leaving the last card reachable.
 */
@Preview(
    name = "Foods — 360 dp · largest font",
    showBackground = true,
    backgroundColor = 0xFF101012,
    widthDp = 360,
    heightDp = 900,
    fontScale = 2.0f,
)
@Composable
private fun FoodsScreenNarrowPreview() {
    MuePreviewHost(padding = 0) {
        FoodsScreen(
            state = previewFoodsState(),
            onQueryChange = {},
            onClearQuery = {},
            onSourceChange = {},
            onOpenFood = {},
            onCreateFood = {},
            onOpenPreferences = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * PRD_FOOD 13.2 and FR-FOOD-010, in one picture.
 *
 * Held beside `Foods — catalogue` it is the whole preference: the same five foods, the same
 * names, the same provenances, and not one figure. Nothing else about the screen moves.
 */
@Preview(name = "Foods — energy hidden", showBackground = true, backgroundColor = 0xFF101012, heightDp = 900)
@Composable
private fun FoodsScreenHiddenEnergyPreview() {
    MuePreviewHost(padding = 0) {
        FoodsScreen(
            state = hiddenEnergyFoodsState(),
            onQueryChange = {},
            onClearQuery = {},
            onSourceChange = {},
            onOpenFood = {},
            onCreateFood = {},
            onOpenPreferences = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** PRD_FOOD 17 and 22: a search that matched nothing offers a food already carrying the term. */
@Preview(name = "Foods — nothing matches", showBackground = true, backgroundColor = 0xFF101012, heightDp = 900)
@Composable
private fun FoodsScreenNoMatchPreview() {
    MuePreviewHost(padding = 0) {
        FoodsScreen(
            state = noMatchFoodsState(),
            onQueryChange = {},
            onClearQuery = {},
            onSourceChange = {},
            onOpenFood = {},
            onCreateFood = {},
            onOpenPreferences = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// endregion
