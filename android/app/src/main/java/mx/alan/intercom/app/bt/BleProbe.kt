package mx.alan.intercom.app.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import mx.alan.intercom.app.EventLog
import mx.alan.intercom.core.lab.BluetoothUuidCatalog
import java.util.ArrayDeque

/**
 * Hipótesis 2 (app SafeRiding): el intercomunicador expone un servicio BLE
 * de configuración. Esta sonda es ESTRICTAMENTE de solo lectura:
 *  - escanea anuncios BLE;
 *  - opcionalmente conecta GATT, descubre servicios y LEE características
 *    con propiedad READ. Nunca escribe, nunca suscribe notificaciones,
 *    nunca inicia DFU.
 */
@SuppressLint("MissingPermission")
class BleProbe(private val ctx: Context, private val log: EventLog) {
    private val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val main = Handler(Looper.getMainLooper())
    private val seen = linkedMapOf<String, ScanResult>()

    fun scan(durationMs: Long = 15_000, onDone: (List<ScanResult>) -> Unit) {
        val scanner = adapter?.bluetoothLeScanner ?: run { log.log("ble", "BLE no disponible"); onDone(emptyList()); return }
        seen.clear()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val key = r.device.address
                if (key !in seen) {
                    log.log("ble", "Anuncio BLE: ${r.device.name ?: r.scanRecord?.deviceName ?: "(sin nombre)"}", describe(r))
                }
                seen[key] = r
            }
            override fun onScanFailed(errorCode: Int) = log.log("ble", "Escaneo falló: $errorCode")
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, cb)
        log.log("ble", "Escaneo BLE iniciado (${durationMs / 1000} s)")
        main.postDelayed({
            scanner.stopScan(cb)
            val list = seen.values.sortedByDescending { it.rssi }
            log.log("ble", "Escaneo BLE terminado: ${list.size} dispositivos")
            onDone(list)
        }, durationMs)
    }

    fun describe(r: ScanResult): Map<String, Any?> {
        val rec = r.scanRecord
        val mfr = rec?.manufacturerSpecificData
        val companies = (0 until (mfr?.size() ?: 0)).map { i ->
            mapOf("companyId" to "0x%04X".format(mfr!!.keyAt(i)), "bytes" to mfr.valueAt(i)?.size)
        }
        return mapOf(
            "name" to (r.device.name ?: rec?.deviceName),
            "address" to maskMac(r.device.address),
            "oui" to r.device.address?.take(8),
            "rssi" to r.rssi,
            "connectable" to if (Build.VERSION.SDK_INT >= 26) r.isConnectable else null,
            "serviceUuids" to rec?.serviceUuids?.map { BluetoothUuidCatalog.describe(it.uuid.toString()) },
            "manufacturerData" to companies,
            "candidate" to BluetoothInventory.looksLikeIntercom(r.device.name ?: rec?.deviceName),
        )
    }

    /** Descubre servicios GATT y lee solo características legibles. */
    fun dumpGatt(device: BluetoothDevice, onDone: (Map<String, Any?>) -> Unit) {
        val report = mutableMapOf<String, Any?>("device" to device.name, "address" to maskMac(device.address))
        val services = mutableListOf<Map<String, Any?>>()
        val reads = ArrayDeque<BluetoothGattCharacteristic>()
        val values = mutableMapOf<String, Any?>()
        var finished = false

        fun finish(gatt: BluetoothGatt?, reason: String) {
            if (finished) return
            finished = true
            gatt?.disconnect(); gatt?.close()
            report["services"] = services
            report["reads"] = values
            report["end"] = reason
            log.log("gatt", "Volcado GATT terminado: $reason", report)
            main.post { onDone(report) }
        }

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                log.log("gatt", "Estado GATT: $newState (status $status)")
                if (newState == BluetoothProfile.STATE_CONNECTED) gatt.discoverServices()
                else if (newState == BluetoothProfile.STATE_DISCONNECTED) finish(gatt, "desconectado (status $status)")
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                gatt.services.forEach { s ->
                    services += mapOf(
                        "service" to BluetoothUuidCatalog.describe(s.uuid.toString()),
                        "characteristics" to s.characteristics.map { c ->
                            if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) reads.add(c)
                            mapOf("uuid" to c.uuid.toString(), "properties" to props(c.properties))
                        },
                    )
                }
                readNext(gatt)
            }

            @Deprecated("API < 33")
            override fun onCharacteristicRead(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                @Suppress("DEPRECATION")
                record(c, c.value, status); readNext(gatt)
            }

            override fun onCharacteristicRead(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                record(c, value, status); readNext(gatt)
            }

            private fun record(c: BluetoothGattCharacteristic, v: ByteArray?, status: Int) {
                values[c.uuid.toString()] = mapOf(
                    "status" to status,
                    "hex" to v?.joinToString("") { "%02x".format(it) },
                    "ascii" to v?.let { b -> String(b.map { if (it in 32..126) it.toInt().toChar() else '.' }.toCharArray()) },
                )
            }

            private fun readNext(gatt: BluetoothGatt) {
                val next = reads.pollFirst() ?: return finish(gatt, "completo")
                @Suppress("DEPRECATION")
                if (!gatt.readCharacteristic(next)) readNext(gatt)
            }
        }
        log.log("gatt", "Conectando GATT (solo lectura) a ${device.name}")
        val gatt = if (Build.VERSION.SDK_INT >= 23) device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE) else device.connectGatt(ctx, false, cb)
        main.postDelayed({ finish(gatt, "timeout") }, 20_000)
    }

    private fun props(p: Int) = buildList {
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NR")
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
    }
}
