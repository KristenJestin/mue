package fr.kristenjestin.mue.data.scale.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import fr.kristenjestin.mue.data.scale.protocol.bluetoothUuid16
import fr.kristenjestin.mue.domain.model.ScaleAdvertisement
import fr.kristenjestin.mue.domain.model.ScaleGattProfile
import fr.kristenjestin.mue.domain.model.ScaleUnavailableReason
import fr.kristenjestin.mue.domain.model.ScaleWrite
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.Locale

/** Étiquette du journal technique de PRD_SCALE 18.5. */
private const val LOG_TAG = "MueScale"

/** Le descripteur de configuration de client, `0x2902`, que tout abonnement GATT écrit. */
private val CLIENT_CONFIGURATION_DESCRIPTOR = bluetoothUuid16(0x2902)

/**
 * Profondeur de la file de trames.
 *
 * La balance émet une trame de poids toutes les quelques dizaines de millisecondes, et l'appelant
 * peut rester suspendu le temps d'une écriture acquittée. La file absorbe cette avance ; le spike a
 * relevé 201 notifications sur une session complète, donc soixante-quatre trames de retard sont
 * très au-delà de ce qui a été observé, et une trame perdue vaut mieux qu'une mémoire qui enfle.
 */
private const val FRAME_BUFFER = 64

/**
 * La liaison Bluetooth réelle (PRD_SCALE 21.2).
 *
 * **Ce fichier est le seul du module à importer `android.bluetooth`**, et il ne contient aucune
 * règle métier : pas de fenêtre de deux minutes, pas de bornes de poids, pas de `sessionId`. Tout
 * cela vit dans [BleScaleSessionSource], qui se teste sans lui.
 *
 * Ce qui suit vient du spike validé sur la Homebuds HB9027 le 26 août 2026. Trois pièges y ont été
 * payés, et chacun est silencieux :
 *
 * 1. **Une opération GATT à la fois.** La pile d'Android n'en accepte qu'une en vol et rejette les
 *    suivantes sans erreur. Les trois commandes d'initialisation enchaînées sans attente en perdent
 *    deux, et la pesée reste bloquée en attente d'un flux de poids qui ne viendra jamais
 *    (PRD_SCALE 14.3). [AndroidScaleLink.write] délègue à [ScaleWriteQueue], qui sérialise et ne
 *    rend la main qu'à l'acquittement **de cette écriture-là**.
 * 2. **L'abonnement avant la séquence.** Écrire la commande de démarrage avant que le descripteur
 *    `0x2902` soit écrit revient à parler dans le vide. [connect] ne rend donc la main qu'après
 *    `onDescriptorWrite`.
 * 3. **Le scan sans filtre.** Un filtre sur l'adresse serait aveugle le jour où elle change — elle
 *    est statique aléatoire (PRD_SCALE 10.1) — et un scan filtré qui ne rend rien est
 *    indistinguable d'une balance qui ne s'est pas réveillée. Le rattachement proposé de
 *    FR-SCALE-001 a besoin de voir les appareils dont l'adresse est inconnue pour exister. Le coût,
 *    en batterie, est borné par la fenêtre de deux minutes de FR-SCALE-020.
 *
 * `@SuppressLint("MissingPermission")` est ici légitime et non un contournement : [availability]
 * est le préalable de tout appel, et [BleScaleSessionSource] le consulte avant d'ouvrir la moindre
 * session. Lint ne sait pas suivre une garde qui vit dans une autre classe.
 */
