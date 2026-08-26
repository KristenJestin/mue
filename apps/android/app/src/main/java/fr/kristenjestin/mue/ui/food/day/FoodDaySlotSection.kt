package fr.kristenjestin.mue.ui.food.day

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.ui.activity.ActivityIcons
import fr.kristenjestin.mue.ui.components.MueDivider
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MueIcons
import fr.kristenjestin.mue.ui.components.MueSplitRow
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueTheme
import java.util.Locale

/** The prototype's glyph tile, grown to the touch minimum of PRD_FOOD 18. */
private val IconTileSize: Dp = MueMinTouchTarget

/** The small tile of the add row: decoration inside a target, not a target of its own. */
private val AddTileSize: Dp = 32.dp

/** The glyph in front of one of a proposal's actions. */
private val PlanActionIconSize: Dp = 14.dp

/**
 * Everything an action spends on room other than its own glyphs: the padding either side of it,
 * and the gap between its glyph and its word. Kept beside them so the two cannot drift.
 */
private val PlanActionGutter: Dp = 12.dp

/** The bullet the prototype sets between two facts of one line. */
private val FactBulletSize: Dp = 3.dp

/** PRD_FOOD 19: a proposal is outlined rather than filled, and the outline is dashed. */
private val DashOn: Dp = 6.dp
private val DashOff: Dp = 4.dp
private val DashWidth: Dp = 1.dp

/** PRD_FOOD 19: the outline is present, and quiet. */
private const val PlanOutlineAlpha = 0.4f

/**
 * One of the four moments (PRD_FOOD 10.1), in the order that section fixes: the unconfirmed
 * proposal if there is one, then the lines sorted by time, then an add button that is always
 * there.
 *
 * The moment's own total sits in the heading and appears only once the moment holds a line.
 * PRD_FOOD 10.4 forbids inventing one, so an empty breakfast shows **no total at all** — not a
 * zero, and not a dash either. Three facts, three readings: nothing logged, a known zero, and
 * an unknown.
 */
@Composable
internal fun FoodDaySlotSection(
    state: FoodDaySlotUiState,
    onAdd: () -> Unit,
    onEditEntry: (FoodLogEntryId) -> Unit,
    onConfirmPlan: (MealPlanKey) -> Unit,
    onSwapPlan: (MealPlanKey) -> Unit,
    onDismissPlan: (MealPlanKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().testTag(FoodTestTags.slot(state.slot)),
        verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
    ) {
        SlotHeading(state)

        state.plan?.let { plan ->
            PlanCard(
                state = plan,
                onConfirm = { onConfirmPlan(plan.key) },
                onSwap = { onSwapPlan(plan.key) },
                onDismiss = { onDismissPlan(plan.key) },
            )
        }

        state.entries.forEach { entry ->
            FoodDayEntryCard(state = entry, onClick = { onEditEntry(entry.id) })
        }

        AddToSlotRow(state = state, onClick = onAdd)
    }
}

/**
 * The moment's name, its glyph and its own total.
 *
 * PRD_FOOD 18 asks for a moment and its total to be heard as one thing rather than as three
 * loose fragments, so the whole announcement is spelled once on the heading and the figures
 * beside it are silenced. `≈` and `—` are drawings; [FoodDayFormat.spoken] is what they sound
 * like, and an unknown says "unknown" rather than being skipped as punctuation.
 *
 * The total keeps its handle and its glyphs through that silence, which is what lets a test read
 * what a reader actually sees rather than what a semantics string claims.
 *
 * The name and the total are split by [EntryCardBody], for the reason that layout exists: a
 * plain `Row` measures the unweighted total **first and at whatever width it asks for**, and at
 * a doubled font scale `≈ 370 kcal` over `≈ 29.1 g protein` left `BREAKFAST` a ribbon it could
 * only fit by cutting the word in half — `BREAKF` over `AST`, one moment reading as two. The
 * journal line under it had already been given this fix; the heading above it had not, and
 * `onNodeWithText("Breakfast")` could not tell, because the semantics string is
 * [FoodDaySlotUiState.description] whatever the glyphs do.
 *
 * A moment with nothing in it has no total to place, and PRD_FOOD 10.4 forbids inventing one, so
 * that heading stays the single row it always was and keeps the whole width for its name.
 */
