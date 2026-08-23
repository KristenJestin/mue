package fr.kristenjestin.mue.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.theme.MueTheme

/**
 * Text bound to the Mue type scale. The colour defaults to whatever the enclosing container
 * publishes, so the same call renders correctly on the canvas and on an amber card.
 */
@Composable
fun MueText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = MueTheme.contentColor,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        style = style,
        color = color,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
    )
}

/** Hairline rule, used between history rows and above the tab bar. */
@Composable
fun MueDivider(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MueTheme.colors.hairline),
    ) {}
}

@Preview(name = "Type scale", showBackground = true, backgroundColor = 0xFF101012, heightDp = 720)
@Composable
private fun MueTypeScalePreview() {
    MuePreviewHost {
        val type = MueTheme.typography
        val colors = MueTheme.colors
        MueText("MUE", type.wordmark)
        MueText("Hello Kris,", type.eyebrow, color = colors.textSecondary)
        MueText("Where are you today?", type.screenTitle)
        MueText("74.5", type.weightDisplay)
        MueText("SLIDE TO ADJUST", type.hint, color = colors.accent)
        MueText("23.0", type.metricDisplay)
        MueText("74.5", type.metricLarge)
        MueText("−0.3", type.metricMedium)
        MueText("180", type.fieldValue)
        MueText("Latest measurements", type.sectionTitle)
        MueText("Measurement date", type.label, color = colors.textTertiary)
        MueText("Height is used to calculate BMI.", type.body, color = colors.textSecondary)
        MueText("August 18", type.caption, color = colors.textSecondary)
        MueText("kg / week", type.micro, color = colors.textTertiary)
    }
}

@Preview(name = "Palette", showBackground = true, backgroundColor = 0xFF101012)
@Composable
private fun MuePalettePreview() {
    MuePreviewHost {
        val colors = MueTheme.colors
        listOf(
            "accent 10.3:1" to colors.accent,
            "textPrimary 19.0:1" to colors.textPrimary,
            "textSecondary 6.2:1" to colors.textSecondary,
            "textTertiary 5.0:1" to colors.textTertiary,
            "error 7.1:1" to colors.error,
            "textQuiet — decoration only" to colors.textQuiet,
        ).forEach { (name, color) ->
            MueText(name, MueTheme.typography.bodyStrong, color = color)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.accentSoft)
                .padding(12.dp),
        ) {
            MueText("accentSoft container 7.7:1", MueTheme.typography.body, color = colors.onAccentSoft)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.accent)
                .padding(12.dp),
        ) {
            MueText("onAccent 9.7:1", MueTheme.typography.body, color = colors.onAccent)
            MueText("onAccentSecondary 4.6:1", MueTheme.typography.body, color = colors.onAccentSecondary)
        }
    }
}

/** Shared host for the `@Preview` composables of this package. */
@Composable
internal fun MuePreviewHost(
    padding: Int = 20,
    content: @Composable ColumnScope.() -> Unit,
) {
    MueTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MueTheme.colors.canvas)
                .padding(padding.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}
