package fr.kristenjestin.mue.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueTheme

/**
 * Rounded container holding a small grey label above a value. Used both for editable fields
 * and for read-only rows that open a picker, hence the optional [onClick].
 */
@Composable
fun MueFieldContainer(
    label: String,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    isError: Boolean = false,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    trailing: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MueTheme.colors
    val shape = MueTheme.shapes.field
    val borderColor by animateColorAsState(
        targetValue = when {
            isError -> colors.error
            focused -> colors.surfaceBorderFocused
            else -> colors.surfaceBorder
        },
        animationSpec = MueMotion.spec(MueMotion.ManualEntryMillis),
        label = "fieldBorder",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, borderColor, shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        onClickLabel = onClickLabel,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .heightIn(min = 64.dp)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            MueText(label, MueTheme.typography.label, color = colors.textTertiary, maxLines = 1)
            content()
        }
        trailing?.let {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = it,
            )
        }
    }
}

/**
 * Editable field built on [MueFieldContainer]. Deliberately not a Material `TextField`:
 * only the caret colour is borrowed from the platform.
 */
@Composable
fun MueTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    suffix: String? = null,
    errorMessage: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    textStyle: TextStyle = MueTheme.typography.fieldValue,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val colors = MueTheme.colors
    var focused by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MueFieldContainer(
            label = label,
            focused = focused,
            isError = errorMessage != null,
            trailing = trailing,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 32.dp)
                        .onFocusChanged { focused = it.isFocused },
                    enabled = enabled,
                    singleLine = singleLine,
                    textStyle = textStyle.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty() && placeholder != null) {
                                MueText(
                                    placeholder,
                                    textStyle,
                                    color = colors.textTertiary,
                                    maxLines = 1,
                                )
                            }
                            inner()
                        }
                    },
                )
                suffix?.let {
                    MueText(
                        it,
                        MueTheme.typography.body,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
                    )
                }
            }
        }

        errorMessage?.let { message ->
            MueText(
                text = message,
                style = MueTheme.typography.caption,
                color = colors.error,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .semantics { error(message) },
            )
        }
    }
}

/**
 * Read-only field row whose whole surface opens a picker — the `Measurement date` and
 * `Date of birth` rows of the prototypes.
 */
@Composable
fun MuePickerField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onClickLabel: String? = null,
    trailingText: String? = null,
) {
    MueFieldContainer(
        label = label,
        modifier = modifier.heightIn(min = MueMinTouchTarget),
        onClick = onClick,
        onClickLabel = onClickLabel,
        trailing = trailingText?.let {
            {
                MueText(it, MueTheme.typography.chip, color = MueTheme.colors.accent, maxLines = 1)
            }
        },
    ) {
        MueText(value, MueTheme.typography.bodyStrong, maxLines = 1)
    }
}

@Preview(name = "Fields", showBackground = true, backgroundColor = 0xFF101012)
@Composable
private fun MueFieldPreview() {
    MuePreviewHost {
        MueTextField(
            label = "Display name",
            value = "Kris",
            onValueChange = {},
            textStyle = MueTheme.typography.bodyStrong,
        )
        MueTextField(
            label = "Height",
            value = "180",
            onValueChange = {},
            suffix = "cm",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        MueTextField(
            label = "Weight in kilograms",
            value = "312.0",
            onValueChange = {},
            errorMessage = "Weight must be between 30.0 and 250.0 kg",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        MuePickerField(
            label = "Measurement date",
            value = "August 23, 2026",
            trailingText = "Change",
            onClick = {},
        )
    }
}
