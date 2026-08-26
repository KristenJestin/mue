package fr.kristenjestin.mue.ui.scale

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.kristenjestin.mue.MueApplication
import fr.kristenjestin.mue.data.scale.ble.ScaleAvailability
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Everything the scale module needs from Android before it may scan (PRD_SCALE 16.1, 18.5).
 *
 * Four separate conditions hide behind "the scale does not work", and the point of this file is
 * that a screen can tell them apart and say which one it is:
 *
 * 1. the runtime permissions of the current API level are granted;
 * 2. the Bluetooth radio is switched on;
 * 3. before API 31 only, system location is switched on — a requirement of the platform's BLE
 *    scanner, not of Mue, which PRD_SCALE 16.1 insists must be *explained* rather than suffered
 *    as an empty list of results;
 * 4. the refusal is not final, in which case the only route left is the app's settings page.
 *
 * **Nothing here asks for anything on its own.** FR-SCALE-025 puts the request at the first
 * pairing and never at launch, so [ScalePermissionsState.request] is the only door and a screen
 * that never calls it can never raise a system dialog — reading the state is entirely passive.
 *
 * BR-SCALE-011 is the rule underneath all of it: every function of Mue stays available with no
 * Bluetooth and no permission granted, so nothing here may gate a weigh-in typed by hand.
 *
 * **Conditions 1 to 3 are not detected here.** They are read from [ScaleAvailability], the same
 * object `AndroidScaleTransport` consults before it opens a session, and that is deliberate: a
 * screen explaining one cause while the link refuses another is the one failure this module could
 * not diagnose from a bug report. Only the two things that need Compose stay here — the fourth
 * condition, which needs a persisted flag and the current activity, and the intents that turn each
 * of the four into a gesture.
 */
internal object ScalePermissions {

    /** What this API level asks for (PRD_SCALE 16.1), decided once by [ScaleAvailability]. */
    val REQUIRED: List<String> = ScaleAvailability.REQUIRED_PERMISSIONS

    /** Whether system location gates the scan on this device (PRD_SCALE 16.1 and 18.5). */
    val REQUIRES_SYSTEM_LOCATION: Boolean = ScaleAvailability.REQUIRES_SYSTEM_LOCATION

    /** Every permission of [REQUIRED] is held. Re-read on every return to the app. */
    fun isGranted(context: Context): Boolean = ScaleAvailability.hasPermissions(context)

    /** The radio is on (PRD_SCALE 18.5). */
    fun isBluetoothEnabled(context: Context): Boolean =
        ScaleAvailability.isBluetoothEnabled(context)

    /** System location is on, which before API 31 is a precondition of scanning. */
    fun isSystemLocationEnabled(context: Context): Boolean =
        ScaleAvailability.isSystemLocationEnabled(context)

    /**
     * The system's way to switch the radio on (PRD_SCALE 18.5, `Bluetooth is off · Enable`).
     *
     * `ACTION_REQUEST_ENABLE` is the in-place dialog and the better experience, but from Android
     * 12 firing it without `BLUETOOTH_CONNECT` throws a `SecurityException`. So when the
     * permission is not held — which is precisely the moment a user could meet both problems at
     * once — this falls back to the Bluetooth settings page, which needs nothing and is never
     * refused. Either way it opens only on a tap: FR-SCALE-025 forbids opening a system screen
     * without the user asking.
     */
    fun enableBluetoothIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ScaleAvailability.isGranted(context, Manifest.permission.BLUETOOTH_CONNECT)
        ) {
            Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        } else {
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        }

    /** Where a permanent refusal leads (FR-SCALE-025), since Mue never asks a second time. */
    fun appSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )

    /** The master switch of PRD_SCALE 18.5, reachable only up to Android 11. */
    fun systemLocationSettingsIntent(): Intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
}

/**
 * What a scale screen needs to know about Android, and the one thing it can do about it.
 *
 * Every field is a plain value read at composition and refreshed on `ON_RESUME`, so a screen
 * renders from it without side effects. [request] is the only member that does anything, and it
 * does nothing at all unless [canRequest] — which is what keeps FR-SCALE-025's "never at launch"
 * true by construction.
 */
@Stable
internal class ScalePermissionsState internal constructor(
    /** What this API level asks for; empty is impossible. Useful for a diagnostic block. */
    val required: List<String>,
    /** Every permission of [required] is held. */
    val isGranted: Boolean,
    /** Asked for at some point and not held now — softly or for good. */
    val isDenied: Boolean,
    /**
     * Asked for, refused, and the system will no longer show its dialog. The only route left is
     * [appSettingsIntent], with the one-sentence explanation FR-SCALE-025 requires.
     *
     * `false` while the persisted flag is still being read, so a screen never flashes
     * `Open settings` at someone who has simply never been asked.
     */
    val isPermanentlyDenied: Boolean,
    /** The system dialog would actually appear. */
    val canRequest: Boolean,
    /** The radio is on (PRD_SCALE 18.5). */
    val isBluetoothEnabled: Boolean,
    /** This device is on API ≤ 30, where system location gates every BLE scan. */
    val requiresSystemLocation: Boolean,
    /** System location is on. Always `true` from Android 12, where it is not a condition. */
    val isSystemLocationEnabled: Boolean,
    /** Opens the enable dialog, or Bluetooth settings when the dialog is not permitted. */
    val enableBluetoothIntent: Intent,
    /** Mue's own page in Android settings, for a permanent refusal. */
    val appSettingsIntent: Intent,
    /** Android's location master switch. Only meaningful when [requiresSystemLocation]. */
    val systemLocationSettingsIntent: Intent,
    private val onRequest: () -> Unit,
) {

    /**
     * Nothing stands between Mue and a scan.
     *
     * The three conditions are read together because a screen almost always wants the whole
     * answer; when it is `false`, the individual flags say which sentence of PRD_SCALE 18.5 to
     * show, and in that order — permission, then radio, then system location.
     */
    val canScan: Boolean
        get() = isGranted &&
            isBluetoothEnabled &&
            (!requiresSystemLocation || isSystemLocationEnabled)

    /**
     * Shows the system prompt (FR-SCALE-025: at the first pairing, on a deliberate tap).
     *
     * Silently does nothing once the refusal is final, so a caller cannot turn a dead prompt
     * into a dead button; read [isPermanentlyDenied] and offer [appSettingsIntent] instead.
     */
    fun request() {
        if (canRequest) onRequest()
    }
}

