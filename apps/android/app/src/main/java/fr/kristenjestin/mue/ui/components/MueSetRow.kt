package fr.kristenjestin.mue.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.domain.model.SetMeasure
import fr.kristenjestin.mue.domain.model.TrackingMode
import fr.kristenjestin.mue.ui.theme.LocalReduceMotion
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme

/**
 * One numeric column of a set row.
 *
 * [header] carries the unit, so the cell itself holds a bare number: `62.5`, not `62.5 kg`.
 * That is what keeps a value column inside [MueSetRowMetrics.MinValueColumn] once the delete
 * target is grown to the 48 dp of PRD 15.
 */
enum class MueSetMeasure(val header: String, val accessibleName: String) {
    LOAD("kg", "Load in kilograms"),
    REPETITIONS("Reps", "Repetitions"),
    DURATION("Time", "Duration"),
    EFFORT("Effort", "Perceived effort, 1 to 10"),
    ;

    /** PRD 15 asks for a keyboard suited to the field; only the load takes a decimal. */
    val keyboardType: KeyboardType
        get() = if (this == LOAD) KeyboardType.Decimal else KeyboardType.Number
}

/**
 * Column geometry of the set row, kept out of composition so the arithmetic behind contract
 * §7 can be asserted rather than eyeballed.
 *
 * The prototype's `grid-cols-[26px_1fr_1fr_30px]` survives as a shape but not as a set of
 * numbers: its 30 px delete button is below the 48 dp PRD 15 requires, and growing it eats
 * 18 dp the two value columns have to give up. What does *not* survive is a second trailing
 * action — see [fitsIn], which fails for two actions on a 360 dp phone.
 */
internal object MueSetRowMetrics {

    /** Two digits of set number, centred. The prototype's 26 px, rounded to the 4 dp grid. */
    val NumberColumn: Dp = 24.dp

    /** The prototype's `gap-2`. */
    val ColumnGap: Dp = 8.dp

    /** PRD 15 applies to the row's trailing action exactly as it applies to everything else. */
    val Action: Dp = MueMinTouchTarget

    /**
     * Floor for a value column: five glyphs of `bodyStrong` — `125.5`, `12:30` — plus the
     * cell's own horizontal padding. Below this a plausible reading starts to ellipsise.
     */
    val MinValueColumn: Dp = 64.dp

    fun valueColumnWidth(available: Dp, valueColumns: Int, actionCount: Int): Dp {
        if (valueColumns <= 0) return 0.dp
        val gaps = ColumnGap * (valueColumns + actionCount)
        val fixed = NumberColumn + Action * actionCount
        return ((available - gaps - fixed) / valueColumns).coerceAtLeast(0.dp)
    }

    fun fitsIn(available: Dp, valueColumns: Int, actionCount: Int): Boolean =
        valueColumnWidth(available, valueColumns, actionCount) >= MinValueColumn

    /** Narrowest container the row still lays out in without cramping a value column. */
    fun minimumRowWidth(valueColumns: Int, actionCount: Int): Dp =
        NumberColumn +
            Action * actionCount +
            ColumnGap * (valueColumns + actionCount) +
            MinValueColumn * valueColumns

    /**
     * The columns a mode offers (PRD 9.4). Every mode yields exactly two: the load and the
     * per-set effort are mutually exclusive, which is precisely what contract decision 3 buys.
     */
    fun measuresOf(mode: TrackingMode): List<MueSetMeasure> = buildList {
        if (mode.usesLoad) add(MueSetMeasure.LOAD)
        add(
            when (mode.primary) {
                SetMeasure.REPETITIONS -> MueSetMeasure.REPETITIONS
                SetMeasure.DURATION -> MueSetMeasure.DURATION
            },
        )
        if (mode.showsSetEffort) add(MueSetMeasure.EFFORT)
    }
}

/** One editable cell of a set row. */
@Stable
class MueSetField(
    val measure: MueSetMeasure,
    val value: String,
    val onValueChange: (String) -> Unit,
    val placeholder: String = "",
    val keyboardType: KeyboardType = measure.keyboardType,
)

