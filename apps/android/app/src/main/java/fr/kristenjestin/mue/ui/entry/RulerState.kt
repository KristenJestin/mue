package fr.kristenjestin.mue.ui.entry

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
 * Where the scale currently is, in tenths of a kilogram, and how it gets there.
 *
 * A drag delivers a pointer event every few milliseconds. Routing each of them through the
 * ViewModel would rebuild the whole Entry tree between the finger and the pixels, which is
 * precisely the perceptible lag PRD 16.2 forbids — so the live position stops here. Two
 * snapshot values leave this object and each has exactly one reader: [positionTenths] is read
 * from the ruler's *draw* scope, so movement costs a redraw and never a recomposition, and
 * [displayedTenths] is read by the hero readout alone, which is the only other thing on the
 * screen that has to follow the finger. The ViewModel is told once, when the scale stops.
 *
 * The physics itself lives in [RulerPhysics]; this class only sequences it.
 */
@Stable
class RulerState internal constructor(
    initialTenths: Int,
    private val scope: CoroutineScope,
) {

    /** Continuous position under the marker. Read from the draw phase only. */
    var positionTenths by mutableFloatStateOf(initialTenths.toFloat())
        private set

    /** The nearest valid tenth: what the readout shows while the ruler is still moving. */
    var displayedTenths by mutableIntStateOf(initialTenths)
        private set

    /** True from touch-down until the scale has come to rest, inertia and magnetism included. */
    var interacting by mutableStateOf(false)
        private set

    private val decay: DecayAnimationSpec<Float> = exponentialDecay(
        frictionMultiplier = RulerPhysics.FLING_FRICTION_MULTIPLIER,
        absVelocityThreshold = RulerPhysics.FLING_VELOCITY_THRESHOLD,
    )

    /** Critically damped: the magnetism pulls, it never bounces (PRD FR-ENTRY-002). */
    private val settleSpec: SpringSpec<Float> = spring(
        dampingRatio = RulerPhysics.SETTLE_DAMPING_RATIO,
        stiffness = RulerPhysics.SETTLE_STIFFNESS,
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

    fun onDrag(dragPx: Float, pixelsPerTenth: Float) {
        moveTo(positionTenths + RulerPhysics.dragToTenths(dragPx, pixelsPerTenth))
    }

    /**
     * Runs the inertia and the magnetism, then hands the landing tenth to [onRest].
     *
     * [onRest] is the *only* moment the rest of the app hears about a drag. Publishing every
     * frame instead would put a ViewModel round trip and a recomposition of the screen on the
     * hot path of the one gesture that has to track the finger exactly.
     */
    fun onDragEnd(velocityTenthsPerSecond: Float, allowFling: Boolean, onRest: (Int) -> Unit) {
        val gesture = generation
        motion = scope.launch {
            try {
                settle(velocityTenthsPerSecond, allowFling)
            } finally {
                if (gesture == generation) interacting = false
            }
            onRest(displayedTenths)
        }
    }

    /** An order from outside the scale: the history seed, `−` / `+`, the keyboard, TalkBack. */
    fun jumpTo(tenths: Int) {
        stopMotion()
        interacting = false
        moveTo(tenths.toFloat())
    }

    private fun stopMotion() {
        generation++
        motion?.cancel()
        motion = null
    }

    private fun moveTo(tenths: Float) {
        val clamped = RulerPhysics.clampPosition(tenths)
        positionTenths = clamped
        displayedTenths = RulerPhysics.snapToTenth(clamped)
    }

    /**
     * The short glide of PRD FR-ENTRY-002 followed by the pull onto the nearest tenth.
     *
     * Clamping inside the decay is what makes 30.0 and 250.0 dead stops: the moment the
     * projected value leaves the range the animation is cancelled where it stands, which is
     * already an exact tenth, so no rebound and no second animation follow.
     */
    private suspend fun settle(velocityTenthsPerSecond: Float, allowFling: Boolean) {
        if (allowFling && RulerPhysics.isFlingWorthwhile(velocityTenthsPerSecond)) {
            AnimationState(positionTenths, velocityTenthsPerSecond).animateDecay(decay) {
                moveTo(value)
                if (value != positionTenths) cancelAnimation()
            }
        }
        val target = displayedTenths.toFloat()
        if (positionTenths != target) {
            AnimationState(positionTenths).animateTo(target, settleSpec) { moveTo(value) }
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
    return remember(scope) { RulerState(initial.tenthsKg, scope) }
}
