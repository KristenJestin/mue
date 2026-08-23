package fr.kristenjestin.mue.ui.components

import android.content.Context
import android.media.AudioAttributes
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

/**
 * The two sensations Mue produces, described without reference to any Android API so the
 * choice of one over the other stays testable on the JVM.
 *
 * The fallbacks are what a motor without predefined effects is asked for. They are short on
 * purpose: the tick fires every half kilogram of a drag (PRD FR-ENTRY-002) and has to read as
 * a graduation passing under the finger, not as a notification.
 */
enum class MueHaptic(val fallbackDurationMillis: Long, val fallbackAmplitude: Int) {

    /** Every 0.5 kg of scale travel. */
    Tick(fallbackDurationMillis = 10L, fallbackAmplitude = 70),

    /** One measurement saved (PRD FR-ENTRY-006). */
    Confirm(fallbackDurationMillis = 26L, fallbackAmplitude = 180);

    /**
     * Amplitude to ask the motor for. A motor with no amplitude control is given
     * `VibrationEffect.DEFAULT_AMPLITUDE`, which lets the system pick its own strength rather
     * than having the request silently rounded up to full power.
     */
    fun amplitude(hasAmplitudeControl: Boolean): Int =
        if (hasAmplitudeControl) fallbackAmplitude else DEFAULT_AMPLITUDE

    companion object {
        /** Mirrors `VibrationEffect.DEFAULT_AMPLITUDE`, kept here so this file stays pure. */
        const val DEFAULT_AMPLITUDE: Int = -1
    }
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
class MueHaptics(private val vibrator: Vibrator?, val enabled: Boolean) {

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
                TouchVibration.play(motor, effect)
            } else {
                @Suppress("DEPRECATION")
                motor.vibrate(effect, LegacyTouchFeedback)
            }
        }
    }

    /**
     * Marks the vibration as touch feedback, which is what it is: the system then scales it to
     * the strength the user chose for that category. That is a different setting from the
     * touch-feedback switch, which gates `performHapticFeedback` and not this call.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private object TouchVibration {
        private val attributes: VibrationAttributes =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)

        fun play(motor: Vibrator, effect: VibrationEffect) = motor.vibrate(effect, attributes)
    }

    private companion object {

        /** The same intent expressed the only way Android understood before 13. */
        val LegacyTouchFeedback: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

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
            return VibrationEffect.createOneShot(
                haptic.fallbackDurationMillis,
                haptic.amplitude(hasAmplitudeControl()),
            )
        }
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