@Composable
private fun SlotHeading(state: FoodDaySlotUiState) {
    val colors = MueTheme.colors
    val type = MueTheme.typography

    val name: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MueIcon(iconName = state.iconName, tint = colors.textTertiary, size = 14.dp)

            MueText(
                // Locale-independent, for the reason `Food.fold` gives: a Turkish device would
                // otherwise turn the `i` of `Dinner` into a dotted capital.
                text = state.label.uppercase(Locale.ROOT),
                style = type.eyebrow,
                color = colors.textTertiary,
                modifier = Modifier
                    .padding(start = MueTheme.spacing.sm)
                    .clearAndSetSemantics {
                        contentDescription = state.description
                        heading()
                    },
            )
        }
    }

    val total = state.totalLabel
    if (total == null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            name()
        }
        return
    }

    EntryCardBody(
        modifier = Modifier.fillMaxWidth(),
        gap = MueTheme.spacing.sm,
        name = name,
        figures = {
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier
                    .testTag(FoodTestTags.slotTotal(state.slot))
                    /*
                     * Silent, because the heading beside it already says this total out loud and
                     * PRD_FOOD 18 wants one announcement rather than two. Hidden rather than
                     * cleared, so the drawn strings stay in the semantics tree and a test can
                     * still read exactly what a reader sees — which is the only way to prove a
                     * `—` has not become a `0` on the way to the glass.
                     */
                    .semantics { hideFromAccessibility() },
            ) {
                MueText(total, type.bodyStrong, color = colors.textPrimary)
                state.proteinLabel?.let {
                    MueText(it, type.micro, color = colors.textTertiary)
                }
            }
        },
    )
}

/**
 * One journal line (PRD_FOOD 10.2), whichever of the three forms it takes.
 *
 * Nothing is truncated. A food's name runs to 80 characters (PRD_FOOD 15), and a `maxLines = 1`
 * here would ellipsise it into something that still satisfies every assertion — the semantics
 * string stays the whole name whatever the glyphs do — while reading wrong on the phone. A long
 * name therefore makes a taller card, which is the honest outcome and the one that survives a
 * doubled font scale.
 *
 * Surviving it is [EntryCardBody]'s job, not the title's: the figures on the right are what
 * decide how much width the name is left with, so that is where the doubled scale is handled.
 */
@Composable
internal fun FoodDayEntryCard(
    state: FoodDayEntryUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MueTheme.colors
    val type = MueTheme.typography
    val spacing = MueTheme.spacing

    MueSurfaceCard(
        modifier = modifier.testTag(FoodTestTags.logEntry(state.id.value)),
        shape = MueTheme.shapes.field,
        contentPadding = PaddingValues(spacing.md),
        onClick = onClick,
        onClickLabel = FoodDayMessages.EDIT_ENTRY,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().announcedAs(state.description),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(IconTileSize)
                    .clip(MueTheme.shapes.field)
                    .background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                // Decorative: the title right beside it names the line.
                MueIcon(iconName = state.iconName, tint = colors.onAccentSoft, size = 18.dp)
            }

            EntryCardBody(
                modifier = Modifier.weight(1f).padding(start = spacing.md),
                gap = spacing.md,
                name = {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                        MueText(state.title, type.bodyStrong)
                        FactRow(facts = listOfNotNull(state.timeLabel, state.amountLabel))
                    }
                },
                figures = {
                    Column(horizontalAlignment = Alignment.End) {
                        MueText(state.energyLabel, type.bodyStrong)
                        MueText(state.proteinLabel, type.micro, color = colors.textTertiary)
                    }
                },
            )
        }
    }
}

