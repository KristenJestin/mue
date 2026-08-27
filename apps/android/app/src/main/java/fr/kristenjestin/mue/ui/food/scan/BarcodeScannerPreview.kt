package fr.kristenjestin.mue.ui.food.scan

import androidx.annotation.OptIn
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import fr.kristenjestin.mue.ui.theme.MueTheme
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

/**
 * The live camera, and the only place in Mue that opens one for reading rather than for a photo.
 *
 * ## What it is, in one sentence
 *
 * A `Preview` use case drawn by [CameraXViewfinder], and an `ImageAnalysis` use case whose frames
 * go to ML Kit and nowhere else. No frame is stored, no frame is copied out of the analyser, and
 * nothing but a string of digits ever leaves this composable — which is the implementation half
 * of PRD_FOOD 9.2's "aucune image ne quitte le téléphone".
 *
 * ## Why the binding is undone by hand
 *
 * `bindToLifecycle` follows the **lifecycle owner**, and in a single-activity Compose app that
 * owner is the activity. A scan sheet that closed would therefore leave the camera bound and the
 * torch-adjacent green dot lit for as long as Mue was open. So the binding lives inside a
 * `LaunchedEffect` that suspends for ever and unbinds in its `finally`: leaving the composition
 * cancels the coroutine, which releases the camera, closes the detector and shuts the analysis
 * thread down. The activity's lifecycle still stops the preview when the app is backgrounded,
 * which is the half CameraX is right about.
 *
 * ## Why the same number is not reported twice
 *
 * `STRATEGY_KEEP_ONLY_LATEST` still delivers many frames a second and a barcode held in front of
 * a lens reads identically in all of them. [lastReported] drops the repeats, so the caller sees
 * one event per code rather than thirty; a *different* code always gets through, because moving
 * the phone to another jar has to work without closing the sheet. PRD_FOOD 17's "le scanner
 * continue" is the same rule seen from the other side: nothing here ever stops on its own.
 */
@Composable
internal fun BarcodeScannerPreview(
    onBarcode: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnBarcode by rememberUpdatedState(onBarcode)
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }

    /*
     * A preview and an inspection tree have no camera behind them, and `ProcessCameraProvider`
     * would throw inside the tooling. The box below is what the layout is measured against
     * either way, so a `@Preview` still shows the scanner's shape.
     */
    val live = !LocalInspectionMode.current

    Box(
        modifier = modifier
            .clip(MueTheme.shapes.card)
            .background(MueTheme.colors.surface)
            /*
             * PRD_FOOD 18: every control is announced. A viewfinder has nothing readable in it,
             * and a screen-reader user is precisely the person the typed field beneath exists
             * for — so this says what the rectangle is and does not pretend to be a control.
             */
            .semantics { this.contentDescription = contentDescription },
    ) {
        if (live) {
            LaunchedEffect(lifecycleOwner) {
                val provider = ProcessCameraProvider.awaitInstance(context)

                /*
                 * **The main thread, explicitly, and this line is load-bearing.**
                 *
                 * `setSurfaceProvider`, `bindToLifecycle` and `unbind` all call
                 * `Threads.checkMainThread` and throw outright off it. A `LaunchedEffect` starts
                 * on the composition's dispatcher, so it *looks* as though everything below is
                 * already on the main thread — and on a warm start it is, because CameraX is
                 * initialised and `awaitInstance` returns without suspending at all.
                 *
                 * On a **cold** one it suspends, and the future that resumes it is completed on
                 * CameraX's own init executor and delivered by a `DirectExecutor` — so the
                 * continuation resumes on `CameraX-camerax_init`, and the first call below throws
                 * `IllegalStateException: Not in application's main thread`.
                 *
                 * That is the shape of bug that survives a whole test suite: the scanner works
                 * every time it is opened *except* the very first time in a fresh process, which
                 * is the only time an owner meets it. It was caught by running the preview as the
                 * **only** class of a fresh instrumentation on an emulator, and the stack came out
                 * of `Preview.setSurfaceProvider` → `Threads.checkMainThread`.
                 *
                 * **No test in the suite reproduces it**, and that is stated rather than glossed.
                 * `ProcessCameraProvider.shutdown()` re-initialises CameraX but does not restore
                 * the resume path a genuinely first use takes, so `BarcodeScannerPreviewTest`
                 * passes with this `withContext` deleted — which was checked, not assumed. The
                 * line stays because the exception was seen, not because something is watching it.
                 */
                withContext(Dispatchers.Main.immediate) {
                    val preview = Preview.Builder().build().apply {
                        setSurfaceProvider { request -> surfaceRequest = request }
                    }
                    val scanner = MlKitBarcodeDecoder.newScanner()
                    val lastReported = AtomicReference<String?>(null)
                    // One thread, its own: analysis must never run on the frame loop, and a shared
                    // pool would let a slow decode of one frame delay the app's other work.
                    val executor = Executors.newSingleThreadExecutor()
                    val analysis = ImageAnalysis.Builder()
                        // The shopper's hand moves. Queuing frames would decode where the phone
                        // was half a second ago and lag further behind with every one of them.
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    analysis.setAnalyzer(
                        executor,
                        BarcodeFrameAnalyzer(scanner) { code ->
                            if (lastReported.getAndSet(code) != code) currentOnBarcode(code)
                        },
                    )

                    try {
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            // The back camera, and no fallback to the front one: a selfie camera
                            // cannot be pointed at a jar the person is holding, and offering it
                            // would be a scanner that looks alive and reads nothing.
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                        awaitCancellation()
                    } finally {
                        // Still on the main thread, and none of these suspends — so cancellation
                        // cannot cut the release short.
                        analysis.clearAnalyzer()
                        provider.unbind(preview, analysis)
                        scanner.close()
                        executor.shutdown()
                        surfaceRequest = null
                    }
                }
            }

            surfaceRequest?.let { request ->
                CameraXViewfinder(surfaceRequest = request, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * One frame, decoded.
 *
 * The `ImageProxy` is closed in **every** path, including the failure one, and that is the whole
 * of the correctness here: `STRATEGY_KEEP_ONLY_LATEST` hands out one buffer at a time, so a proxy
 * that is not closed stops the camera dead — a scanner that works for exactly one frame and then
 * freezes, with no error anywhere.
 *
 * A decode that fails is not reported and not counted: PRD_FOOD 17 answers an unreadable code
 * with "le scanner continue", and the next frame is a fraction of a second away.
 */
private class BarcodeFrameAnalyzer(
    private val scanner: BarcodeScanner,
    private val onBarcode: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val frame = image.image
        if (frame == null) {
            image.close()
            return
        }
        // The rotation travels with the frame rather than being assumed: the activity is locked
        // to portrait, but the sensor's own orientation differs by device and a barcode read
        // ninety degrees out is a barcode not read at all.
        val input = InputImage.fromMediaImage(frame, image.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { found ->
                FoodBarcodes.firstRetailOrNull(found.map(Barcode::getRawValue))?.let(onBarcode)
            }
            .addOnCompleteListener { image.close() }
    }
}
