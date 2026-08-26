package fr.kristenjestin.mue.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.theme.LocalMueContentColor
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme

/**
 * The two shapes this tile takes.
 *
 * The compact one is three across, so a 390 dp screen gives it 106 dp. `Treadmill` measures
 * 66 dp of the 74 dp a 16 dp padding would leave it — a label one wider glyph away from
 * ellipsising. The compact defaults therefore drop to the prototype's own 12 dp padding and to
 * the 12 sp of [MueTypography.chip], which brings the same word back to 57 dp in an 82 dp box.
 */
object MueChoiceCardDefaults {

    /** The prototype's `min-h-[88px]` preset tile. */
    val CompactHeight: Dp = 88.dp

    /** The taller `Quick log` / `Detailed` pair, which carries a description under its title. */
    val TallHeight: Dp = 132.dp

    val CompactPadding: PaddingValues = PaddingValues(12.dp)
    val TallPadding: PaddingValues = PaddingValues(16.dp)

    @Composable
    fun compactLabelStyle(): TextStyle = MueTheme.typography.chip

    @Composable
    fun tallLabelStyle(): TextStyle = MueTheme.typography.bodyStrong
}

/**
 * A tile the person picks one of: the six presets of FR-ACTIVITY-004 and the Quick/Detailed
 * pair of FR-ACTIVITY-009.
 *
 * Selection is carried three ways, because PRD 15 forbids leaning on colour alone: the fill and
 * the ink move to the amber pair, the outline thickens, and the node is `selectable`, so
 * TalkBack says `selected` without the screen adding anything.
 */
@Composable
fun MueChoiceCard(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: (@Composable () -> Unit)? = null,
    minHeight: Dp = MueChoiceCardDefaults.CompactHeight,
    contentPadding: PaddingValues = MueChoiceCardDefaults.CompactPadding,
    labelStyle: TextStyle = MueChoiceCardDefaults.compactLabelStyle(),
    enabled: Boolean = true,
) {
    val colors = MueTheme.colors
    val shape = MueTheme.shapes.field
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val container by animateColorAsState(
        targetValue = if (selected) colors.accentSoft else colors.surface,
        animationSpec = MueMotion.spec(MueMotion.PresetChangeMillis),
        label = "choiceContainer",
    )
    val border by animateColorAsState(
        targetValue = if (selected) colors.accent else colors.surfaceBorder,
        animationSpec = MueMotion.spec(MueMotion.PresetChangeMillis),
        label = "choiceBorder",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (selected) 2.dp else 1.dp,
        animationSpec = MueMotion.spec(MueMotion.PresetChangeMillis),
        label = "choiceBorderWidth",
    )
    val content by animateColorAsState(
        targetValue = when {
            !enabled -> colors.textQuiet
            selected -> colors.onAccentSoft
            else -> colors.textSecondary
        },
        animationSpec = MueMotion.spec(MueMotion.PresetChangeMillis),
        label = "choiceContent",
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = MueMotion.spec(MueMotion.PressMillis),
        label = "choiceScale",
    )

    CompositionLocalProvider(LocalMueContentColor provides content) {
        Column(
            modifier = modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .heightIn(min = minHeight)
                .clip(shape)
                .background(container)
                .border(borderWidth, border, shape)
                .selectable(
                    selected = selected,
                    enabled = enabled,
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.RadioButton,
                    onClick = onClick,
                )
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
        ) {
            icon?.invoke()
            MueText(
                text = label,
                style = labelStyle,
                color = if (selected) colors.onAccentSoft else colors.textPrimary,
                maxLines = 2,
                modifier = Modifier.padding(top = if (icon != null) MueTheme.spacing.xs else 0.dp),
            )
            description?.let {
                // No ceiling: `Duration, effort and energy` stopped at `and ene…` at the largest
                // font size, so the tile named two of the three things it offers. A fourth line
                // costs a taller tile, which is what a bigger text size asks for.
                MueText(it, MueTheme.typography.caption, color = content)
            }
        }
    }
}

/**
 * Two or three tiles across, all the same height whatever their text runs to.
 *
 * `IntrinsicSize.Min` is deliberately not used: the tallest tile wins through the card's own
 * `minHeight` instead, which costs no extra measure pass on a grid the presets rebuild on every
 * selection.
 */
@Composable
fun MueChoiceRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
        content = content,
    )
}