/**
 * The two halves of a journal line — the name with its facts, and the energy figures — side by
 * side while both can be read, stacked once they cannot.
 *
 * A plain `Row` is what broke at a doubled font scale. The figures carry no weight, so the row
 * measures them **first and at whatever width they ask for**: `≈ 29.1 g protein` at scale 2.0
 * wants some 430 px of a 1080 px screen, and the `weight(1f)` name is handed the ~215 px that
 * are left. At that width `Golden chicken grain bowl…` breaks *mid-word* over seventeen lines,
 * and [FactRow]'s quantity is squeezed to one glyph per line before ellipsising — `1 × serving`
 * drawn as a vertical column of letters. Every assertion still passed: the semantics string is
 * the whole name however the glyphs fall.
 *
 * The measured split that answers it is [MueSplitRow], where its own reasoning is written down.
 * This name is kept because the card and the moment's heading above it now share that layout,
 * and only one of the two is a journal line.
 */
@Composable
private fun EntryCardBody(
    name: @Composable () -> Unit,
    figures: @Composable () -> Unit,
    gap: Dp,
    modifier: Modifier = Modifier,
) {
    MueSplitRow(start = name, end = figures, gap = gap, modifier = modifier)
}

/**
 * The unconfirmed proposal at the head of a moment (PRD_FOOD 12 and 19).
 *
 * Dashed outline, near-transparent fill, no glyph tile: it has to read as secondary "sans lire
 * le texte". PRD_FOOD 18 then refuses to let colour carry that alone, which is what the
 * `Suggested` word above the name is for.
 *
 * It shows no energy on purpose. A proposal "n'entre dans aucun total tant qu'elle n'est pas
 * confirmée", and a figure printed beside the real lines would invite the eye to add it in.
 */
@Composable
private fun PlanCard(
    state: FoodDayPlanUiState,
    onConfirm: () -> Unit,
    onSwap: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MueTheme.colors
    val type = MueTheme.typography
    val spacing = MueTheme.spacing
    val shape = MueTheme.shapes.field

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(FoodTestTags.plan(state.key.slot))
            .clip(shape)
            .background(colors.accentSoft)
            .dashedOutline(shape, colors.accent),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.md)
                .announcedAs(state.description),
            verticalArrangement = Arrangement.spacedBy(spacing.xxs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MueIcon(
                    // The prototype's `sparkles`, already imported for the app's other hints.
                    iconName = ActivityIcons.SPARKLES,
                    tint = colors.accent,
                    size = 12.dp,
                )
                MueText(
                    text = FoodDayMessages.SUGGESTED.uppercase(Locale.ROOT),
                    style = type.eyebrow,
                    color = colors.accent,
                    modifier = Modifier.padding(start = spacing.xs),
                )
            }
            MueText(state.recipeName, type.bodyStrong)
            MueText(state.servingsLabel, type.micro, color = colors.textTertiary)
        }

        MueDivider()

        PlanActions(
            actions = listOfNotNull(
                /*
                 * PRD_FOOD 12's first action, and the only one of the three that writes a journal
                 * line — so the only one PRD_FOOD 22 can refuse. On a proposal for Thursday it is
                 * not there at all: a card cannot ask whether you ate Thursday's dinner, and the
                 * day's own note above has already said why. `Swap` and `Dismiss` are unaffected,
                 * because replacing and freeing a moment ahead are exactly what planning is.
                 */
                PlanActionSpec(
                    label = FoodDayMessages.I_ATE_THIS,
                    iconName = MueIcons.CHECK,
                    tint = colors.accent,
                    testTag = FoodTestTags.confirmPlan(state.key.slot),
                    onClick = onConfirm,
                ).takeIf { state.canConfirm },
                PlanActionSpec(
                    label = FoodDayMessages.SWAP,
                    iconName = MueIcons.ROTATE_CW,
                    tint = colors.textSecondary,
                    testTag = FoodTestTags.swapPlan(state.key.slot),
                    onClick = onSwap,
                ),
                PlanActionSpec(
                    label = FoodDayMessages.DISMISS,
                    iconName = MueIcons.CLOSE,
                    tint = colors.textSecondary,
                    testTag = FoodTestTags.dismissPlan(state.key.slot),
                    onClick = onDismiss,
                ),
            ),
        )
    }
}