@SuppressLint("MissingPermission")
internal class AndroidScaleTransport(
    private val context: Context,
    private val log: ScaleLog = AndroidScaleLog,
) : ScaleTransport {

    private val adapter: BluetoothAdapter?
        get() = ContextCompat.getSystemService(context, BluetoothManager::class.java)?.adapter

    /**
     * Les trois conditions de PRD_SCALE 16.1 et 18.5, telles que [ScaleAvailability] les énonce.
     *
     * Rien n'est décidé ici : `ScalePermissionsState` lit le même objet pour choisir laquelle des
     * phrases de PRD_SCALE 18.5 montrer, et c'est ce partage qui garantit qu'un écran n'expliquera
     * jamais une cause pendant que cette liaison en refuse une autre.
     */
    override fun availability(): ScaleUnavailableReason? = ScaleAvailability.reason(context)

    override fun scan(): Flow<ScaleAdvertisement> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
            ?: throw ScaleTransportException("no BLE scanner on this device")

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(result.toAdvertisement())
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { trySend(it.toAdvertisement()) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(ScaleTransportException("BLE scan failed with code $errorCode"))
            }
        }

        val settings = ScanSettings.Builder()
            // La fenêtre est de deux minutes et l'utilisateur attend debout sur une balance :
            // c'est exactement la situation où la latence prime sur la consommation.
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()

        try {
            scanner.startScan(emptyList(), settings, callback)
        } catch (failure: Throwable) {
            close(ScaleTransportException("startScan refused", failure))
        }

        awaitClose {
            // Appelé quand la machine trouve sa candidate, quand la session se ferme et quand
            // l'écran disparaît : c'est ce qui donne « aucun scan en arrière-plan » gratuitement.
            runCatching { scanner.stopScan(callback) }
                .onFailure { log.log("stopScan failed: ${it.message}") }
        }
    }

    override suspend fun connect(
        advertisement: ScaleAdvertisement,
        profile: ScaleGattProfile,
    ): ScaleLink {
        val device = runCatching { adapter?.getRemoteDevice(advertisement.address) }.getOrNull()
            ?: throw ScaleTransportException("unknown device ${advertisement.address}")
        return AndroidScaleLink(profile, log).open(context, device)
    }

    /**
     * L'annonce, telle que les pilotes la lisent (PRD_SCALE 9.2).
     *
     * Le nom vient d'abord de l'annonce et seulement ensuite de l'appareil : `BluetoothDevice.name`
     * lit le cache du système et exige `BLUETOOTH_CONNECT` à partir d'Android 12, alors que le nom
     * porté par l'annonce est celui que la balance vient de diffuser. Les UUID sont normalisés en
     * minuscules et en forme 128 bits, parce que le contrat de [ScaleAdvertisement] le demande —
     * sans quoi chaque pilote devrait réimplémenter la comparaison des formes courte et longue.
     */
    private fun ScanResult.toAdvertisement(): ScaleAdvertisement {
        val record = scanRecord
        val manufacturerData = buildMap {
            val data = record?.manufacturerSpecificData
            if (data != null) {
                for (index in 0 until data.size()) put(data.keyAt(index), data.valueAt(index))
            }
        }
        return ScaleAdvertisement(
            address = device.address,
            name = record?.deviceName ?: runCatching { device.name }.getOrNull(),
            serviceUuids = record?.serviceUuids.orEmpty()
                .map { it.uuid.toString().lowercase(Locale.ROOT) },
            manufacturerData = manufacturerData,
        )
    }
}

/**
 * Une liaison GATT, du `connectGatt` à la fermeture.
 *
 * Le pont entre les callbacks d'Android — qui arrivent sur un fil de liaison quelconque — et les
 * coroutines de l'appelant tient en trois objets : un [CompletableDeferred] pour l'abonnement, une
 * file pour les trames, une [ScaleWriteQueue] pour les écritures. Aucun `Handler`, contrairement au
 * spike : la sérialisation qu'il obtenait par une file d'opérations sur le fil principal, une
 * fonction suspendante l'obtient par sa propre séquence d'exécution.
 *
 * **Cette classe ne fait plus que de la plomberie.** Tout ce qui est une règle — un seul GATT en
 * vol, quelle écriture un acquittement acquitte, le chien de garde, la traduction en
 * [ScaleTransportException] — vit dans [ScaleWriteQueue], qui ne connaît ni `BluetoothGatt` ni
 * `Looper` et se couvre donc entièrement en JVM pure (PRD_SCALE 21.3, 23).
 */