/**
 * A grid of tiles, as many across as their own words allow and never more than [maxColumns].
 *
 * Three across is 96 dp of a 360 dp screen, and a tile spends 24 dp of that on its padding. At the
 * largest font size `Treadmill` alone wants more than the 72 dp that leaves, so the six presets of
 * `Log activity` and `Start activity` came out **cut in the middle of the word** — `Tread` over
 * `mill …`, `Outd` over `oor …`, `Cycli` over `ng`, `Stren` over `gth …`. Four of the six unusable
 * as words, on the first control of both screens. `onNodeWithText(preset.label)` matched every one
 * of them: the semantics string is the whole label whatever the glyphs do.
 *
 * The labels themselves cannot move — they are the PRD's own words and the shell's tests name them
 * — and shrinking the type answers someone who asked for large text with small text. So the grid
 * gives way instead: it drops to two across, and then to one, until the longest word of the widest
 * label fits the tile it would be drawn in. A tile that can hold its word is worth more than a row
 * that holds three tiles.
 *
 * The threshold is measured, not guessed. Each label is broken into its words and laid out at the
 * grid's own type style, at the current density and font scale, and compared with the tile a given
 * column count would give it, padding removed. No `dp` breakpoint is written down, so a longer
 * word, a denser script or a wider phone each move the answer by themselves — and at the ordinary
 * size the six presets still sit three across, drawn by exactly the [MueChoiceRow]s as before.
 */
@Composable
fun MueChoiceGrid(
    labels: List<String>,
    maxColumns: Int,
    modifier: Modifier = Modifier,
    labelStyle: TextStyle = MueChoiceCardDefaults.compactLabelStyle(),
    contentPadding: PaddingValues = MueChoiceCardDefaults.CompactPadding,
    tile: @Composable RowScope.(index: Int) -> Unit,
) {
    val gap = MueTheme.spacing.sm
    BoxWithConstraints(modifier = modifier) {
        val columns = wholeWordColumns(
            labels = labels,
            maxColumns = maxColumns,
            available = maxWidth,
            gap = gap,
            labelStyle = labelStyle,
            contentPadding = contentPadding,
        )

        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            labels.indices.chunked(columns).forEach { row ->
                MueChoiceRow {
                    row.forEach { index -> tile(index) }
                }
            }
        }
    }
}

/**
 * The largest column count, up to [maxColumns], in which no label has to break a word — and 1 when
 * even a full-width tile cannot hold one, because there is nothing narrower left to try.
 */
@Composable
private fun wholeWordColumns(
    labels: List<String>,
    maxColumns: Int,
    available: Dp,
    gap: Dp,
    labelStyle: TextStyle,
    contentPadding: PaddingValues,
): Int {
    val measurer = rememberTextMeasurer()
    val direction = LocalLayoutDirection.current
    val density = LocalDensity.current
    val padding = contentPadding.calculateStartPadding(direction) +
        contentPadding.calculateEndPadding(direction)

    return remember(measurer, labels, maxColumns, available, gap, labelStyle, padding, density) {
        val longestWord = labels
            .flatMap { it.split(' ') }
            .maxOfOrNull { word -> measurer.measure(word, labelStyle).size.width }
            ?: return@remember maxColumns

        val availablePx = with(density) { available.roundToPx() }
        val gapPx = with(density) { gap.roundToPx() }
        val paddingPx = with(density) { padding.roundToPx() }

        (maxColumns downTo 2).firstOrNull { columns ->
            val tile = (availablePx - gapPx * (columns - 1)) / columns
            tile - paddingPx >= longestWord
        } ?: 1
    }
}

@Preview(name = "Choice cards", showBackground = true, backgroundColor = 0xFF101012, widthDp = 390)
@Composable
private fun MueChoiceCardPreview() {
    MuePreviewHost(padding = 28) {
        val presets = listOf(
            "Treadmill walk", "Outdoor walk", "Run",
            "Cycling", "Strength training", "Other",
        )
        presets.chunked(3).forEach { labels ->
            MueChoiceRow {
                labels.forEach { label ->
                    MueChoiceCard(
                        label = label,
                        selected = label == "Treadmill walk",
                        onClick = {},
                        icon = { MuePreviewIcon(MuePreviewGlyph.DOT, size = 18.dp) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        MueChoiceRow {
            listOf(
                "Quick log" to "Duration, effort and energy",
                "Detailed" to "Exercises, sets and reps",
            ).forEach { (label, description) ->
                MueChoiceCard(
                    label = label,
                    description = description,
                    selected = label == "Quick log",
                    onClick = {},
                    icon = { MuePreviewIcon(MuePreviewGlyph.DOT, size = 20.dp) },
                    minHeight = MueChoiceCardDefaults.TallHeight,
                    contentPadding = MueChoiceCardDefaults.TallPadding,
                    labelStyle = MueChoiceCardDefaults.tallLabelStyle(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
