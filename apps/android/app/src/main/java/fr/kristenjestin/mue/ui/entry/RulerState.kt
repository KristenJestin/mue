package fr.kristenjestin.mue.ui.entry

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import fr.kristenjestin.mue.domain.model.Weight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Where the scale currently is, in hundredths of a kilogram, and how it gets there.
 *
 * A drag delivers a pointer event every few milliseconds. Routing each of them through the
 * ViewModel would rebuild the whole Entry tree between the finger and the pixels, which is
 * precisely the perceptible lag PRD 16.2 forbids — so the live position stops here. Two
 * snapshot values leave this object and each has exactly one reader: [positionHundredths] is
 * read from the ruler's *draw* scope, so movement costs a redraw and never a recomposition,
 * and [displayedHundredths] is read by the hero readout alone, which is the only other thing
 * on the screen that has to follow the finger. The ViewModel is told once, when the scale
 * stops.
 *
 * The physics itself lives in [RulerPhysics]; this class only sequences it.
 */
@Stable
class RulerState internal constructor(
    initialHundredths: Int,
    private val scope: CoroutineScope,
) {

    /** Continuous position under the marker. Read from the draw phase only. */
    var positionHundredths by mutableFloatStateOf(initialHundredths.toFloat())
        private set

    /** The nearest valid 0.05 kg: what the readout shows while the ruler is still moving. */
    var displayedHundredths by mutableIntStateOf(initialHundredths)
        private set

    /** True from touch-down until the scale has come to rest, inertia and magnetism included. */
    var interacting by mutableStateOf(false)
        private set

    /**
     * True while [glideTo] is carrying the ruler towards a value nobody's finger chose.
     *
     * Read by the screen for one purpose, and it is not decoration: the save action publishes
     * [displayedHundredths] back to the ViewModel so a press landed mid-glide records what is on
     * screen. Mid-*glide*, that same reflex would publish an intermediate value — which would
     * both save the wrong weight and read as a manual correction, stripping the provenance and
     * the impedance of the measurement being saved (BR-SCALE-013). During a glide the screen's
     * own weight is already the destination, so there is nothing to publish.
     *
     * Distinct from [interacting] on purpose: a glide must not suppress the digit roll nor block
     * the screen from following later orders, which is exactly what [interacting] does.
     */
    var gliding by mutableStateOf(false)
        private set

    private val decay: DecayAnimationSpec<Float> = exponentialDecay(
        frictionMultiplier = RulerPhysics.FLING_FRICTION_MULTIPLIER,
        absVelocityThreshold = RulerPhysics.FLING_VELOCITY_THRESHOLD,
    )

    /** Critically damped: the magnetism pulls, it never bounces (PRD FR-ENTRY-002). */
    private val settleSpec: SpringSpec<Float> = spring(
        dampingRatio = RulerPhysics.SETTLE_DAMPING_RATIO,
        stiffness = RulerPhysics.SETTLE_STIFFNESS,
        visibilityThreshold = RulerPhysics.SETTLE_VISIBILITY_THRESHOLD,
    )

    private var motion: Job? = null

    /**
     * Bumped whenever the current movement is abandoned.
     *
     * Cancelling a coroutine only takes effect at its next dispatch, so a settle that has just
     * been interrupted still gets to run its cleanup — one frame *after* the touch that
     * interrupted it. Without this the finger would land on a scale that immediately declared
     * itself idle again.
     */
    private var generation: Int = 0

    /** PRD FR-ENTRY-002: a new touch interrupts the inertia immediately. */
    fun onDragStart() {
        stopMotion()
        interacting = true
    }

    fun onDrag(dragPx: Float, pixelsPerHundredth: Float) {
        moveTo(positionHundredths + RulerPhysics.dragToHundredths(dragPx, pixelsPerHundredth))
    }

    /**
     * Runs the inertia and the magnetism, then hands the landing value to [onRest].
     *
     * [onRest] is the *only* moment the rest of the app hears about a drag. Publishing every
     * frame instead would put a ViewModel round trip and a recomposition of the screen on the
     * hot path of the one gesture that has to track the finger exactly.
     */
    fun onDragEnd(velocityHundredthsPerSecond: Float, allowFling: Boolean, onRest: (Int) -> Unit) {
        val gesture = generation
        motion = scope.launch {
            try {
                settle(velocityHundredthsPerSecond, allowFling)
            } finally {
                if (gesture == generation) interacting = false
            }
            onRest(displayedHundredths)
        }
    }

    /** An order from outside the scale: the history seed, `−` / `+`, the keyboard, TalkBack. */
    fun jumpTo(hundredths: Int) {
        stopMotion()
        interacting = false
        moveTo(hundredths.toFloat())
    }

    /**
     * The same order, travelled rather than teleported (PRD_SCALE 19).
     *
     * A weigh-in arriving from the scale is the one value the user did not put there, and
     * PRD_SCALE 19 asks the ruler to *move* to it so the eye can follow where it came from.
     * Everything else — the history seed, `−` / `+`, the keyboard — still lands through
     * [jumpTo]: those are the user's own doing and travelling to them would only add lag to a
     * gesture. Under reduced motion the caller uses [jumpTo] instead, which is that section's
     * "changement direct de valeur".
     *
     * Interruptible like every other movement here (PRD 13): a touch calls [onDragStart], which
     * bumps the generation and cancels this animation where it stands.
     */
    fun glideTo(hundredths: Int, spec: AnimationSpec<Float>) {
        stopMotion()
        interacting = false
        val order = generation
        gliding = true
        motion = scope.launch {
            try {
                AnimationState(positionHundredths).animateTo(hundredths.toFloat(), spec) {
                    moveTo(value)
                }
            } finally {
                // Same guard as `onDragEnd`: a cancelled coroutine still runs its cleanup, one
                // frame after the touch that cancelled it, and must not undo that touch's state.
                if (order == generation) gliding = false
            }
        }
    }

    private fun stopMotion() {
        generation++
        motion?.cancel()
        motion = null
        gliding = false
    }

    private fun moveTo(hundredths: Float) {
        val clamped = RulerPhysics.clampPosition(hundredths)
        positionHundredths = clamped
        displayedHundredths = RulerPhysics.snapToStep(clamped)
    }

    /**
     * The short glide of PRD FR-ENTRY-002 followed by the pull onto the nearest 0.05 kg.
     *
     * Clamping inside the decay is what makes 30.0 and 250.0 dead stops: the moment the
     * projected value leaves the range the animation is cancelled where it stands, which is
     * already an exact step, so no rebound and no second animation follow.
     */
    private suspend fun settle(velocityHundredthsPerSecond: Float, allowFling: Boolean) {
        if (allowFling && RulerPhysics.isFlingWorthwhile(velocityHundredthsPerSecond)) {
            AnimationState(positionHundredths, velocityHundredthsPerSecond).animateDecay(decay) {
                moveTo(value)
                if (value != positionHundredths) cancelAnimation()
            }
        }
        val target = displayedHundredths.toFloat()
        if (positionHundredths != target) {
            AnimationState(positionHundredths).animateTo(target, settleSpec) { moveTo(value) }
        }
    }
}

/**
 * The scale's position, kept across recompositions and tied to the screen's own scope.
 *
 * Seeded once: afterwards the value belongs to the user for the session (PRD FR-ENTRY-001)
 * and every later change arrives through [RulerState.jumpTo].
 */
@Composable
fun rememberRulerState(initial: Weight): RulerState {
    val scope = rememberCoroutineScope()
    return remember(scope) { RulerState(initial.hundredthsKg, scope) }
}
