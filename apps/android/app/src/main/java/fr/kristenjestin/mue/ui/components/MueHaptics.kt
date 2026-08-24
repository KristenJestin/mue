package fr.kristenjestin.mue.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Mirrors `VibrationEffect.DEFAULT_AMPLITUDE`, kept here so this file stays pure. */
const val DEFAULT_AMPLITUDE: Int = -1

/**
 * What Mue asks the motor for, described without reference to any Android API so the choice
 * stays testable on the JVM.
 */
sealed interface MueMotorRequest {

    /** One continuous pulse. */
    data class Pulse(val durationMillis: Long, val amplitude: Int) : MueMotorRequest

    /** Alternating off and on durations, starting with an off step, played once. */
    data class Pattern(val timingsMillis: List<Long>, val amplitude: Int) : MueMotorRequest
}

/**
 * The two sensations Mue produces.
 *
 * Each carries two fallbacks because the phones that reach this code have two different kinds
 * of actuator, and what reads as a light tick on one is nothing at all on the other.
 *
 * A motor reporting `AMPLITUDE_CONTROL` is a linear resonant actuator: it reaches its target
 * within a few milliseconds, so a brief pulse at a chosen strength is enough, and strength is
 * what separates the tick from the save.
 *
 * A motor reporting none is an eccentric rotating mass, which has to physically spin a weight
 * up before anything reaches the hand. Below roughly twenty milliseconds it never gets there.
 * The Galaxy A71 is one of these — `mCapabilities=[]`, no resonant frequency — and its own
 * system feedback runs at 45 ms per touch. Amplitude is not a lever on such a motor: the HAL
 * ignores it. Time is, so the two sensations are separated by shape instead of strength.
 */
enum class MueHaptic(
    private val onAmplitudeControl: MueMotorRequest,
    private val onPlainMotor: MueMotorRequest,
) {

    /** Every 0.5 kg of scale travel. */
    Tick(
        onAmplitudeControl = MueMotorRequest.Pulse(durationMillis = 10L, amplitude = 70),
        onPlainMotor = MueMotorRequest.Pulse(
            durationMillis = 22L,
            amplitude = DEFAULT_AMPLITUDE,
        ),
    ),

    /**
     * One measurement saved (PRD FR-ENTRY-006). On a plain motor this is two beats — a short
     * flare and a longer settle — because a single pulse there can only differ from the tick
     * by a duration the hand does not measure.
     */
    Confirm(
        onAmplitudeControl = MueMotorRequest.Pulse(durationMillis = 26L, amplitude = 180),
        onPlainMotor = MueMotorRequest.Pattern(
            timingsMillis = listOf(0L, 35L, 85L, 65L),
            amplitude = DEFAULT_AMPLITUDE,
        ),
    );

    /**
     * `VibrationEffect.DEFAULT_AMPLITUDE` is what a motor without amplitude control is asked
     * for, rather than a number: a number there is rounded up to full power, which is the one
     * strength Mue never wants.
     */
    fun requestFor(hasAmplitudeControl: Boolean): MueMotorRequest =
        if (hasAmplitudeControl) onAmplitudeControl else onPlainMotor
}

/**
 * Mue's vibrations, driven through the vibrator rather than through `performHapticFeedback`.
 *
 * `View.performHapticFeedback` obeys Android's *touch feedback* switch. On a phone where that
 * switch is off — which it silently is on plenty of them — the preference of PRD FR-PROFILE-004
 * would promise a feedback the app never delivers. Inside Mue that preference is the authority
 * on whether Mue vibrates, so the motor is driven directly and [enabled] alone decides.
 *
 * The effects are built once per instance: the tick sits on the hot path of a drag and must
 * not allocate there.
 */
@Immutable
class MueHaptics(vibrator: Vibrator?, val enabled: Boolean) {

    private val motor: Vibrator? =
        vibrator?.takeIf { enabled && runCatching { it.hasVibrator() }.getOrDefault(false) }

    private val tickEffect: VibrationEffect? = motor?.effectFor(MueHaptic.Tick)
    private val confirmEffect: VibrationEffect? = motor?.effectFor(MueHaptic.Confirm)

    /** The light graduation tick of PRD FR-ENTRY-002. */
    fun tick() = dispatch(tickEffect)

    /** The confirmation of PRD FR-ENTRY-006. */
    fun confirm() = dispatch(confirmEffect)

    fun perform(haptic: MueHaptic) = when (haptic) {
        MueHaptic.Tick -> tick()
        MueHaptic.Confirm -> confirm()
    }

    private fun dispatch(effect: VibrationEffect?) {
        val motor = this.motor ?: return
        if (effect == null) return
        // A vendor can refuse a vibration outright; a missing tick must never take the app down.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Detent.play(motor, effect)
            } else {
                // Android exposes no way to classify a vibration before 13, so this is the
                // system's own guess. On the versions that guess, it does not suppress.
                motor.vibrate(effect)
            }
        }
    }

    /**
     * The vibration is declared as a physical emulation, not as touch feedback.
     *
     * That is what it is — a graduation clicking past under the finger, the detent of a dial —
     * and it is also the only honest way to keep the promise of FR-PROFILE-004. Measured on
     * API 36 with the system's touch-feedback switch off: a `USAGE_TOUCH` vibration is dropped
     * with `ignored_for_settings`, and so is one with no usage at all, because Android
     * reclassifies short undeclared vibrations as touch feedback. `USAGE_PHYSICAL_EMULATION`
     * plays. The system's other controls — vibrate off, battery saver, its own intensity for
     * this category — still apply, and should.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private object Detent {
        private val attributes: VibrationAttributes =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_PHYSICAL_EMULATION)

        fun play(motor: Vibrator, effect: VibrationEffect) = motor.vibrate(effect, attributes)
    }

    private companion object {

        fun Vibrator.effectFor(haptic: MueHaptic): VibrationEffect {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val predefined = when (haptic) {
                    MueHaptic.Tick -> VibrationEffect.EFFECT_TICK
                    MueHaptic.Confirm -> VibrationEffect.EFFECT_CLICK
                }
                val supported = areEffectsSupported(predefined).firstOrNull()
                if (supported == Vibrator.VIBRATION_EFFECT_SUPPORT_YES) {
                    return VibrationEffect.createPredefined(predefined)
                }
            }
            return when (val request = haptic.requestFor(hasAmplitudeControl())) {
                is MueMotorRequest.Pulse ->
                    VibrationEffect.createOneShot(request.durationMillis, request.amplitude)

                is MueMotorRequest.Pattern ->
                    VibrationEffect.createWaveform(
                        request.timingsMillis.toLongArray(),
                        NO_REPEAT,
                    )
            }
        }

        const val NO_REPEAT = -1
    }
}

/** Mue's vibrations, silent unless the preference of PRD FR-PROFILE-004 is on. */
@Composable
fun rememberMueHaptics(enabled: Boolean): MueHaptics {
    val context = LocalContext.current
    val vibrator = remember(context) { context.mueVibrator() }
    return remember(vibrator, enabled) { MueHaptics(vibrator, enabled) }
}

private fun Context.mueVibrator(): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        getSystemService(Vibrator::class.java)
    }
