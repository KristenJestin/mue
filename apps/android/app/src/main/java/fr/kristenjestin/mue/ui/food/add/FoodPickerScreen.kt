package fr.kristenjestin.mue.ui.food.add

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.ui.activity.ActivityIcons
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MuePreviewHost
import fr.kristenjestin.mue.ui.components.MueIcons
import fr.kristenjestin.mue.ui.components.MueSearchField
import fr.kristenjestin.mue.ui.components.MueSecondaryButton
import fr.kristenjestin.mue.ui.components.MueSplitRow
import fr.kristenjestin.mue.ui.components.MueSubScreenScaffold
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueTheme

private val BackIconSize: Dp = 18.dp

/**
 * PRD_FOOD 11's shared selector, and the way in FR-FOOD-002 opens (PRD_FOOD 9.4).
 *
 * It hands back a [FoodId] rather than a `Food`: the caller reads the card from the catalogue
 * itself, so a food corrected between being chosen and being used is quoted as it is now.
 *
 * [onCreateFood] carries the term that found nothing (PRD_FOOD 9.4 and 17), which is the
 * ViewModel's own trimmed query rather than whatever happened to be in the field at the moment of
 * the tap.
 */
@Composable
internal fun FoodPickerRoute(
    onPicked: (FoodId) -> Unit,
    onCreateFood: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FoodPickerViewModel = foodPickerViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    FoodPickerScreen(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onClearQuery = viewModel::onClearQuery,
        onSourceSelected = viewModel::onSourceSelected,
        onPicked = onPicked,
        onCreateFood = { onCreateFood(viewModel.searchTerm) },
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * The picker as it is drawn: one search bar, one source filter, one list (PRD_FOOD 9.4).
 *
 * The list is lazy because the catalogue is not small — 1 038 entries are seeded on first launch —
 * and because a search that matches half of them must still scroll like a list of ten.
 *
 * No name is ever truncated here. PRD_FOOD 15 lets a food name run to 80 characters and the
 * catalogue is full of them: `Courgette or zucchini, flesh and skin, raw` is a name that a
 * one-line row would cut in the middle, while every assertion about it still passed.
 */
@Composable
internal fun FoodPickerScreen(
    state: FoodPickerUiState,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onSourceSelected: (FoodSource?) -> Unit,
    onPicked: (FoodId) -> Unit,
    onCreateFood: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MueTheme.spacing

    MueSubScreenScaffold(
        title = FoodAddMessages.PICKER_TITLE,
        onNavigateBack = onBack,
        navigationIcon = {
            MueIcon(
                iconName = MueIcons.ARROW_LEFT,
                tint = MueTheme.colors.textSecondary,
                size = BackIconSize,
            )
        },
        modifier = modifier.testTag(FoodTestTags.FOOD_PICKER),
    ) {
        MueSearchField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .padding(top = spacing.md)
                .testTag(FoodTestTags.SEARCH_FIELD),
            placeholder = FoodAddMessages.SEARCH_PLACEHOLDER,
            label = FoodAddMessages.SEARCH_LABEL,
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

        SourceFilter(
            sources = state.sources,
            onSourceSelected = onSourceSelected,
            modifier = Modifier.padding(top = spacing.md),
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
                .testTag(FoodTestTags.SEARCH_RESULTS),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
            contentPadding = PaddingValues(bottom = spacing.xxxl),
        ) {
            items(items = state.results, key = { it.id }) { row ->
                FoodResultRow(row = row, onClick = { onPicked(FoodId(row.id)) })
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
             * PRD_FOOD 9.4 and 17: "une recherche sans résultat propose la création d'un aliment
             * pré-rempli du terme saisi".
             *
             * The term cannot travel on the route — `FoodRoute.FoodEditor` carries a food id and
             * nothing else — so it travels beside the stack, in the holder `Foods` already put
             * there for its own creation. One shortcoming of the frozen route, answered once.
             */
            if (!state.isRecent && state.isEmpty) {
                item(key = "create") {
                    MueSecondaryButton(
                        label = FoodAddMessages.CREATE_FOOD,
                        onClick = onCreateFood,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(FoodTestTags.CREATE_FOOD),
                    )
                }
            }
        }
    }
}

/**
 * PRD_FOOD 9.4's one filter, as a scrolling row of chips.
 *
 * Each chip sizes to its own word inside a scrolling row, so nothing is squeezed or clipped at a
 * large font scale — which a fixed-height pill or an equal-width segmented row would both do with
 * four options.
 */
@Composable
private fun SourceFilter(
    sources: List<FoodSourceFilterUiState>,
    onSourceSelected: (FoodSource?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
    ) {
        sources.forEach { option ->
            SourceChip(option = option, onClick = { onSourceSelected(option.source) })
        }
    }
}

@Composable
private fun SourceChip(option: FoodSourceFilterUiState, onClick: () -> Unit) {
    val colors = MueTheme.colors
    val shape = MueTheme.shapes.pill
    Box(
        modifier = Modifier
            .heightIn(min = MueMinTouchTarget)
            .clip(shape)
            .background(if (option.selected) colors.accentSoft else colors.surfaceStrong)
            .border(
                width = if (option.selected) 2.dp else 1.dp,
                color = if (option.selected) colors.accent else colors.surfaceBorder,
                shape = shape,
            )
            .selectable(
                selected = option.selected,
                indication = null,
                interactionSource = null,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = MueTheme.spacing.lg, vertical = MueTheme.spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        MueText(
            text = option.label,
            style = MueTheme.typography.chip,
            color = if (option.selected) colors.onAccentSoft else colors.textTertiary,
        )
    }
}

/**
 * One food in the list: what it is, where it came from, and what it is worth per 100.
 *
 * The name and the energy are split by measurement rather than by weight, so a long name and a
 * `≈ 341 kcal` beside it stack instead of crushing each other at a doubled font scale — the very
 * defect the `Day` screen's journal card was rebuilt to fix.
 */
@Composable
private fun FoodResultRow(row: FoodPickerRowUiState, onClick: () -> Unit) {
    val colors = MueTheme.colors
    val type = MueTheme.typography
    val spacing = MueTheme.spacing

    MueSurfaceCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(FoodTestTags.foodCard(row.id)),
        shape = MueTheme.shapes.field,
        contentPadding = PaddingValues(spacing.md),
        onClick = onClick,
        onClickLabel = row.name,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MueMinTouchTarget)
                .clearAndSetSemantics { contentDescription = row.description },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(MueMinTouchTarget)
                    .clip(MueTheme.shapes.small)
                    .background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                MueIcon(iconName = row.iconName, tint = colors.onAccentSoft, size = 18.dp)
            }

            MueSplitRow(
                modifier = Modifier.weight(1f).padding(start = spacing.md),
                gap = spacing.md,
                start = {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                        MueText(row.name, type.bodyStrong)
                        MueText(row.meta, type.micro, color = colors.textTertiary)
                    }
                },
                end = {
                    Column(horizontalAlignment = Alignment.End) {
                        MueText(row.energyLabel, type.bodyStrong)
                        MueText(row.per100Label, type.micro, color = colors.textTertiary)
                    }
                },
            )
        }
    }
}

