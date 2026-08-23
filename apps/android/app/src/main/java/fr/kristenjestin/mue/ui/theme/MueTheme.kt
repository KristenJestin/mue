package fr.kristenjestin.mue.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Entry point of the Mue design system.
 *
 * Material 3 is installed underneath purely as plumbing — ripples, text selection handles,
 * cursor colours — and every visible surface comes from the Mue scales instead. Nothing
 * in the app should read [MaterialTheme] directly.
 *
 * [reduceMotion] defaults to the device's own animation setting and is a parameter rather
 * than a hardcoded lookup so a caller — a test, a preview — can state it. Reading the
 * setting unconditionally here would silently overwrite any [LocalReduceMotion] the caller
 * had provided, and the reduced paths would then never run.
 */
@Composable
fun MueTheme(
    reduceMotion: Boolean = rememberReduceMotion(),
    content: @Composable () -> Unit,
) {
    val colors = MueDarkColors

    ApplySystemBarAppearance()

    val materialScheme = remember(colors) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            secondary = colors.accent,
            onSecondary = colors.onAccent,
            background = colors.canvas,
            onBackground = colors.textPrimary,
            surface = colors.canvas,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.canvasElevated,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.surfaceBorder,
            error = colors.error,
            onError = colors.onAccent,
            scrim = colors.scrim,
        )
    }

    CompositionLocalProvider(
        LocalMueColors provides colors,
        LocalMueTypography provides MueTypeScale,
        LocalMueSpacing provides MueSpacing(),
        LocalMueShapes provides MueShapes(),
        LocalMueContentColor provides colors.textPrimary,
        LocalContentColor provides colors.textPrimary,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(colorScheme = materialScheme) {
            content()
        }
    }
}

/** Accessors for the Mue scales, mirroring the `MaterialTheme` object convention. */
object MueTheme {
    val colors: MueColors
        @Composable @ReadOnlyComposable get() = LocalMueColors.current

    val typography: MueTypography
        @Composable @ReadOnlyComposable get() = LocalMueTypography.current

    val spacing: MueSpacing
        @Composable @ReadOnlyComposable get() = LocalMueSpacing.current

    val shapes: MueShapes
        @Composable @ReadOnlyComposable get() = LocalMueShapes.current

    /** Content colour published by the enclosing container. */
    val contentColor: Color
        @Composable @ReadOnlyComposable get() = LocalMueContentColor.current

    val reduceMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReduceMotion.current
}

/**
 * The app is dark-only, so system bar icons are always light. `themes.xml` already declares
 * this for the launch window; this keeps it true if anything changes the flags at runtime.
 */
@Composable
private fun ApplySystemBarAppearance() {
    if (LocalInspectionMode.current) return
    val view = LocalView.current
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
