package fr.kristenjestin.muespike

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The whole spike, on one screen: a Start/Stop button, the connection state, the last raw frame
 * and the last decoded weight, over a live tail of the log.
 *
 * The screen is a convenience. `adb logcat -s MUE_SPIKE` is the real output.
 */
class SpikeActivity : ComponentActivity() {

    private lateinit var spike: ScaleSpike

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val refused = grants.filterValues { !it }.keys
            if (refused.isEmpty()) {
                SpikeLog.i("[permissions] accordees")
                spike.start()
            } else {
                SpikeLog.e("[permissions] refusees : " + refused.joinToString(", "))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android throws away the results of an *unfiltered* BLE scan once the screen is off,
        // silently. Since the whole point is that the user puts the phone down and steps on
        // the scale, the screen has to stay up for the length of the session.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        spike = ScaleSpike(applicationContext)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SpikeScreen(
                        spike = spike,
                        onStart = ::requestThenStart,
                        onStop = { spike.stop() },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        spike.stop()
    }

    private fun requestThenStart() {
        val needed = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            spike.start()
        } else {
            SpikeLog.i("[permissions] demande de " + needed.joinToString(", "))
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    /**
     * On Android 12+ the two Bluetooth permissions are enough, because the manifest asserts
     * `neverForLocation`. Below that, a BLE scan is a location operation and needs fine
     * location plus the phone's location services switched on.
     */
    private fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
}

@Composable
private fun SpikeScreen(
    spike: ScaleSpike,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val state by spike.state.collectAsStateWithLifecycle()
    val lines by SpikeLog.lines.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onStart, enabled = !state.running) { Text("Start") }
            Button(onClick = onStop, enabled = state.running) { Text("Stop") }
            Button(onClick = { SpikeLog.clear() }) { Text("Vider") }
        }

        Text(
            text = state.status,
            style = MaterialTheme.typography.titleMedium,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = (state.stableWeightKg ?: state.lastWeightKg ?: "--.--") + " kg",
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (state.stableWeightKg != null) "stable" else "en cours de stabilisation",
                    style = MaterialTheme.typography.labelMedium,
                )
                state.lastImpedanceOhm?.let {
                    Text(text = "impedance " + it + " ohms")
                }
                Text(text = "trames recues : " + state.frameCount)
                state.deviceAddress?.let {
                    Text(text = (state.deviceName ?: "?") + "  " + it)
                }
            }
        }

        state.lastFrameHex?.let { hex ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = hex,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                    )
                    state.lastFrameReading?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(lines) { line ->
                Text(
                    text = line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }
        }
    }
}
