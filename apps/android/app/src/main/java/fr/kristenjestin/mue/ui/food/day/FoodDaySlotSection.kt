package fr.kristenjestin.mue.ui.food.day

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.ui.activity.ActivityIcons
import fr.kristenjestin.mue.ui.components.MueDivider
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MueIcons
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
 */
@Composable
private fun SlotHeading(state: FoodDaySlotUiState) {
    val colors = MueTheme.colors
    val type = MueTheme.typography

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MueIcon(iconName = state.iconName, tint = colors.textTertiary, size = 14.dp)

        MueText(
            // Locale-independent, for the reason `Food.fold` gives: a Turkish device would
            // otherwise turn the `i` of `Dinner` into a dotted capital.
            text = state.label.uppercase(Locale.ROOT),
            style = type.eyebrow,
            color = colors.textTertiary,
            modifier = Modifier
                .weight(1f)
                .padding(start = MueTheme.spacing.sm)
                .clearAndSetSemantics {
                    contentDescription = state.description
                    heading()
                },
        )

        state.totalLabel?.let { total ->
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
        }
    }
}

/**
 * One journal line (PRD_FOOD 10.2), whichever of the three forms it takes.
 *
 * Nothing is truncated. A food's name runs to 80 characters (PRD_FOOD 15), and a `maxLines = 1`
 * here would ellipsise it into something that still satisfies every assertion — the semantics
 * string stays the whole name whatever the glyphs do — while reading wrong on the phone. A long
 * name therefore makes a taller card, which is the honest outcome and the one that survives a
 * doubled font scale.
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

            Column(
                modifier = Modifier.weight(1f).padding(horizontal = spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.xxs),
            ) {
                MueText(state.title, type.bodyStrong)
                FactRow(facts = listOfNotNull(state.timeLabel, state.amountLabel))
            }

            Column(horizontalAlignment = Alignment.End) {
                MueText(state.energyLabel, type.bodyStrong)
                MueText(state.proteinLabel, type.micro, color = colors.textTertiary)
            }
        }
    }
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

        Row(modifier = Modifier.fillMaxWidth()) {
            PlanAction(
                label = FoodDayMessages.I_ATE_THIS,
                iconName = MueIcons.CHECK,
                tint = colors.accent,
                testTag = FoodTestTags.confirmPlan(state.key.slot),
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
            )
            PlanAction(
                label = FoodDayMessages.SWAP,
                iconName = MueIcons.ROTATE_CW,
                tint = colors.textSecondary,
                testTag = FoodTestTags.swapPlan(state.key.slot),
                onClick = onSwap,
                modifier = Modifier.weight(1f),
            )
            PlanAction(
                label = FoodDayMessages.DISMISS,
                iconName = MueIcons.CLOSE,
                tint = colors.textSecondary,
                testTag = FoodTestTags.dismissPlan(state.key.slot),
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** One of the proposal's three actions, at the 48 dp PRD_FOOD 18 requires of every target. */
@Composable
private fun PlanAction(
    label: String,
    iconName: String,
    tint: Color,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .heightIn(min = MueMinTouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = MueTheme.spacing.xs)
            .testTag(testTag),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MueIcon(iconName = iconName, tint = tint, size = 14.dp)
        MueText(
            text = label,
            style = MueTheme.typography.chip,
            color = tint,
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
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = spacing.md, vertical = spacing.sm)
            .testTag(FoodTestTags.addToSlot(state.slot))
            // The words alone do not say which moment they would add to.
            .announcedAs("${state.addLabel}, ${state.label}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(AddTileSize)
                .clip(MueTheme.shapes.small)
                .background(colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            MueIcon(iconName = ActivityIcons.PLUS, tint = colors.textTertiary, size = 14.dp)
        }
        MueText(
            text = state.addLabel,
            style = MueTheme.typography.caption,
            color = colors.textTertiary,
            modifier = Modifier.padding(start = spacing.md),
        )
    }
}

/** The time and the quantity of a line, separated by the prototype's small bullet. */
@Composable
private fun FactRow(facts: List<String>, modifier: Modifier = Modifier) {
    val colors = MueTheme.colors
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        facts.forEachIndexed { index, fact ->
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
