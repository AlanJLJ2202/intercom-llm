package mx.alan.intercom.app.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import mx.alan.intercom.core.lab.BluetoothUuidCatalog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * EXP-01 / EXP-02: inventario no invasivo de lo que el teléfono ve del
 * intercomunicador. Solo lectura: no empareja, no escribe, no cambia ajustes.
 */
@SuppressLint("MissingPermission")
class BluetoothInventory(private val ctx: Context, private val headset: HeadsetProfile) {
    private val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = manager.adapter
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun collect(refreshSdp: Boolean, candidateFilter: (String?) -> Boolean = ::looksLikeIntercom): Map<String, Any?> {
        val a = adapter ?: return mapOf("error" to "Sin adaptador Bluetooth")
        headset.connect()
        val bonded = a.bondedDevices?.toList().orEmpty()
        if (refreshSdp) bonded.filter { candidateFilter(it.name) }.forEach { fetchUuids(it) }
        return mapOf(
            "platform" to mapOf(
                "manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL,
                "android" to Build.VERSION.RELEASE, "sdk" to Build.VERSION.SDK_INT,
            ),
            "adapter" to mapOf(
                "enabled" to a.isEnabled,
                "leSupported" to ctx.packageManager.hasSystemFeature("android.hardware.bluetooth_le"),
                "leAudioSupported" to if (Build.VERSION.SDK_INT >= 33) a.isLeAudioSupported else null,
                "hfpState" to stateName(a.getProfileConnectionState(BluetoothProfile.HEADSET)),
                "a2dpState" to stateName(a.getProfileConnectionState(BluetoothProfile.A2DP)),
            ),
            "bonded" to bonded.map { describe(it) },
            "audioDevices" to audioDevices(),
            "communicationDevice" to if (Build.VERSION.SDK_INT >= 31) audio.communicationDevice?.let(::audioDevice) else null,
            "availableCommunicationDevices" to if (Build.VERSION.SDK_INT >= 31) audio.availableCommunicationDevices.map(::audioDevice) else null,
        )
    }

    fun describe(d: BluetoothDevice): Map<String, Any?> {
        val cls = d.bluetoothClass
        return mapOf(
            "name" to d.name,
            "alias" to if (Build.VERSION.SDK_INT >= 30) d.alias else null,
            "address" to maskMac(d.address),
            "oui" to d.address?.take(8),
            "type" to when (d.type) {
                BluetoothDevice.DEVICE_TYPE_CLASSIC -> "CLASSIC"
                BluetoothDevice.DEVICE_TYPE_LE -> "LE"
                BluetoothDevice.DEVICE_TYPE_DUAL -> "DUAL"
                else -> "UNKNOWN"
            },
            "classOfDevice" to cls?.let {
                mapOf(
                    "raw" to "0x%06X".format(it.hashCode()),
                    "major" to majorName(it.majorDeviceClass),
                    "deviceClass" to "0x%04X".format(it.deviceClass),
                    "audioService" to it.hasService(BluetoothClass.Service.AUDIO),
                    "telephonyService" to it.hasService(BluetoothClass.Service.TELEPHONY),
                )
            },
            "uuids" to d.uuids?.map { BluetoothUuidCatalog.describe(it.uuid.toString()) },
            "hfpConnected" to headset.hfpDevices().any { it.address == d.address },
            "a2dpConnected" to headset.a2dpDevices().any { it.address == d.address },
            "scoAudio" to headset.hfpDevices().firstOrNull { it.address == d.address }?.let { headset.isScoConnected(it) },
            "candidate" to looksLikeIntercom(d.name),
        )
    }

    /** Fuerza una consulta SDP fresca (los UUID en caché pueden estar desactualizados). */
    private fun fetchUuids(d: BluetoothDevice, timeoutMs: Long = 6000) {
        val latch = CountDownLatch(1)
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val dev = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (dev?.address == d.address) latch.countDown()
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_UUID)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(rx, filter, Context.RECEIVER_EXPORTED) else ctx.registerReceiver(rx, filter)
        try {
            d.fetchUuidsWithSdp()
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            ctx.unregisterReceiver(rx)
        }
    }

    private fun audioDevices(): List<Map<String, Any?>> =
        (audio.getDevices(AudioManager.GET_DEVICES_INPUTS) + audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)).map(::audioDevice)

    companion object {
        fun looksLikeIntercom(name: String?): Boolean {
            val n = name?.lowercase() ?: return false
            return listOf("ejeas", "v6", "v4", "ms1", "safe", "intercom").any { it in n }
        }

        fun audioDevice(d: AudioDeviceInfo): Map<String, Any?> = mapOf(
            "id" to d.id,
            "type" to audioTypeName(d.type),
            "product" to d.productName?.toString(),
            "address" to maskMac(d.address),
            "source" to d.isSource,
            "sink" to d.isSink,
            "sampleRates" to d.sampleRates.toList(),
            "channelCounts" to d.channelCounts.toList(),
        )

        fun audioTypeName(t: Int): String = when (t) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
            AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
            else -> "TYPE_$t"
        }

        fun stateName(s: Int) = when (s) {
            BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
            BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
            BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
            else -> "DISCONNECTED"
        }

        private fun majorName(m: Int) = when (m) {
            BluetoothClass.Device.Major.AUDIO_VIDEO -> "AUDIO_VIDEO"
            BluetoothClass.Device.Major.PHONE -> "PHONE"
            BluetoothClass.Device.Major.WEARABLE -> "WEARABLE"
            BluetoothClass.Device.Major.PERIPHERAL -> "PERIPHERAL"
            BluetoothClass.Device.Major.UNCATEGORIZED -> "UNCATEGORIZED"
            else -> "0x%04X".format(m)
        }

    }
}