/** One of the proposal's three actions, before it knows whether it will sit in a row or a column. */
private data class PlanActionSpec(
    val label: String,
    val iconName: String,
    val tint: Color,
    val testTag: String,
    val onClick: () -> Unit,
)

/**
 * The proposal's three actions, abreast while all three can be read whole and stacked once they
 * cannot.
 *
 * A third of 360 dp is 120 dp, and at a doubled font scale `Dismiss` alone wants more than the
 * 94 dp that leaves beside its glyph: the word came out **cut in its middle**, `Dismi` over
 * `ss`, on the one control that destroys a proposal. `onNodeWithText(DISMISS)` never saw it —
 * the semantics string is the whole word however the glyphs fall.
 *
 * Narrowing the type was the rejected alternative, for [MueBottomBar]'s reason: someone who sets
 * the largest font is asking for large text, and answering with smaller text punishes exactly
 * the reader who asked. Dropping the labels for glyphs alone was rejected too — a check, a
 * rotation and a cross say nothing about which of them is irreversible.
 *
 * So the row gives way instead, and each action takes the full width of the card. The threshold
 * is measured rather than guessed: every label is laid out at this row's own type style, at the
 * current density and font scale, and the widest is compared with a third of the width less what
 * the glyph and the gaps beside it take. No `dp` breakpoint is written anywhere, so a longer
 * word, a denser script or a wider phone each move the threshold by themselves — and at the
 * ordinary scale the three still sit abreast, drawn by the very same `Row` as before.
 *
 * [MueBottomBar]: fr.kristenjestin.mue.ui.components.MueBottomBar
 */
@Composable
private fun PlanActions(actions: List<PlanActionSpec>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        if (planLabelsFitAbreast(actions, share = maxWidth / actions.size.coerceAtLeast(1))) {
            Row(modifier = Modifier.fillMaxWidth()) {
                actions.forEach { action -> PlanAction(action, Modifier.weight(1f)) }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                actions.forEach { action -> PlanAction(action, Modifier.fillMaxWidth()) }
            }
        }
    }
}

/**
 * Whether every label can be drawn whole in [share] of the row, glyph and gaps included.
 *
 * The widest label decides for all three: a row with two words and one broken one would still be
 * a row with a broken word in it.
 */
@Composable
private fun planLabelsFitAbreast(actions: List<PlanActionSpec>, share: Dp): Boolean {
    val measurer = rememberTextMeasurer()
    val style = MueTheme.typography.chip
    val room = with(LocalDensity.current) {
        (share - PlanActionGutter - PlanActionIconSize).roundToPx()
    }

    return remember(measurer, actions, style, room) {
        room > 0 && actions.all { action ->
            measurer.measure(action.label, style, maxLines = 1).size.width <= room
        }
    }
}

/** One of the proposal's three actions, at the 48 dp PRD_FOOD 18 requires of every target. */
@Composable
private fun PlanAction(action: PlanActionSpec, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .heightIn(min = MueMinTouchTarget)
            .clickable(role = Role.Button, onClick = action.onClick)
            .padding(horizontal = MueTheme.spacing.xs)
            .testTag(action.testTag),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MueIcon(iconName = action.iconName, tint = action.tint, size = PlanActionIconSize)
        MueText(
            text = action.label,
            style = MueTheme.typography.chip,
            color = action.tint,
            modifier = Modifier.padding(start = MueTheme.spacing.xs),
        )
    }
}

