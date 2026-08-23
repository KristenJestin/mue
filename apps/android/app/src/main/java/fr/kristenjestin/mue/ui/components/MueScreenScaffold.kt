package fr.kristenjestin.mue.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.theme.MueTheme
import fr.kristenjestin.mue.ui.theme.mueAmberGlow

/**
 * Shared shell of the three screens: canvas, amber glow bleeding from the top edge, the
 * `MUE` wordmark with a contextual [trailing] slot, then the content column already gutted
 * to the screen padding.
 *
 * The bottom tab bar is intentionally *not* part of this scaffold: it lives above the
 * navigation host so it never moves during a tab transition (PRD 8).
 */
@Composable
fun MueScreenScaffold(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = MueTheme.spacing.screenHorizontal,
    trailing: @Composable (() -> Unit)? = null,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = MueTheme.spacing
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MueTheme.colors.canvas)
            .mueAmberGlow(),
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = horizontalPadding,
                        end = horizontalPadding,
                        top = spacing.screenTop,
                        bottom = spacing.sm,
                    )
                    .heightIn(min = 40.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MueText(
                    text = "MUE",
                    style = MueTheme.typography.wordmark,
                    color = MueTheme.colors.textPrimary,
                    // Without this TalkBack spells the three letters out.
                    modifier = Modifier.semantics { contentDescription = "Mue" },
                )
                trailing?.invoke()
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = horizontalPadding),
                verticalArrangement = verticalArrangement,
                content = content,
            )
        }
    }
}

/** Eyebrow plus title block that opens each screen. [eyebrow] is hidden when null. */
@Composable
fun MueScreenTitle(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        eyebrow?.let {
            MueText(it, MueTheme.typography.eyebrow, color = MueTheme.colors.textSecondary)
        }
        MueText(
            text = title,
            style = MueTheme.typography.screenTitle,
            color = MueTheme.colors.textPrimary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Preview(name = "Screen scaffold", showBackground = true, backgroundColor = 0xFF101012, heightDp = 520)
@Composable
private fun MueScreenScaffoldPreview() {
    MueTheme {
        MueScreenScaffold(trailing = { MueHeaderChip("Today") }) {
            MueScreenTitle(
                title = "Where are you today?",
                eyebrow = "Hello Kris,",
                modifier = Modifier.padding(top = MueTheme.spacing.xl),
            )
            MueAnimatedNumber(
                text = "74.5",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MueTheme.spacing.xxl),
                suffix = "kg",
                horizontalArrangement = Arrangement.Center,
            )
            MueText(
                text = "SLIDE TO ADJUST",
                style = MueTheme.typography.hint,
                color = MueTheme.colors.accent,
                modifier = Modifier.fillMaxWidth().padding(top = MueTheme.spacing.md),
            )
        }
    }
}