/** Header strip naming the columns of the rows below it. Shares the geometry of [MueSetRow]. */
@Composable
fun MueSetHeaderRow(
    measures: List<MueSetMeasure>,
    modifier: Modifier = Modifier,
    actionCount: Int = 1,
) {
    val colors = MueTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MueSetRowMetrics.ColumnGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MueText(
            text = "Set",
            style = MueTheme.typography.micro,
            color = colors.textQuiet,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(MueSetRowMetrics.NumberColumn),
        )
        measures.forEach { measure ->
            MueText(
                text = measure.header,
                style = MueTheme.typography.micro,
                color = colors.textQuiet,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
        repeat(actionCount) { Spacer(Modifier.width(MueSetRowMetrics.Action)) }
    }
}

/**
 * One set of one exercise: its number, its two editable measures, and its delete target.
 *
 * The row carries a single trailing action on purpose. `Duplicate last set` is a list-level
 * action ([MueSetListActions]) rather than a second 48 dp column, because two of them push a
 * value column to 56 dp on a 360 dp phone — under the floor a `125.5` needs.
 *
 * [highlighted] is the amber beat PRD 14.2 asks for on a duplicated row. It plays once, on
 * arrival, and not at all when motion is reduced.
 */
@Composable
fun MueSetRow(
    number: Int,
    fields: List<MueSetField>,
    onDelete: () -> Unit,
    deleteIcon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false,
    highlighted: Boolean = false,
    deleteContentDescription: String = "Remove set $number",
) {
    val colors = MueTheme.colors
    val reduceMotion = LocalReduceMotion.current
    val shape = MueTheme.shapes.small

    val halo = remember { Animatable(0f) }
    // Hoisted: the effect below is a suspend block and cannot read composition locals.
    val haloSpec = MueMotion.spec<Float>(MueMotion.SetDuplicateHaloMillis, MueMotion.Exit)
    LaunchedEffect(highlighted, reduceMotion) {
        if (highlighted && !reduceMotion) {
            halo.snapTo(1f)
            halo.animateTo(0f, haloSpec)
        } else {
            halo.snapTo(0f)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .drawBehind {
                val travelled = halo.value
                if (travelled > 0f) {
                    drawRect(color = colors.accent.copy(alpha = travelled * HaloPeakAlpha))
                }
            },
        horizontalArrangement = Arrangement.spacedBy(MueSetRowMetrics.ColumnGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MueText(
            text = number.toString(),
            style = MueTheme.typography.micro,
            color = if (emphasised) colors.accent else colors.textTertiary,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(MueSetRowMetrics.NumberColumn),
        )

        fields.forEach { field ->
            SetValueCell(
                field = field,
                setNumber = number,
                modifier = Modifier.weight(1f),
            )
        }

        MueSetRowAction(
            contentDescription = deleteContentDescription,
            onClick = onDelete,
            icon = deleteIcon,
        )
    }
}

/**
 * A 48 dp target whose glyph stays as small as the prototype's. The size is the touch area,
 * not the drawing: what the caller puts in [icon] is centred at whatever size it declares.
 */
@Composable
fun MueSetRowAction(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(MueSetRowMetrics.Action)
            .clip(MueTheme.shapes.small)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
        content = { icon() },
    )
}

/**
 * The two set actions of FR-ACTIVITY-009, under the list they act on. `Add set` starts an
 * empty row; `Duplicate last set` repeats the previous one, and is absent while there is no
 * previous one to repeat.
 *
 * The two are *not* given equal halves. `Add set` measures 47 dp and `Duplicate last set`
 * 105 dp, so an even split starves the longer label: on a 360 dp phone it would have 82 dp to
 * say 105 dp in. Sizing the short one to its content and letting the long one take the rest
 * leaves the latter 120 dp on that same phone.
 */
@Composable
fun MueSetListActions(
    onAddSet: () -> Unit,
    modifier: Modifier = Modifier,
    onDuplicateLastSet: (() -> Unit)? = null,
    addIcon: (@Composable () -> Unit)? = null,
    duplicateIcon: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
    ) {
        MueDashedAction(
            label = "Add set",
            onClick = onAddSet,
            modifier = if (onDuplicateLastSet == null) Modifier.weight(1f) else Modifier,
            icon = addIcon,
        )
        onDuplicateLastSet?.let {
            MueDashedAction(
                label = "Duplicate last set",
                onClick = it,
                modifier = Modifier.weight(1f),
                icon = duplicateIcon,
            )
        }
    }
}

/**
 * Opacity of the duplication beat at the instant the row arrives. Low, because the cells above
 * it are themselves translucent: the amber reaches the eye through them as well as around them.
 */
private const val HaloPeakAlpha = 0.16f

@Composable
private fun SetValueCell(
    field: MueSetField,
    setNumber: Int,
    modifier: Modifier = Modifier,
) {
    val colors = MueTheme.colors
    val shape = MueTheme.shapes.small
    var focused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = if (focused) colors.surfaceBorderFocused else colors.surfaceBorder,
        animationSpec = MueMotion.spec(MueMotion.ManualEntryMillis),
        label = "setCellBorder",
    )

    Box(
        modifier = modifier
            .heightIn(min = MueMinTouchTarget)
            .clip(shape)
            .background(colors.surfaceStrong)
            .border(1.dp, borderColor, shape),
        contentAlignment = Alignment.Center,
    ) {
        BasicTextField(
            value = field.value,
            onValueChange = field.onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MueTheme.spacing.sm)
                .onFocusChanged { focused = it.isFocused }
                .semantics {
                    contentDescription = "Set $setNumber, ${field.measure.accessibleName}"
                },
            singleLine = true,
            textStyle = MueTheme.typography.bodyStrong.copy(
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
            ),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(keyboardType = field.keyboardType),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.Center) {
                    if (field.value.isEmpty() && field.placeholder.isNotEmpty()) {
                        MueText(
                            text = field.placeholder,
                            style = MueTheme.typography.bodyStrong,
                            color = colors.textQuiet,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    inner()
                }
            },
        )
    }
}