@SuppressLint("MissingPermission")
private class AndroidScaleLink(
    private val profile: ScaleGattProfile,
    private val log: ScaleLog,
) : ScaleLink {

    private val frames = Channel<ByteArray>(FRAME_BUFFER)

    /** Complété quand l'abonnement est effectif, en échec si la liaison meurt avant. */
    private val subscribed = CompletableDeferred<Unit>()

    /**
     * La sérialisation des écritures (PRD_SCALE 14.3).
     *
     * L'émetteur relit [gatt] et [writeCharacteristic] à chaque émission plutôt que de les capturer :
     * ils ne sont connus qu'après `onServicesDiscovered`, c'est-à-dire bien après la construction de
     * cette liaison.
     */
    private val writes = ScaleWriteQueue(log) { bytes ->
        val client = gatt ?: throw ScaleTransportException("write without a GATT client")
        val characteristic = writeCharacteristic
            ?: throw ScaleTransportException("write characteristic ${profile.write} missing")
        send(client, characteristic, bytes)
    }

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var closed = false

    suspend fun open(context: Context, device: BluetoothDevice): ScaleLink {
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw ScaleTransportException("connectGatt returned no client")
        try {
            // PRD_SCALE 14.3 : rendre la main **après** l'abonnement, jamais avant.
            subscribed.await()
        } catch (failure: Throwable) {
            close()
            throw failure
        }
        return this
    }

    override suspend fun nextFrame(): ByteArray? = frames.receiveCatching().getOrNull()

    override suspend fun write(write: ScaleWrite) {
        if (closed) throw ScaleTransportException("write on a closed link")
        writes.write(write)
    }

    override fun close() {
        if (closed) return
        closed = true
        frames.close()
        writes.fail("link closed")
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(client: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED ->
                    if (!client.discoverServices()) fail("discoverServices refused")

                BluetoothGatt.STATE_DISCONNECTED ->
                    // FR-SCALE-021 : la balance interrompt la liaison quelques secondes après une
                    // mesure. C'est la norme, pas une anomalie, et cela n'affiche rien.
                    onLinkLost("disconnected, status=$status")
            }
        }

        override fun onServicesDiscovered(client: BluetoothGatt, status: Int) {
            val service = client.getService(profile.service)
                ?: return fail("service ${profile.service} absent, status=$status")
            val notify = service.getCharacteristic(profile.notify)
                ?: return fail("characteristic ${profile.notify} absent")
            writeCharacteristic = service.getCharacteristic(profile.write)
                ?: return fail("characteristic ${profile.write} absent")

            if (!client.setCharacteristicNotification(notify, true)) {
                return fail("setCharacteristicNotification refused")
            }
            val descriptor = notify.getDescriptor(CLIENT_CONFIGURATION_DESCRIPTOR)
                ?: return fail("no client configuration descriptor on ${profile.notify}")
            val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            if (!writeDescriptor(client, descriptor, value)) fail("descriptor write refused")
        }

        override fun onDescriptorWrite(
            client: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                subscribed.complete(Unit)
            } else {
                fail("descriptor write failed, status=$status")
            }
        }

        override fun onCharacteristicChanged(
            client: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = deliver(characteristic, value)

        @Deprecated("Remplacée en API 33 par la surcharge qui porte la valeur")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            client: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            // Les deux surcharges existent en API 33 et la plateforme n'appelle que la nouvelle :
            // sans ce test, chaque trame serait livrée deux fois.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                deliver(characteristic, characteristic.value ?: ByteArray(0))
            }
        }

        /**
         * L'acquittement d'une écriture — **pas** forcément de celle qu'on attend.
         *
         * La corrélation est faite par [ScaleWriteQueue] sur un rang croissant, et non ici sur la
         * caractéristique : toutes les écritures de la séquence portent la même, `0xFFF2`, si bien
         * qu'une comparaison d'UUID laisserait passer l'acquittement tardif d'une écriture déjà
         * abandonnée par le chien de garde. Aucun filtrage n'a lieu ici non plus : chaque appel
         * correspond à une émission de ce client et doit donc consommer exactement un rang, sans
         * quoi tous les acquittements suivants seraient décalés (PRD_SCALE 14.3).
         */
        override fun onCharacteristicWrite(
            client: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) = writes.acknowledge(
            failure = if (status == BluetoothGatt.GATT_SUCCESS) {
                null
            } else {
                "write rejected by the scale, status=$status"
            },
        )
    }

    /** Seule la caractéristique de notification du pilote compte ; le reste est du bavardage. */
    private fun deliver(characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
        if (characteristic.uuid != profile.notify) return
        if (frames.trySend(bytes).isFailure) log.log("frame dropped, queue full or closed")
    }

    private fun fail(reason: String) {
        log.log("scale link failed: $reason")
        onLinkLost(reason)
    }

    /** La liaison n'existe plus : les trames s'arrêtent, l'attente en cours est libérée. */
    private fun onLinkLost(reason: String) {
        subscribed.completeExceptionally(ScaleTransportException(reason))
        frames.close()
        writes.fail(reason)
    }

    private fun send(
        client: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray,
    ): Boolean {
        val type = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        /*
         * `BluetoothStatusCodes.SUCCESS` et non `BluetoothGatt.GATT_SUCCESS`.
         *
         * Les deux valent `0`, donc le comportement est le même, mais la surcharge d'API 33 rend un
         * code de la famille `BluetoothStatusCodes` — pas un statut GATT. Comparer à l'autre famille
         * est ce que `WrongConstant` signale, et il a raison : les deux ensembles divergent dès la
         * première valeur d'erreur, et une lecture d'un code de retour dans la mauvaise table est
         * exactement le genre de bogue qu'aucun test de protocole ne peut voir.
         */
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            client.writeCharacteristic(characteristic, bytes, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.writeType = type
                characteristic.value = bytes
                client.writeCharacteristic(characteristic)
            }
        }
    }

    private fun writeDescriptor(
        client: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // Même famille de codes que [send], pour la même raison.
        client.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
    } else {
        @Suppress("DEPRECATION")
        run {
            descriptor.value = value
            client.writeDescriptor(descriptor)
        }
    }
}

/**
 * Le journal technique du module sur Android (PRD_SCALE 18.5).
 *
 * Il n'existe que pour les trames incohérentes et les pannes de liaison, « journalisées en interne »
 * pendant que l'utilisateur voit une absence de mesure et non une erreur de protocole. Volume borné
 * par construction : rien ici n'est écrit hors d'une session de deux minutes.
 */
internal object AndroidScaleLog : ScaleLog {
    override fun log(message: String) {
        Log.w(LOG_TAG, message)
    }
}
