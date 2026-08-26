package fr.kristenjestin.muespike

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.ArrayDeque
import java.util.UUID

/**
 * Scans for the scale, connects, replays the vendor handshake, and logs every byte that comes
 * back.
 *
 * The scale only wakes when someone stands on it, only accepts a connection during that window,
 * and drops the link seconds later. So this never stops on its own: it scans, connects, talks,
 * loses the device, and goes straight back to scanning until [stop] is called. The user should
 * be able to launch it, put the phone down, and step on the scale whenever.
 */
@SuppressLint("MissingPermission")
class ScaleSpike(private val context: Context) {

    data class State(
        val running: Boolean = false,
        val status: String = "a l'arret",
        val deviceName: String? = null,
        val deviceAddress: String? = null,
        val lastFrameHex: String? = null,
        val lastFrameReading: String? = null,
        val lastWeightKg: String? = null,
        val lastImpedanceOhm: Int? = null,
        val stableWeightKg: String? = null,
        val frameCount: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())

    private val manager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val adapter: BluetoothAdapter? get() = manager?.adapter

    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var running = false
    private var connecting = false

    /** Addresses already logged this scan, so the unfiltered scan does not drown the log. */
    private val seen = mutableSetOf<String>()

    /** One ack per connection: the scale repeats its stable frame, and we need not repeat with it. */
    private var ackedWeight = false
    private var ackedImpedance = false

    /**
     * Matched on the name first, since the address is a random static one that a battery
     * change may have rotated. `BODY FAT` alone is kept as a fallback in case the vendor
     * renamed it, and is specific enough not to catch a phone or a pair of earbuds.
     */
    private fun isTarget(name: String?, address: String): Boolean {
        if (address.equals(TARGET_ADDRESS, ignoreCase = true)) return true
        val n = name ?: return false
        return n.equals(TARGET_NAME, ignoreCase = true) || n.contains("BODY FAT", ignoreCase = true)
    }

    // --- GATT operation queue -------------------------------------------------------------
    //
    // Android runs one GATT operation at a time. Firing the three init frames back to back is
    // the classic way to have two of them vanish with no error at all, so everything goes
    // through this queue and waits for its own callback.

    private sealed interface Op {
        val label: String

        data class Subscribe(
            val characteristic: BluetoothGattCharacteristic,
            override val label: String,
        ) : Op

        class Write(
            val characteristic: BluetoothGattCharacteristic,
            val bytes: ByteArray,
            override val label: String,
        ) : Op
    }

    private val queue = ArrayDeque<Op>()
    private var inFlight: Op? = null

    private val watchdog = Runnable {
        val stuck = inFlight
        if (stuck != null) {
            SpikeLog.w("[watchdog] pas de callback pour \"" + stuck.label + "\" apres 4 s, on force la suite")
            inFlight = null
            pump()
        }
    }

    // --- Public control -------------------------------------------------------------------

    fun start() {
        if (running) return
        val bt = adapter
        if (bt == null) {
            SpikeLog.e("aucun adaptateur Bluetooth sur cet appareil")
            return
        }
        if (!bt.isEnabled) {
            SpikeLog.e("le Bluetooth est eteint - active-le puis relance")
            return
        }
        running = true
        _state.update { it.copy(running = true) }
        SpikeLog.i("[start] cible \"" + TARGET_NAME + "\" / " + TARGET_ADDRESS)
        SpikeLog.i(
            "trames d'init pretes : " +
                ScaleProtocol.INIT_FRAMES.joinToString(" / ") { ScaleProtocol.hex(it) }
        )
        startScan()
    }

    fun stop() {
        if (!running) return
        running = false
        SpikeLog.i("[stop] arret demande")
        stopScan()
        teardownGatt()
        _state.update { it.copy(running = false, status = "a l'arret") }
    }

    // --- Scanning -------------------------------------------------------------------------

    private fun startScan() {
        if (!running || scanning || connecting) return
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            SpikeLog.e("scanner BLE indisponible")
            return
        }

        // Deliberately unfiltered.
        //
        // Filtering on the name and address we saw in nRF Connect would be tighter, but it
        // would also make this blind in the one case we cannot rule out: FF:10:00:1F:52:C3 is
        // a random static address, which a peripheral is free to regenerate when it powers
        // back up — and the next run happens after a battery change. A filtered scan that
        // returns nothing looks exactly like a scale that never woke up, and we would have no
        // way to tell those apart. Unfiltered, the log answers the question.
        //
        // The cost is that every nearby device is reported; `seen` keeps the log readable.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()