/**
 * Preview-only stand-in for the Lucide vectors, which land as drawables outside this chunk.
 * Screens pass their own slot built from `ActivityIcons`; nothing here names a resource.
 */
@Immutable
internal enum class MuePreviewGlyph { CROSS, PLUS, COPY, SEARCH, CHEVRON, BACK, DOT }

@Composable
internal fun MuePreviewIcon(
    glyph: MuePreviewGlyph,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
) {
    val label = when (glyph) {
        MuePreviewGlyph.CROSS -> "×"
        MuePreviewGlyph.PLUS -> "+"
        MuePreviewGlyph.COPY -> "⧉"
        MuePreviewGlyph.SEARCH -> "○"
        MuePreviewGlyph.CHEVRON -> "›"
        MuePreviewGlyph.BACK -> "‹"
        MuePreviewGlyph.DOT -> "•"
    }
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        MueText(label, MueTheme.typography.bodyStrong, color = MueTheme.colors.textTertiary)
    }
}

@Preview(name = "Set row — four modes", showBackground = true, backgroundColor = 0xFF101012, widthDp = 390)
@Composable
private fun MueSetRowPreview() {
    MueTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MueTheme.colors.canvas)
                .padding(horizontal = MueTheme.spacing.screenHorizontal, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TrackingMode.entries.forEach { mode ->
                MueSurfaceCard(
                    shape = MueTheme.shapes.field,
                    contentPadding = PaddingValues(MueTheme.spacing.md),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MueText(mode.label, MueTheme.typography.bodyStrong)
                    val measures = MueSetRowMetrics.measuresOf(mode)
                    MueSetHeaderRow(measures)
                    listOf("40", "60", "").forEachIndexed { index, first ->
                        MueSetRow(
                            number = index + 1,
                            emphasised = index == 2,
                            highlighted = index == 2,
                            fields = measures.mapIndexed { column, measure ->
                                MueSetField(
                                    measure = measure,
                                    value = if (column == 0) first else "",
                                    onValueChange = {},
                                    placeholder = "—",
                                )
                            },
                            onDelete = {},
                            deleteIcon = { MuePreviewIcon(MuePreviewGlyph.CROSS) },
                        )
                    }
                    MueSetListActions(
                        onAddSet = {},
                        onDuplicateLastSet = {},
                        addIcon = { MuePreviewIcon(MuePreviewGlyph.PLUS, size = 14.dp) },
                        duplicateIcon = { MuePreviewIcon(MuePreviewGlyph.COPY, size = 14.dp) },
                    )
                }
            }
        }
    }
}
