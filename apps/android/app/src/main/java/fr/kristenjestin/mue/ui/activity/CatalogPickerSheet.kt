package fr.kristenjestin.mue.ui.activity

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.components.MueDashedAction
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MueIcons
import fr.kristenjestin.mue.ui.components.MuePickerEmpty
import fr.kristenjestin.mue.ui.components.MuePickerList
import fr.kristenjestin.mue.ui.components.MuePickerRow
import fr.kristenjestin.mue.ui.components.MuePickerSectionHeader
import fr.kristenjestin.mue.ui.components.MuePickerSheet
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.theme.MueTheme

/**
 * The searchable catalogue of PRD FR-ACTIVITY-008, for the main activity and for equipment
 * alike.
 *
 * One sheet serves both because both answer the same question — *is what you did already
 * named?* — and both end with the same last resort. Creating is deliberately the footer and
 * not a peer of the list: PRD 8.5 wants the catalogue tried first, and a free name is the only
 * thing that produces `movement = other`.
 *
 * The last non-null state is kept so the panel still has something to draw while it slides
 * back down after a choice.
 */
@Composable
internal fun CatalogPickerSheet(
    picker: CatalogPickerState?,
    onQueryChange: (String) -> Unit,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    var lastPicker by remember { mutableStateOf(picker) }
    if (picker != null) lastPicker = picker
    val shown = lastPicker ?: return

    MuePickerSheet(
        visible = picker != null,
        onDismissRequest = onDismiss,
        title = shown.title,
        eyebrow = shown.eyebrow,
        query = shown.query,
        onQueryChange = onQueryChange,
        searchPlaceholder = shown.searchPlaceholder,
        searchLabel = shown.searchLabel,
        searchIcon = { MueIcon(ActivityIcons.SEARCH, tint = MueTheme.colors.textTertiary) },
        footer = {
            shown.notice?.let { message ->
                MueText(
                    text = message,
                    style = MueTheme.typography.caption,
                    color = MueTheme.colors.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            MueDashedAction(
                label = if (shown.canCreate) {
                    LogActivityMessages.create(shown.trimmedQuery)
                } else {
                    LogActivityMessages.CREATE_HINT
                },
                onClick = onCreate,
                enabled = shown.canCreate,
                icon = { MueIcon(ActivityIcons.SPARKLES, size = 16.dp) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        MuePickerSectionHeader(
            title = if (shown.query.isBlank()) {
                LogActivityMessages.COMMON_CHOICES
            } else {
                LogActivityMessages.RESULTS
            },
            trailing = LogActivityMessages.resultCount(shown.results.size),
        )

        if (shown.results.isEmpty()) {
            MuePickerEmpty(LogActivityMessages.NO_CATALOGUE_MATCH)
        } else {
            MuePickerList {
                shown.results.forEachIndexed { index, entry ->
                    MuePickerRow(
                        name = entry.name,
                        meta = entry.meta,
                        selected = entry.selected,
                        showDivider = index > 0,
                        onClick = { onSelect(entry.id) },
                        selectedIndicator = {
                            MueIcon(
                                iconName = MueIcons.CHECK,
                                tint = MueTheme.colors.onAccent,
                                size = 14.dp,
                            )
                        },
                    )
                }
            }
        }
    }
}

private val CatalogPickerState.title: String
    get() = when (target) {
        CatalogTarget.MOVEMENT -> LogActivityMessages.ACTIVITY_PICKER_TITLE
        CatalogTarget.EQUIPMENT -> LogActivityMessages.EQUIPMENT_PICKER_TITLE
    }

private val CatalogPickerState.eyebrow: String
    get() = when (target) {
        CatalogTarget.MOVEMENT -> LogActivityMessages.ACTIVITY_PICKER_EYEBROW
        CatalogTarget.EQUIPMENT -> LogActivityMessages.EQUIPMENT_PICKER_EYEBROW
    }

private val CatalogPickerState.searchLabel: String
    get() = when (target) {
        CatalogTarget.MOVEMENT -> LogActivityMessages.SEARCH_ACTIVITY_LABEL
        CatalogTarget.EQUIPMENT -> LogActivityMessages.SEARCH_EQUIPMENT_LABEL
    }

private val CatalogPickerState.searchPlaceholder: String
    get() = when (target) {
        CatalogTarget.MOVEMENT -> LogActivityMessages.SEARCH_ACTIVITY_PLACEHOLDER
        CatalogTarget.EQUIPMENT -> LogActivityMessages.SEARCH_EQUIPMENT_PLACEHOLDER
    }
