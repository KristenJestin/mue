package fr.kristenjestin.mue.ui.theme

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.IntOffset

/**
 * Durations and easings of PRD section 13, plus the reduce-motion contract of section 14.
 *
 * Screens must never hardcode a duration: ask [spec] or [durationOf] instead, so that
 * turning Android animations off collapses every transition to a short fade in one place.
 */
object MueMotion {

    /** Tab change: light directional slide plus fade, bar stays put. */
    const val TabChangeMillis: Int = 220

    /** Manual entry: the scale fades down and the value becomes a field. */
    const val ManualEntryMillis: Int = 180

    /** Bottom sheets: date picker and history edit panel. */
    const val SheetMillis: Int = 220

    /** Period change on Progress: chart and indicators morph. */
    const val PeriodChangeMillis: Int = 280

    /** BMI value and reference marker. */
    const val BmiMillis: Int = 250

    /*
     * The save confirmation of PRD 13, timed by the approved `both` prototype: the button
     * discharges its light and then goes quiet. One press, one beat — the halo leaves as the
     * fill dims, rather than the two happening in turn.
     */

    /** Whole confirmation, from the press to the button coming back. */
    const val SaveConfirmationMillis: Int = 960

    /** How long the touch contraction is held before it is released. */
    const val SavePressHoldMillis: Int = 170

    /** Cross-fade of the button label, both on the way in and on the way out. */
    const val SaveLabelFadeMillis: Int = 130

    /** When the fill drops to soft amber — the instant the label comes back as `Saved`. */
    const val SaveQuietOnsetMillis: Int = 140

    /** The halo radiating off the button, and the screen echo that travels with it. */
    const val SaveHaloMillis: Int = 950

    /** The vertical hop Profile's BMI readout gives when the halo reaches it. */
    const val SaveHopMillis: Int = 600

    /** Vertical roll of a single digit. */
    const val NumberRollMillis: Int = 220

    /** Every slide degrades to a fade of this length when animations are reduced. */
    const val ReducedMillis: Int = 100

    /** Press feedback is a state change, not a transition; it stays short either way. */
    const val PressMillis: Int = 90

    /** Symmetric, no overshoot — PRD 13 forbids bounce on navigation. */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Enter: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val Exit: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    @Composable
    @ReadOnlyComposable
    fun durationOf(millis: Int): Int = if (LocalReduceMotion.current) ReducedMillis else millis

    /** A [tween] already collapsed to a linear 100 ms fade when motion is reduced. */
    @Composable
    @ReadOnlyComposable
    fun <T> spec(
        durationMillis: Int = TabChangeMillis,
        easing: Easing = Standard,
        delayMillis: Int = 0,
    ): TweenSpec<T> = if (LocalReduceMotion.current) {
        tween(durationMillis = ReducedMillis, easing = LinearEasing)
    } else {
        tween(durationMillis = durationMillis, delayMillis = delayMillis, easing = easing)
    }

    /**
     * Transition between two tabs. [forward] means the destination sits to the right of the
     * origin in the tab bar. Reduced motion drops the slide entirely.
     */
    @Composable
    @ReadOnlyComposable
    fun tabTransition(forward: Boolean): ContentTransform {
        val reduced = LocalReduceMotion.current
        val enterSpec = spec<Float>(TabChangeMillis, Enter)
        val exitSpec = spec<Float>(TabChangeMillis, Exit)
        if (reduced) {
            return fadeIn(enterSpec) togetherWith fadeOut(exitSpec)
        }
        val offsetSpec = spec<IntOffset>(TabChangeMillis, Standard)
        val direction = if (forward) 1 else -1
        return (
            slideInHorizontally(offsetSpec) { width -> direction * width / 6 } + fadeIn(enterSpec)
            ) togetherWith (
            slideOutHorizontally(offsetSpec) { width -> -direction * width / 6 } + fadeOut(exitSpec)
            )
    }
}

/**
 * True when the system animation scale is off. Screens read this to skip digit rolling and
 * any purely decorative movement; functional behaviour such as the scale magnetism stays on.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * Observes `Settings.Global.ANIMATOR_DURATION_SCALE` so the app reacts while it is running,
 * not only at launch. Previews and unit hosts fall back to "animations enabled".
 */
@Composable
fun rememberReduceMotion(): Boolean {
    if (LocalInspectionMode.current) return false

    val resolver = LocalContext.current.contentResolver
    var reduced by remember(resolver) { mutableStateOf(resolver.isAnimationScaleOff()) }

    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = resolver.isAnimationScaleOff()
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }

    // The observer does not fire for changes made while the app was in the background.
    LaunchedEffect(resolver) { reduced = resolver.isAnimationScaleOff() }

    return reduced
}

private fun ContentResolver.isAnimationScaleOff(): Boolean =
    Settings.Global.getFloat(this, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
