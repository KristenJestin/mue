package fr.kristenjestin.mue.ui.food.scan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.kristenjestin.mue.MueApplication
import kotlinx.coroutines.launch

/**
 * `CAMERA` for FR-FOOD-003's scanner, asked once and never again.
 *
 * The whole shape of this file is [fr.kristenjestin.mue.timer.TimerNotificationPermission]'s,
 * because the rule is the same rule and it was already got right once: the prompt is shown at
 * most once in the life of the install, the flag that makes "once" implementable is persisted,
 * and it is written **before** the answer comes back — a person who dismisses the dialog by
 * tapping outside it has still been asked, and Android counts that dismissal against the two
 * attempts it allows.
 *
 * What differs is what a refusal costs, and here it costs less than anywhere else in the app.
 * PRD_FOOD 18 does not call the typed barcode a fallback: it is "une alternative **complète** à
 * la caméra", and the lookup, the copy into the catalogue and the prefilled creation are
 * byte-identical whichever of the two produced the number. So nothing on the scan path is gated
 * on this permission. The camera adds a way of typing thirteen digits without typing them.
 */
internal object FoodCameraPermission {

    /**
     * Whether Mue may open the camera right now.
     *
     * `checkSelfPermission` and not a remembered result: `CAMERA` is revocable from Settings while
     * the app is in the background, and a screen that trusted its own last answer would keep a
     * dead preview on screen until the next process.
     */
    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Whether this device has a camera at all.
     *
     * `<uses-feature android:required="false">` means Mue installs on devices that have none, and
     * on one of those the honest screen is the one that offers the field without ever mentioning
     * a permission — being told to grant something that would still not work is worse than not
     * being offered it.
     */
    fun isAvailable(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    /**
     * The way back after a refusal, as FR-TIMER-012 does it: Mue never asks twice, and Android
     * would not show the prompt twice anyway. Settings is the only place the answer can change.
     */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
}

/** What the scan panel needs to know about the camera, and the one thing it can do about it. */
@Stable
internal class FoodCameraPermissionState internal constructor(
    /** The preview can be shown. Re-read on every return to the app. */
    val isGranted: Boolean,
    /** There is a camera on this device at all. */
    val isAvailable: Boolean,
    /**
     * The system prompt is still worth showing: a camera exists, it is not granted, and it has
     * never been asked for. Once this is false the only route left is
     * [FoodCameraPermission.settingsIntent] — and the typed barcode, which never needed either.
     */
    val canRequest: Boolean,
    private val onRequest: () -> Unit,
) {
    /** Shows the system prompt, at most once in the life of the install. */
    fun request() {
        if (canRequest) onRequest()
    }
}

/**
 * The permission, wired to the persisted flag that makes "ask once" implementable.
 *
 * **`shouldShowRequestPermissionRationale` cannot answer this question.** It returns `false`
 * before the very first request and `false` again after a permanent denial, so the two states
 * this rule has to tell apart are the same value. Only a boolean written at the moment the prompt
 * is launched distinguishes them, which is what `ScanPreferencesRepository` exists for.
 */
@Composable
internal fun rememberFoodCameraPermission(): FoodCameraPermissionState {
    val context = LocalContext.current
    val preferences = remember(context) {
        (context.applicationContext as MueApplication).container.food.scanPreferencesRepository
    }
    val scope = rememberCoroutineScope()

    // `true` while the stored value is still being read: the safe default is not to ask. A prompt
    // that appeared for a fraction of a second because a DataStore read had not landed yet would
    // spend the one request this install gets.
    val alreadyRequested by preferences.cameraPermissionRequested
        .collectAsStateWithLifecycle(initialValue = true)

    val available = remember(context) { FoodCameraPermission.isAvailable(context) }
    var granted by remember { mutableStateOf(FoodCameraPermission.isGranted(context)) }

    // Revocable from Settings while Mue is in the background, and grantable there too — which is
    // the whole point of the way back this offers after a refusal. Re-read on every return.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = FoodCameraPermission.isGranted(context)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result -> granted = result }

    return FoodCameraPermissionState(
        isGranted = granted && available,
        isAvailable = available,
        canRequest = available && !granted && !alreadyRequested,
        onRequest = {
            scope.launch { preferences.setCameraPermissionRequested(true) }
            launcher.launch(Manifest.permission.CAMERA)
        },
    )
}