/**
 * PRD_FOOD 10.1: "un bouton d'ajout toujours présent", and PRD_FOOD 17's empty state in one.
 *
 * A moment with nothing in it is not an error and says nothing about what is missing; it simply
 * offers the way in. The words change once the moment holds something, which is the prototype's
 * own distinction between `Add what you ate` and `Add something else`.
 */
@Composable
private fun AddToSlotRow(
    state: FoodDaySlotUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MueTheme.colors
    val spacing = MueTheme.spacing
    val shape = MueTheme.shapes.field

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MueMinTouchTarget)
            .clip(shape)
            .border(BorderStroke(1.dp, colors.hairline), shape)
            /*
             * PRD_FOOD 22: on a day still to come the row keeps its place and stops being a
             * control. `enabled = false` rather than a missing `clickable`, so the node is still
             * announced as a disabled button instead of quietly becoming a paragraph — a reader
             * who reaches it hears that it is there and cannot be used, which is the fact.
             */
            .clickable(enabled = state.canAdd, role = Role.Button, onClick = onClick)
            .padding(horizontal = spacing.md, vertical = spacing.sm)
            .testTag(FoodTestTags.addToSlot(state.slot))
            // The words alone do not say which moment they would add to.
            .announcedAs("${state.addLabel}, ${state.label}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The `+` is the promise of a line, so it goes where no line may be written.
        if (state.canAdd) {
            Box(
                modifier = Modifier
                    .size(AddTileSize)
                    .clip(MueTheme.shapes.small)
                    .background(colors.surface),
                contentAlignment = Alignment.Center,
            ) {
                MueIcon(iconName = ActivityIcons.PLUS, tint = colors.textTertiary, size = 14.dp)
            }
        }
        MueText(
            text = state.addLabel,
            style = MueTheme.typography.caption,
            color = if (state.canAdd) colors.textTertiary else colors.textQuiet,
            modifier = Modifier.padding(start = if (state.canAdd) spacing.md else 0.dp),
        )
    }
}

/**
 * The time and the quantity of a line, separated by the prototype's small bullet.
 *
 * A `Row` measures each fact in turn out of what is left, so the last one takes the shortfall
 * alone: at a doubled font scale `225 g` became `2 2 …` down the side of the card while the time
 * beside it stayed whole. A fact is either drawn or it is not — half of one is a misreading, and
 * `2 2 …` is not a weight.
 *
 * A flow row makes the shortfall a line break instead. Each fact carries its own bullet, so a
 * fact that will not fit moves to the next line *with* the mark that separates it, and never
 * leaves a bullet stranded at the end of a line. At one line — every line at the ordinary scale
 * — this draws exactly what the `Row` drew.
 */
@Composable
private fun FactRow(facts: List<String>, modifier: Modifier = Modifier) {
    val colors = MueTheme.colors
    FlowRow(modifier = modifier) {
        facts.forEachIndexed { index, fact ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = MueTheme.spacing.sm)
                            .size(FactBulletSize)
                            .clip(MueTheme.shapes.pill)
                            .background(colors.textQuiet),
                    )
                }
                MueText(fact, MueTheme.typography.micro, color = colors.textTertiary)
            }
        }
    }
}

/**
 * The dashed contour of PRD_FOOD 19, drawn from the very [Shape] the card is clipped to, so the
 * outline and the fill can never disagree about a corner.
 */
@Composable
private fun Modifier.dashedOutline(shape: Shape, color: Color): Modifier {
    val density = LocalDensity.current
    val dash = with(density) { floatArrayOf(DashOn.toPx(), DashOff.toPx()) }
    val width = with(density) { DashWidth.toPx() }
    return drawBehind {
        drawOutline(
            outline = shape.createOutline(size, layoutDirection, this),
            brush = SolidColor(color),
            alpha = PlanOutlineAlpha,
            style = Stroke(width = width, pathEffect = PathEffect.dashPathEffect(dash, 0f)),
        )
    }
}