// region previews

@Preview(name = "Food picker — recents", showBackground = true, backgroundColor = 0xFF101012, heightDp = 800)
@Composable
private fun FoodPickerPreview() {
    MuePreviewHost(padding = 0) {
        FoodPickerScreen(
            state = previewPickerState(),
            onQueryChange = {},
            onClearQuery = {},
            onSourceSelected = {},
            onPicked = {},
            onCreateFood = {},
            onBack = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The same list at the largest font scale on the narrowest phone.
 *
 * What to look for: the 80-character name wrapped at spaces and whole, and its energy under the
 * facts rather than crushed against them.
 */
@Preview(
    name = "Food picker — 360 dp · largest font",
    showBackground = true,
    backgroundColor = 0xFF101012,
    widthDp = 360,
    heightDp = 900,
    fontScale = 2.0f,
)
@Composable
private fun FoodPickerNarrowPreview() {
    MuePreviewHost(padding = 0) {
        FoodPickerScreen(
            state = previewPickerState(),
            onQueryChange = {},
            onClearQuery = {},
            onSourceSelected = {},
            onPicked = {},
            onCreateFood = {},
            onBack = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** PRD_FOOD 17: a search with no result offers the way out of it. */
@Preview(name = "Food picker — nothing matches", showBackground = true, backgroundColor = 0xFF101012, heightDp = 600)
@Composable
private fun FoodPickerEmptyPreview() {
    MuePreviewHost(padding = 0) {
        FoodPickerScreen(
            state = previewEmptyPickerState(),
            onQueryChange = {},
            onClearQuery = {},
            onSourceSelected = {},
            onPicked = {},
            onCreateFood = {},
            onBack = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// endregion