        scanning = true
        seen.clear()
        setStatus("scan en cours - monte sur la balance")
        SpikeLog.i("[scan] lance, sans filtre")
        runCatching { scanner.startScan(emptyList(), settings, scanCallback) }
            .onFailure {
                scanning = false
                SpikeLog.e("startScan a echoue", it)
            }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
            .onFailure { SpikeLog.w("stopScan a echoue : " + it.message) }
        SpikeLog.i("[scan] arrete")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val record = result.scanRecord
            val name = device.name ?: record?.deviceName
            val target = isTarget(name, device.address)

            // The target is logged on every single advertisement, because if this scale turns
            // out to broadcast its weight rather than serve it over GATT, the payload changes
            // between adverts and the diff is the whole finding. Everything else is logged
            // once, just to prove the scan is alive and to catch a renamed scale.
            if (target || seen.add(device.address)) {
                SpikeLog.i(
                    (if (target) "[CIBLE] " else "[vu] ") +
                        "\"" + (name ?: "?") + "\" " + device.address + " rssi=" + result.rssi
                )
                SpikeLog.i(
                    "   advertising brut : " +
                        (record?.bytes?.let { ScaleProtocol.hex(it) } ?: "(vide)")
                )
                record?.serviceUuids?.let { uuids ->
                    if (uuids.isNotEmpty()) {
                        SpikeLog.i("   services annonces : " + uuids.joinToString(", "))
                    }
                }
                record?.manufacturerSpecificData?.let { data ->
                    for (i in 0 until data.size()) {
                        val id = data.keyAt(i)
                        SpikeLog.i(
                            "   manufacturer " + String.format("0x%04X", id) + " : " +
                                ScaleProtocol.hex(data.valueAt(i))
                        )
                    }
                }
                record?.serviceData?.forEach { (uuid, bytes) ->
                    SpikeLog.i("   service data " + uuid + " : " + ScaleProtocol.hex(bytes))
                }
            }

