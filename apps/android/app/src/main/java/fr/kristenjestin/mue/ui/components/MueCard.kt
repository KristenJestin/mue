package fr.kristenjestin.mue.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.theme.LocalMueContentColor
import fr.kristenjestin.mue.ui.theme.MueTheme

/**
 * Default container: hairline outline over a barely-there white fill, large radius.
 *
 * The fill stays translucent on purpose so the amber glow behind the screen shows through
 * the card, exactly as in the prototypes. That also rules out an elevation shadow, which
 * would be visible *through* the fill; depth comes from the outline instead.
 */
@Composable
fun MueSurfaceCard(
    modifier: Modifier = Modifier,
    shape: Shape = MueTheme.shapes.card,
    contentPadding: PaddingValues = PaddingValues(MueTheme.spacing.cardPadding),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MueTheme.colors
    CompositionLocalProvider(LocalMueContentColor provides colors.textPrimary) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.surface)
                .border(1.dp, colors.surfaceBorder, shape)
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
                .padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

/**
 * Amber-filled variant used for the BMI card and the highlighted indicator. It publishes
 * [MueTheme.contentColor] as the ink colour so nested text needs no override.
 */
@Composable
fun MueAccentCard(
    modifier: Modifier = Modifier,
    shape: Shape = MueTheme.shapes.card,
    contentPadding: PaddingValues = PaddingValues(MueTheme.spacing.cardPadding),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MueTheme.colors
    CompositionLocalProvider(LocalMueContentColor provides colors.onAccent) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 16.dp,
                    shape = shape,
                    clip = false,
                    ambientColor = colors.accent.copy(alpha = 0.25f),
                    spotColor = colors.accent.copy(alpha = 0.40f),
                )
                .clip(shape)
                .background(colors.accent)
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
                .padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

@Preview(name = "Cards", showBackground = true, backgroundColor = 0xFF101012)
@Composable
private fun MueCardPreview() {
    MuePreviewHost {
        MueSurfaceCard {
            MueText("Current weight", MueTheme.typography.label, color = MueTheme.colors.textTertiary)
            MueText("74.5 kg", MueTheme.typography.metricLarge)
        }
        MueSurfaceCard(shape = MueTheme.shapes.field, contentPadding = PaddingValues(16.dp)) {
            MueText("Current BMI", MueTheme.typography.label, color = MueTheme.colors.textTertiary)
            MueText("23.0", MueTheme.typography.metricMedium)
            MueText("Healthy weight", MueTheme.typography.caption, color = MueTheme.colors.accent)
        }
        MueAccentCard {
            MueText(
                "Body mass index",
                MueTheme.typography.label,
                color = MueTheme.colors.onAccentSecondary,
            )
            MueText("23.0", MueTheme.typography.metricDisplay)
            MueText(
                "BMI is a general screening indicator, not an individual diagnosis.",
                MueTheme.typography.caption,
                color = MueTheme.colors.onAccentSecondary,
            )
        }
    }
}
