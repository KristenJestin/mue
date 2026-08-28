package fr.kristenjestin.mue.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueTheme

/** The visible chrome of a step control, which is also its touch target (PRD_FOOD 18). */
private val StepButtonSize: Dp = MueMinTouchTarget

/**
 * The glyphs. Typography and not a vector, exactly as `RulerStepButton` draws them on `Entry`:
 * the app has never imported a Lucide `minus`, and a `−` set in the app's own numeric face is
 * the same mark the reader already meets on the weight screen.
 */
private const val MinusGlyph = "−"
private const val PlusGlyph = "+"

/**
 * A count, with a `−` and a `+` — the app's one stepper.
 *
 * **What it replaces.** Three screens each grew their own pair of chevrons: the add sheet's usual
 * portions, the add sheet's recipe servings (a bare text field, in fact) and the recipe card's
 * displayed servings. The owner's words on the first of them are the reason this file exists —
 * *"le serving qui affiche des carets pour changer de taille, c'est trop chelou. Tu peux pas me
 * faire un faux input avec des + et des − plutôt ?"* — and a caret is genuinely the wrong mark:
 * `chevron-up` is what a *disclosure* uses, so the control read as something that would open
 * rather than something that would count.
 *
 * **Why it is a field and not a row.** It is built on [MueFieldContainer], the same shell every
 * typed value in the app sits in, which is what makes it the "faux input" that was asked for: the
 * same fill, the same border, the same label in the same place, so a count and a typed number
 * look like two of one thing rather than two things. It also inherits that container's answer to
 * a doubled font scale for nothing — the trailing slot is measured through [MueSplitRow], so the
 * two buttons drop under the value instead of squeezing it into a ribbon.
 *
 * **What it does not know.** No bound and no step is written here. The caller hands over a value
 * already rendered and says, through [canDecrement] and [canIncrement], whether each end has been
 * reached; the domain that owns the range — `Servings`, `FoodValidation` — is what answered. A
 * stepper that clamped for itself would be a second copy of a rule that already exists, and the
 * two would drift.
 *
 * **What TalkBack hears.** One value, not two buttons. The readout carries [label] as its name
 * and the value as its `stateDescription`, which is how `MueEffortSlider` publishes its own
 * number and how an adjustable control is expected to read: `Servings, 1.25`. The buttons keep
 * their own names ([decrementLabel], [incrementLabel]) because they are still two distinct
 * actions, and each is [MueMinTouchTarget] on both axes whatever the text size (PRD_FOOD 18).
 */
@Composable
fun MueStepper(
    label: String,
    value: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    decrementLabel: String,
    incrementLabel: String,
    modifier: Modifier = Modifier,
    canDecrement: Boolean = true,
    canIncrement: Boolean = true,
    /** Drawn quiet while the count is not yet a count — PRD 12's rule against a false number. */
    isValueSet: Boolean = true,
    isError: Boolean = false,
    valueTestTag: String? = null,
    decrementTestTag: String? = null,
    incrementTestTag: String? = null,
) {
    val colors = MueTheme.colors
    MueFieldContainer(
        label = label,
        modifier = modifier.fillMaxWidth(),
        isError = isError,
        // Tighter than a text field's: the buttons already carry their own 48 dp of height, and
        // the container's own vertical padding on top of them would make the row overbearing.
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        trailing = {
            StepButton(
                glyph = MinusGlyph,
                description = decrementLabel,
                enabled = canDecrement,
                testTag = decrementTestTag,
                onClick = onDecrement,
            )
            StepButton(
                glyph = PlusGlyph,
                description = incrementLabel,
                enabled = canIncrement,
                testTag = incrementTestTag,
                onClick = onIncrement,
            )
        },
    ) {
        /*
         * The value states itself as a value. `contentDescription` names the quantity and
         * `stateDescription` carries what it currently is, so a reader lands on one node that
         * says `Servings, 1.25` rather than on a bare number between two unexplained buttons.
         *
         * Never capped: a portion label is `1.5 × 1 apple`, and half of that is a different
         * quantity rather than a shorter one.
         */
        MueText(
            text = value,
            style = MueTheme.typography.fieldValue,
            color = if (isValueSet) colors.textPrimary else colors.textQuiet,
            modifier = Modifier
                .then(valueTestTag?.let { Modifier.testTag(it) } ?: Modifier)
                .semantics {
                    contentDescription = label
                    stateDescription = value
                },
        )
    }
}

/**
 * One end of the stepper.
 *
 * The click is published through semantics rather than left to `clickable` alone so that the
 * disabled state is *announced* as well as drawn — a control that has reached its bound should
 * say so, not merely stop responding.
 */
@Composable
private fun RowScope.StepButton(
    glyph: String,
    description: String,
    enabled: Boolean,
    testTag: String?,
    onClick: () -> Unit,
) {
    val colors = MueTheme.colors
    Box(
        modifier = Modifier
            .size(StepButtonSize)
            .background(colors.surfaceStrong, CircleShape)
            .border(1.dp, colors.surfaceBorder, CircleShape)
            .then(testTag?.let { Modifier.testTag(it) } ?: Modifier)
            .semantics {
                role = Role.Button
                contentDescription = description
                if (enabled) {
                    onClick {
                        onClick()
                        true
                    }
                } else {
                    disabled()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        MueText(
            text = glyph,
            style = MueTheme.typography.metricMedium,
            color = if (enabled) colors.accent else colors.textQuiet,
        )
    }
}

@Preview(name = "Stepper", showBackground = true, backgroundColor = 0xFF101012, widthDp = 390)
@Composable
private fun MueStepperPreview() {
    MuePreviewHost(padding = 28) {
        MueStepper(
            label = "Servings",
            value = "1",
            onDecrement = {},
            onIncrement = {},
            decrementLabel = "One quarter serving fewer",
            incrementLabel = "One quarter serving more",
            canDecrement = false,
        )
        MueStepper(
            label = "Usual portions",
            value = "1.5 × 1 apple",
            onDecrement = {},
            onIncrement = {},
            decrementLabel = "One portion fewer",
            incrementLabel = "One portion more",
        )
    }
}

/**
 * The stepper on the narrowest phone at the largest text size.
 *
 * What to look for: the two buttons **under** the value rather than beside it, both still round
 * and both still 48 dp. That is [MueSplitRow] doing what it was lifted out of the Food module to
 * do; a plain `Row` with a weight would instead hand the value a ribbon and break `1.5 × 1 apple`
 * in the middle of a word.
 */
@Preview(
    name = "Stepper · 360 dp · largest font",
    showBackground = true,
    backgroundColor = 0xFF101012,
    widthDp = 360,
    fontScale = 2.0f,
)
@Composable
private fun MueStepperLargeTextPreview() {
    MuePreviewHost(padding = 16) {
        MueStepper(
            label = "Usual portions",
            value = "1.5 × 1 apple",
            onDecrement = {},
            onIncrement = {},
            decrementLabel = "One portion fewer",
            incrementLabel = "One portion more",
        )
    }
}