            if (!target) return
            if (connecting || gatt != null) return
            connecting = true
            stopScan()
            connect(device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            SpikeLog.e("echec du scan, code=" + errorCode)
            retryLater("echec de scan")
        }
    }

    // --- Connection -----------------------------------------------------------------------

    private fun connect(device: BluetoothDevice) {
        setStatus("connexion a " + device.address)
        _state.update { it.copy(deviceName = device.name, deviceAddress = device.address) }
        SpikeLog.i("[connexion] \"" + (device.name ?: "?") + "\" " + device.address)
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun teardownGatt() {
        handler.removeCallbacks(watchdog)
        queue.clear()
        inFlight = null
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        connecting = false
    }

    private fun retryLater(reason: String) {
        if (!running) return
        setStatus(reason + " - nouvelle tentative")
        handler.postDelayed(
            {
                if (running) {
                    teardownGatt()
                    startScan()
                }
            },
            RETRY_DELAY_MS,
        )
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    SpikeLog.i("[connecte] status=" + status + " - decouverte des services")
                    setStatus("connecte, decouverte des services")
                    handler.post { g.discoverServices() }
                }

                BluetoothGatt.STATE_DISCONNECTED -> {
                    SpikeLog.w("[deconnecte] status=" + status)
                    connecting = false
                    retryLater("deconnecte")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            SpikeLog.i("[services] decouverts, status=" + status)
            dumpServices(g)
            planSession(g)
        }

        // API 33+ delivers the value with the callback; below that it is read off the object.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = onFrame(characteristic.uuid, value)

        @Deprecated("Remplace en API 33 par la surcharge qui porte la valeur")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                onFrame(characteristic.uuid, characteristic.value ?: ByteArray(0))
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            SpikeLog.i(
                "[lecture] " + short(characteristic.uuid) + " = " + ScaleProtocol.hex(value) +
                    " (status=" + status + ")"
            )
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            SpikeLog.i(
                "[ecriture] terminee \"" + (inFlight?.label ?: "?") + "\" status=" + status
            )
            if (status != BluetoothGatt.GATT_SUCCESS) {
                SpikeLog.w("   attention : l'ecriture a ete refusee par la balance")
            }
            advance()
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            SpikeLog.i(
                "[abonnement] termine \"" + (inFlight?.label ?: "?") + "\" status=" + status
            )
            advance()
        }
    }

    // --- Session plan ---------------------------------------------------------------------

    private fun dumpServices(g: BluetoothGatt) {
        for (service in g.services) {
            SpikeLog.i("service " + short(service.uuid))
            for (c in service.characteristics) {
                SpikeLog.i("   char " + short(c.uuid) + " props=" + properties(c.properties))
                for (d in c.descriptors) {
                    SpikeLog.i("      desc " + short(d.uuid))
                }
            }
        }
    }

    /**
     * Order is chosen for the short window the scale gives us: the characteristic we expect the
     * weight on goes first, the two standard body-composition ones next because they are cheap
     * and would beat the proprietary path outright, then the handshake, then everything else
     * that can notify.
     */
    private fun planSession(g: BluetoothGatt) {
        queue.clear()
        inFlight = null
        // A reconnection is a fresh weigh-in and must be able to ack again.
        ackedWeight = false
        ackedImpedance = false

        val vendorNotify = g.characteristic(SERVICE_VENDOR, CHAR_VENDOR_NOTIFY)
        val vendorWrite = g.characteristic(SERVICE_VENDOR, CHAR_VENDOR_WRITE)

        if (vendorNotify != null) {
            queue += Op.Subscribe(vendorNotify, "notify FFF1")
        } else {
            SpikeLog.e("FFF1 introuvable - le chemin proprietaire est mort, on ecoute le reste")
        }

        val bodyComposition = g.characteristic(SERVICE_BODY_COMPOSITION, CHAR_BODY_COMPOSITION)
        if (bodyComposition != null) {
            queue += Op.Subscribe(bodyComposition, "notify 2A9C (Body Composition)")
        } else {
            SpikeLog.i("181B/2A9C absent")
        }

        val weightMeasurement = g.characteristic(SERVICE_WEIGHT_SCALE, CHAR_WEIGHT_MEASUREMENT)
        if (weightMeasurement != null) {
            queue += Op.Subscribe(weightMeasurement, "notify 2A9D (Weight Scale)")
        } else {
            SpikeLog.i("181D/2A9D absent")
        }

        if (vendorWrite != null) {
            ScaleProtocol.INIT_FRAMES.forEachIndexed { index, frame ->
                queue += Op.Write(
                    vendorWrite,
                    frame,
                    "init " + (index + 1) + "/3 -> " + ScaleProtocol.hex(frame),
                )
            }
        } else {
            SpikeLog.e("FFF2 introuvable - impossible d'envoyer le handshake")
        }

        // Anything else that can talk. Cheap, and this is the run where we find out what the
        // scale volunteers on its own.
        val alreadyQueued = queue.filterIsInstance<Op.Subscribe>()
            .map { it.characteristic.uuid }
            .toSet()
        for (service in g.services) {
            for (c in service.characteristics) {
                if (c.uuid in alreadyQueued) continue
                if (!c.canNotify()) continue
                queue += Op.Subscribe(c, "notify " + short(c.uuid) + " (exploratoire)")
            }
        }

        SpikeLog.i("[file] " + queue.size + " operations")
        setStatus("handshake en cours")
        pump()
    }

    private fun advance() {
        handler.removeCallbacks(watchdog)
        inFlight = null
        // A breath between operations: cheap peripherals drop the next one otherwise.
        handler.postDelayed({ pump() }, OP_GAP_MS)
    }

    private fun pump() {
        if (inFlight != null) return
        val g = gatt ?: return
        val next = queue.poll()
        if (next == null) {
            SpikeLog.i("[file] vide - en ecoute")
            setStatus("en ecoute, monte sur la balance")
            return
        }
        inFlight = next
        handler.postDelayed(watchdog, WATCHDOG_MS)

        val ok = when (next) {
            is Op.Subscribe -> subscribe(g, next.characteristic)
            is Op.Write -> write(g, next.characteristic, next.bytes)
        }
        SpikeLog.i("[emission] \"" + next.label + "\" accepte=" + ok)
        if (!ok) advance()
    }

    private fun subscribe(g: BluetoothGatt, c: BluetoothGattCharacteristic): Boolean {
        if (!g.setCharacteristicNotification(c, true)) {
            SpikeLog.w("setCharacteristicNotification refuse sur " + short(c.uuid))
            return false
        }
        val cccd = c.getDescriptor(CCCD)
        if (cccd == null) {
            // No CCCD means nothing to write; the local enable above is all there is.
            SpikeLog.w("pas de CCCD sur " + short(c.uuid) + ", abonnement local seulement")
            return false
        }
        val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = value
                g.writeDescriptor(cccd)
            }
        }
    }

    private fun write(g: BluetoothGatt, c: BluetoothGattCharacteristic, bytes: ByteArray): Boolean {
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, bytes, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                c.writeType = type
                c.value = bytes
                g.writeCharacteristic(c)
            }
        }
    }

    // --- Frames ---------------------------------------------------------------------------

    /** Every notification, from any characteristic, raw first and interpreted second. */
    private fun onFrame(source: UUID, bytes: ByteArray) {
        val hex = ScaleProtocol.hex(bytes)
        SpikeLog.i("[recu] " + short(source) + " : " + hex)

        val isVendor = source == CHAR_VENDOR_NOTIFY
        val reading = if (isVendor) ScaleProtocol.describe(bytes) else "(hors FFF1, non decode)"
        if (isVendor) SpikeLog.i("   -> " + reading)

        _state.update {
            it.copy(
                lastFrameHex = hex,
                lastFrameReading = reading,
                frameCount = it.frameCount + 1,
            )
        }

        if (!isVendor) return

        val decoded = ScaleProtocol.decode(bytes)
        val raw = decoded.weightHundredthsKg
        if (raw != null) {
            _state.update { it.copy(lastWeightKg = ScaleProtocol.formatKg(raw)) }
        }

        if (decoded.isStableWeight && raw != null) {
            val kg = ScaleProtocol.formatKg(raw)
            SpikeLog.i("[POIDS STABLE] " + kg + " kg")
            _state.update { it.copy(stableWeightKg = kg) }
            setStatus("poids stable " + kg + " kg - on demande l'impedance")
            // The scale repeats the stable frame; acking each repeat would queue a pile of
            // writes into the short window we have, and bury the impedance frame we want.
            if (!ackedWeight) {
                ackedWeight = true
                enqueueAck(ScaleProtocol.ACK_WEIGHT, "ack poids stable")
            }
        }

        if (decoded.isImpedance) {
            SpikeLog.i("[IMPEDANCE] " + decoded.impedanceOhm + " ohms")
            _state.update { it.copy(lastImpedanceOhm = decoded.impedanceOhm) }
            if (!ackedImpedance) {
                ackedImpedance = true
                enqueueAck(ScaleProtocol.ACK_IMPEDANCE, "ack impedance")
            }
        }
    }

    private fun enqueueAck(frame: ByteArray, label: String) {
        val g = gatt ?: return
        val target = g.characteristic(SERVICE_VENDOR, CHAR_VENDOR_WRITE) ?: return
        queue += Op.Write(target, frame, label + " -> " + ScaleProtocol.hex(frame))
        pump()
    }

    // --- Helpers --------------------------------------------------------------------------

    private fun setStatus(text: String) = _state.update { it.copy(status = text) }

    private fun BluetoothGatt.characteristic(service: UUID, characteristic: UUID) =
        getService(service)?.getCharacteristic(characteristic)

    private fun BluetoothGattCharacteristic.canNotify(): Boolean {
        val mask = BluetoothGattCharacteristic.PROPERTY_NOTIFY or
            BluetoothGattCharacteristic.PROPERTY_INDICATE
        return properties and mask != 0
    }

    private fun properties(bits: Int): String {
        val names = buildList {
            if (bits and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
            if (bits and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
            if (bits and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NR")
            if (bits and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
            if (bits and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
        }
        return if (names.isEmpty()) String.format("0x%02X", bits) else names.joinToString("|")
    }

    /** `0000fff1-0000-1000-8000-00805f9b34fb` reads as `FFF1`. */
    private fun short(uuid: UUID): String {
        val text = uuid.toString()
        return if (text.startsWith("0000") && text.endsWith(BASE_UUID_SUFFIX)) {
            text.substring(4, 8).uppercase()
        } else {
            text
        }
    }

    companion object {
        const val TARGET_NAME = "HB BODY FAT"
        const val TARGET_ADDRESS = "FF:10:00:1F:52:C3"

        private const val BASE_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb"
        private const val RETRY_DELAY_MS = 800L
        private const val OP_GAP_MS = 120L
        private const val WATCHDOG_MS = 4_000L

        private fun uuid16(short: Int): UUID =
            UUID.fromString(String.format("0000%04x", short) + BASE_UUID_SUFFIX)

        private val SERVICE_VENDOR = uuid16(0xFFF0)
        private val CHAR_VENDOR_NOTIFY = uuid16(0xFFF1)
        private val CHAR_VENDOR_WRITE = uuid16(0xFFF2)

        private val SERVICE_BODY_COMPOSITION = uuid16(0x181B)
        private val CHAR_BODY_COMPOSITION = uuid16(0x2A9C)

        private val SERVICE_WEIGHT_SCALE = uuid16(0x181D)
        private val CHAR_WEIGHT_MEASUREMENT = uuid16(0x2A9D)

        private val CCCD = uuid16(0x2902)
    }
}