/**
 * The permissions of PRD_SCALE 16.1, wired to the persisted flag that makes FR-SCALE-025
 * implementable.
 *
 * **`shouldShowRequestPermissionRationale` cannot answer this on its own.** It returns `false`
 * before the very first request and `false` again after a permanent denial, so the two states
 * that lead to opposite interfaces — show the prompt, or send the user to settings — are the
 * same value. A boolean written the moment the prompt is launched is what tells them apart, and
 * it is why `UserPreferences.scalePermissionRequested` exists.
 *
 * The flag is written *before* the answer comes back, not after: a user who dismisses the dialog
 * by tapping outside it has still been asked, and the system counts that dismissal against the
 * attempts it allows.
 *
 * Everything is re-read on `ON_RESUME` because all four conditions can change while Mue is in
 * the background — a permission revoked in settings, the radio switched off from the shade,
 * location turned off — and because that is also the way back from every intent this file hands
 * out.
 */
@Composable
internal fun rememberScalePermissions(): ScalePermissionsState {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val preferences = remember(context) {
        (context.applicationContext as MueApplication).container.userPreferencesRepository
    }
    val scope = rememberCoroutineScope()

    // `null` while the stored value is still being read. It is deliberately a third state rather
    // than a safe default: `false` would offer a prompt that may be dead, and `true` would
    // accuse a first-time user of a refusal they never made. Unknown means "offer neither yet".
    val requestedFlow = remember(preferences) {
        preferences.preferences.map { it.scalePermissionRequested }.distinctUntilChanged()
    }
    val alreadyRequested: Boolean? by requestedFlow.collectAsStateWithLifecycle(initialValue = null)

    var granted by remember { mutableStateOf(ScalePermissions.isGranted(context)) }
    var bluetoothEnabled by remember {
        mutableStateOf(ScalePermissions.isBluetoothEnabled(context))
    }
    var systemLocationEnabled by remember {
        mutableStateOf(ScalePermissions.isSystemLocationEnabled(context))
    }

    // Recomputed on every resume too, because a rationale flips the moment the user answers.
    var rationaleOffered by remember {
        mutableStateOf(activity.shouldShowAnyRationale(ScalePermissions.REQUIRED))
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = ScalePermissions.isGranted(context)
        bluetoothEnabled = ScalePermissions.isBluetoothEnabled(context)
        systemLocationEnabled = ScalePermissions.isSystemLocationEnabled(context)
        rationaleOffered = activity.shouldShowAnyRationale(ScalePermissions.REQUIRED)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        // The map answers for the permissions that were asked for; the authority stays the
        // package manager, so a partial grant can never read as a whole one.
        granted = results.values.all { it } && ScalePermissions.isGranted(context)
        rationaleOffered = activity.shouldShowAnyRationale(ScalePermissions.REQUIRED)
    }

    // The system dialog will appear when the request has never been made, or when Android still
    // offers a rationale — that is, after a first refusal the user can be asked about again.
    val canShowSystemPrompt = alreadyRequested == false || rationaleOffered

    return ScalePermissionsState(
        required = ScalePermissions.REQUIRED,
        isGranted = granted,
        isDenied = !granted && alreadyRequested == true,
        isPermanentlyDenied = !granted && alreadyRequested == true && !canShowSystemPrompt,
        canRequest = !granted && alreadyRequested != null && canShowSystemPrompt,
        isBluetoothEnabled = bluetoothEnabled,
        requiresSystemLocation = ScalePermissions.REQUIRES_SYSTEM_LOCATION,
        isSystemLocationEnabled = systemLocationEnabled,
        enableBluetoothIntent = ScalePermissions.enableBluetoothIntent(context),
        appSettingsIntent = ScalePermissions.appSettingsIntent(context),
        systemLocationSettingsIntent = ScalePermissions.systemLocationSettingsIntent(),
        onRequest = {
            // Persisted first, and not in the result callback: see the file's KDoc.
            scope.launch { preferences.setScalePermissionRequested(true) }
            launcher.launch(ScalePermissions.REQUIRED.toTypedArray())
        },
    )
}

/**
 * Whether Android would still show its own explanation for at least one missing permission.
 *
 * Read through the activity because that is the only receiver the platform offers. A null
 * activity — a preview, or a composable hosted outside one — answers `false`, which combined
 * with an unread flag leaves the state at "unknown" rather than at a wrong certainty.
 */
private fun Activity?.shouldShowAnyRationale(permissions: List<String>): Boolean {
    val activity = this ?: return false
    return permissions.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
