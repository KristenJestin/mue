package fr.kristenjestin.mue.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueTheme

private val AvatarSize = 40.dp
private val IndicatorSize = 28.dp

/**
 * The catalogue picker of FR-ACTIVITY-008 and FR-ACTIVITY-009: an editorial header, a search
 * line, a single list, and a footer that offers to create what the search did not find.
 *
 * Header and search stay put while the list scrolls under them, which is where this departs
 * from the prototype: there the whole panel scrolls, and the search box — the only way out of
 * a list of seventeen exercises — leaves the screen as soon as you use the list.
 *
 * The list is a plain `Column` inside the sheet's scroll rather than a `LazyColumn`. Every
 * catalogue in this module is a closed enum of fewer than twenty entries, and a lazy list
 * cannot be nested in a scrolling parent without giving it a height of its own.
 */
@Composable
fun MuePickerSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    title: String,
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    searchPlaceholder: String = "Search",
    searchLabel: String = "Search the catalogue",
    searchIcon: (@Composable () -> Unit)? = null,
    searchFocusRequester: FocusRequester? = null,
    closeContentDescription: String = "Close",
    maxHeightFraction: Float = MueBottomSheetDefaults.MaxHeightFraction,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    MueBottomSheet(
        visible = visible,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        scrimContentDescription = closeContentDescription,
        maxHeightFraction = maxHeightFraction,
        bodyScrolls = true,
        header = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    eyebrow?.let {
                        MueText(it, MueTheme.typography.caption, color = MueTheme.colors.textTertiary)
                    }
                    MueText(
                        text = title,
                        style = MueTheme.typography.screenTitle,
                        color = MueTheme.colors.textPrimary,
                        modifier = Modifier
                            .padding(top = MueTheme.spacing.xxs)
                            .semantics { heading() },
                    )
                }
                Box(
                    modifier = Modifier
                        .size(MueMinTouchTarget)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = onDismissRequest,
                        )
                        .semantics { contentDescription = closeContentDescription },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MueTheme.colors.surfaceStrong),
                        contentAlignment = Alignment.Center,
                    ) {
                        MueText(
                            "×",
                            MueTheme.typography.bodyStrong,
                            color = MueTheme.colors.textTertiary,
                        )
                    }
                }
            }

            MueSearchField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = searchPlaceholder,
                label = searchLabel,
                leadingIcon = searchIcon,
                onClear = { onQueryChange("") },
                focusRequester = searchFocusRequester,
            )
        },
    ) {
        content()
        footer?.invoke(this)
    }
}

/** `Recent & common` / `Results`, with the count of what is under it. */
@Composable
fun MuePickerSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MueText(
            text = title.uppercase(),
            style = MueTheme.typography.hint,
            color = MueTheme.colors.textTertiary,
            maxLines = 1,
        )
        trailing?.let {
            MueText(it, MueTheme.typography.micro, color = MueTheme.colors.textQuiet, maxLines = 1)
        }
    }
}

/** Bordered container the rows are divided inside, as in both prototypes. */
@Composable
fun MuePickerList(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MueTheme.shapes.field
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MueTheme.colors.surface)
            .border(1.dp, MueTheme.colors.surfaceBorder, shape),
        content = content,
    )
}

/**
 * One catalogue entry.
 *
 * The selected state is drawn as a filled indicator rather than a tick: the row is already
 * `selectable`, so TalkBack says `selected` on its own, and the shape change from outline to
 * fill is the non-colour cue PRD 15 asks for. A screen that wants the Lucide `check` inside it
 * passes [selectedIndicator].
 */
@Composable
fun MuePickerRow(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    meta: String? = null,
    selected: Boolean = false,
    showDivider: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    selectedIndicator: (@Composable () -> Unit)? = null,
) {
    val colors = MueTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
        if (showDivider) MueDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MueMinTouchTarget)
                .selectable(
                    selected = selected,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(horizontal = MueTheme.spacing.lg, vertical = MueTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.md),
        ) {
            Box(
                modifier = Modifier
                    .size(AvatarSize)
                    .clip(MueTheme.shapes.small)
                    .background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                if (leading != null) {
                    leading()
                } else {
                    MueText(
                        text = name.take(1).uppercase(),
                        style = MueTheme.typography.bodyStrong,
                        color = colors.onAccentSoft,
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                MueText(name, MueTheme.typography.bodyStrong, maxLines = 1)
                meta?.let {
                    MueText(it, MueTheme.typography.caption, color = colors.textTertiary, maxLines = 1)
                }
            }

            Box(
                modifier = Modifier
                    .size(IndicatorSize)
                    .clip(CircleShape)
                    .then(
                        if (selected) {
                            Modifier.background(colors.accent)
                        } else {
                            Modifier.border(1.dp, colors.surfaceBorder, CircleShape)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    selected && selectedIndicator != null -> selectedIndicator()
                    selected -> Unit
                    else -> MueText(
                        "+",
                        MueTheme.typography.bodyStrong,
                        color = colors.textTertiary,
                    )
                }
            }
        }
    }
}

/** `No matching exercise yet.` — a statement of fact, with nothing asked of the reader. */
@Composable
fun MuePickerEmpty(message: String, modifier: Modifier = Modifier) {
    MueText(
        text = message,
        style = MueTheme.typography.body,
        color = MueTheme.colors.textTertiary,
        modifier = modifier
            .fillMaxWidth()
            .padding(PaddingValues(horizontal = MueTheme.spacing.lg, vertical = MueTheme.spacing.xl)),
        textAlign = TextAlign.Center,
    )
}

@Preview(name = "Picker sheet body", showBackground = true, backgroundColor = 0xFF101012, widthDp = 390)
@Composable
private fun MuePickerSheetPreview() {
    // The sheet needs a dialog window, so the preview shows the panel body only.
    MuePreviewHost(padding = 0) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MueTheme.shapes.sheet)
                .background(MueTheme.colors.canvasElevated)
                .padding(
                    horizontal = MueTheme.spacing.screenHorizontal,
                    vertical = MueTheme.spacing.xxl,
                ),
            verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.lg),
        ) {
            MueText("What best describes it?", MueTheme.typography.caption, color = MueTheme.colors.textTertiary)
            MueText("Choose an activity", MueTheme.typography.screenTitle)
            MueSearchField(
                value = "",
                onValueChange = {},
                placeholder = "Search yoga, hiking, rowing…",
                leadingIcon = { MuePreviewIcon(MuePreviewGlyph.SEARCH) },
            )
            MuePickerSectionHeader("Common choices", trailing = "10 results")
            MuePickerList {
                listOf(
                    Triple("Yoga", "Mobility & mind-body", true),
                    Triple("Hiking", "Walking · usually outdoor", false),
                    Triple("Rowing", "Indoor or outdoor", false),
                ).forEachIndexed { index, (name, meta, selected) ->
                    MuePickerRow(
                        name = name,
                        meta = meta,
                        selected = selected,
                        showDivider = index > 0,
                        onClick = {},
                    )
                }
            }
            MueDashedAction(
                label = "Search before creating a custom item",
                onClick = {},
                enabled = false,
                icon = { MuePreviewIcon(MuePreviewGlyph.PLUS, size = 14.dp) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
