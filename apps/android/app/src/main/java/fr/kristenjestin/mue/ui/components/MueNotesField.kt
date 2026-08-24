package fr.kristenjestin.mue.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme

/** PRD FR-ACTIVITY-005: a note is at most 500 characters. */
const val MueNotesMaxLength: Int = 500

/**
 * Full-width multiline note with a character counter (FR-ACTIVITY-005).
 *
 * It builds its own container rather than wrapping [MueFieldContainer]: that one centres a
 * single row inside `heightIn(min = 64.dp)`, which is exactly right for a one-line value and
 * exactly wrong for a text area, where the label must sit at the top and the caret start on
 * the first line however tall the box grows.
 *
 * The limit is enforced on the way in — a paste longer than [maxLength] is truncated rather
 * than rejected — so the counter can never read a number the draft would refuse to save.
 */
@Composable
fun MueNotesField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Notes",
    optionalSuffix: String? = "· optional",
    placeholder: String = "How did it feel? Anything worth remembering?",
    maxLength: Int = MueNotesMaxLength,
    minLines: Int = 3,
    icon: (@Composable () -> Unit)? = null,
) {
    val colors = MueTheme.colors
    val shape = MueTheme.shapes.field
    var focused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = if (focused) colors.surfaceBorderFocused else colors.surfaceBorder,
        animationSpec = MueMotion.spec(MueMotion.ManualEntryMillis),
        label = "notesBorder",
    )
    val atLimit = value.length >= maxLength

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, borderColor, shape)
            .padding(MueTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.md),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
        ) {
            icon?.invoke()
            MueText(label, MueTheme.typography.bodyStrong, color = colors.textSecondary, maxLines = 1)
            optionalSuffix?.let {
                MueText(it, MueTheme.typography.caption, color = colors.textQuiet, maxLines = 1)
            }
        }

        BasicTextField(
            value = value,
            onValueChange = { onValueChange(it.take(maxLength)) },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = label },
            minLines = minLines,
            textStyle = MueTheme.typography.body.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        MueText(placeholder, MueTheme.typography.body, color = colors.textQuiet)
                    }
                    inner()
                }
            },
        )

        MueText(
            text = "${value.length}/$maxLength",
            style = MueTheme.typography.micro,
            color = if (atLimit) colors.accent else colors.textQuiet,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "${value.length} of $maxLength characters used"
                },
        )
    }
}

@Preview(name = "Notes field", showBackground = true, backgroundColor = 0xFF101012, widthDp = 390)
@Composable
private fun MueNotesFieldPreview() {
    MuePreviewHost(padding = 28) {
        MueNotesField(
            value = "",
            onValueChange = {},
            icon = { MuePreviewIcon(MuePreviewGlyph.DOT, size = 16.dp) },
        )
        MueNotesField(
            value = "Legs felt heavy on the first kilometre, then it settled. " +
                "Kept the incline at two the whole way.",
            onValueChange = {},
        )
        MueNotesField(value = "x".repeat(MueNotesMaxLength), onValueChange = {}, minLines = 2)
    }
}
