package fr.kristenjestin.mue.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme

/**
 * The search line of the two catalogue pickers (FR-ACTIVITY-008, FR-ACTIVITY-009).
 *
 * The typed text is the caller's, not this field's: the pickers filter on it *and* offer to
 * create an entry from it, so it can never be private state here.
 *
 * [focusRequester] is exposed rather than auto-focused internally. The prototype focuses the
 * field once the sheet has finished rising, and only the sheet knows when that is.
 */
@Composable
fun MueSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
    label: String = "Search",
    leadingIcon: (@Composable () -> Unit)? = null,
    onClear: (() -> Unit)? = null,
    clearContentDescription: String = "Clear search",
    focusRequester: FocusRequester? = null,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val colors = MueTheme.colors
    val shape = MueTheme.shapes.field
    var focused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = if (focused) colors.surfaceBorderFocused else colors.surfaceBorder,
        animationSpec = MueMotion.spec(MueMotion.ManualEntryMillis),
        label = "searchBorder",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MueMinTouchTarget)
            .clip(shape)
            .background(colors.surfaceStrong)
            .border(1.dp, borderColor, shape)
            .padding(horizontal = MueTheme.spacing.lg, vertical = MueTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.md),
    ) {
        leadingIcon?.invoke()

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = label },
            singleLine = true,
            textStyle = MueTheme.typography.body.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Search,
            ),
            keyboardActions = keyboardActions,
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        MueText(
                            placeholder,
                            MueTheme.typography.body,
                            color = colors.textQuiet,
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            },
        )

        if (onClear != null && value.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(MueMinTouchTarget)
                    .clip(MueTheme.shapes.pill)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onClear,
                    )
                    .semantics { contentDescription = clearContentDescription },
                contentAlignment = Alignment.Center,
            ) {
                MueText("×", MueTheme.typography.bodyStrong, color = colors.textTertiary)
            }
        }
    }
}

@Preview(name = "Search field", showBackground = true, backgroundColor = 0xFF101012, widthDp = 390)
@Composable
private fun MueSearchFieldPreview() {
    MuePreviewHost(padding = 28) {
        MueSearchField(
            value = "",
            onValueChange = {},
            placeholder = "Search squat, row, plank…",
            leadingIcon = { MuePreviewIcon(MuePreviewGlyph.SEARCH) },
        )
        MueSearchField(
            value = "plank",
            onValueChange = {},
            placeholder = "Search squat, row, plank…",
            leadingIcon = { MuePreviewIcon(MuePreviewGlyph.SEARCH) },
            onClear = {},
        )
    }
}
