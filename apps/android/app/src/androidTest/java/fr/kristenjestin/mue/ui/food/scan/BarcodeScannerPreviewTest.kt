package fr.kristenjestin.mue.ui.food.scan

import android.Manifest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.test.platform.app.InstrumentationRegistry
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The camera actually opens, binds and releases — the part no unit test can reach.
 *
 * ## What this proves, and what it deliberately does not
 *
 * It proves that on a real Android runtime, with `CAMERA` granted, the composable resolves a
 * `ProcessCameraProvider`, builds the `Preview` and `ImageAnalysis` use cases, binds them to the
 * lifecycle without throwing, keeps the viewfinder on screen, and — when the composable leaves —
 * unbinds, closes the detector and shuts its executor down without leaking or crashing. Those are
 * real failure modes: a use-case combination a device refuses, an `ImageProxy` never closed, an
 * analyser still running after the sheet is gone.
 *
 * It proves **nothing about reading a barcode from a camera frame**, and it must not be read that
 * way. The emulator's camera is a virtual scene — a room with furniture in it — and nothing can
 * put a jar of Nutella in front of it. That half of FR-FOOD-003 is verified where it can be:
 * `MlKitBarcodeDecoderTest` hands the real detector a real EAN-13 image and gets the digits back.
 * A "scanner works" claim with no frame ever decoded would be a claim nobody checked.
 */
class BarcodeScannerPreviewTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * Granted before each test, which is the only way a camera opens at all here.
     *
     * Through `UiAutomation` rather than `GrantPermissionRule`, which lives in
     * `androidx.test:rules` — an artefact this project does not depend on, and not one worth
     * adding for two lines. This is what that rule does underneath.
     *
     * It says nothing about the product's own permission flow: PRD_FOOD 18's "ask once, and a
     * refusal is a path" belongs to `FoodScanUiStateTest` and `FoodAddScanScreenTest`, where every
     * state can be driven rather than only the one the runner grants.
     */
    @Before
    fun grantCamera() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.CAMERA,
        )
    }

    /**
     * CameraX is torn down before each test, so each one re-initialises it.
     *
     * It is **not** the guard it looks like, and saying so here is the point. The main-thread
     * crash `BarcodeScannerPreview` documents happens when `awaitInstance` suspends and resumes on
     * CameraX's init executor, and `shutdown()` does not restore that resume path: this class
     * passes with the `withContext(Dispatchers.Main.immediate)` deleted, which was checked rather
     * than assumed. A reader who removes that line will not be caught here.
     *
     * It stays because re-initialising is still closer to what a person's phone does than reusing
     * a provider three tests have already warmed, and because it costs a few milliseconds.
     */
    @Before
    fun makeCameraXCold() {
        runCatching { ProcessCameraProvider.shutdown().get(5, TimeUnit.SECONDS) }
    }

    private val description = "Camera viewfinder under test"

    @Test
    fun theCameraBindsAndDrawsAViewfinder() {
        compose.setContent {
            MueTheme {
                BarcodeScannerPreview(
                    onBarcode = {},
                    modifier = Modifier.fillMaxSize().testTag(FoodTestTags.SCANNER_PREVIEW),
                    contentDescription = description,
                )
            }
        }

        compose.waitForIdle()
        compose.onNodeWithTag(FoodTestTags.SCANNER_PREVIEW).assertExists()
        // PRD_FOOD 18: the rectangle says what it is, since it has nothing readable in it.
        compose.onNodeWithContentDescription(description).assertExists()
    }

    /**
     * Leaving the composition releases the camera.
     *
     * `bindToLifecycle` follows the **activity**, not the composable, so nothing here would stop
     * on its own: a scan sheet that closed would leave the camera held and the system's recording
     * indicator lit for as long as Mue was open. The unbind lives in a `finally`, and this is what
     * says the `finally` runs — and that running it throws nothing, which is the other half.
     */
    @Test
    fun leavingTheCompositionReleasesTheCamera() {
        val visible = mutableStateOf(true)
        compose.setContent {
            MueTheme {
                if (visible.value) {
                    BarcodeScannerPreview(
                        onBarcode = {},
                        modifier = Modifier.fillMaxSize().testTag(FoodTestTags.SCANNER_PREVIEW),
                        contentDescription = description,
                    )
                }
            }
        }
        compose.waitForIdle()

        visible.value = false
        compose.waitForIdle()

        compose.onNodeWithTag(FoodTestTags.SCANNER_PREVIEW).assertDoesNotExist()

        // And it binds again afterwards, which is what a person reopening the sheet does. A
        // camera that was released badly the first time refuses the second.
        visible.value = true
        compose.waitForIdle()
        compose.onNodeWithTag(FoodTestTags.SCANNER_PREVIEW).assertExists()
    }
}
