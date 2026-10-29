package mx.alan.intercom.app.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import mx.alan.intercom.app.EventLog

/**
 * Observa pasivamente todo lo que el teléfono recibe del intercomunicador:
 * enlaces ACL, HFP/SCO, A2DP, comandos AT específicos de fabricante
 * (batería, botones) y altas/bajas de dispositivos de audio.
 *
 * Es el "osciloscopio" del laboratorio para EXP-05 (botones) y EXP-10
 * (reconexión e interrupciones).
 */
@SuppressLint("MissingPermission")
class BluetoothEventMonitor(private val ctx: Context, private val log: EventLog) {
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var registered = false
    var onScoStateChanged: ((connected: Boolean) -> Unit)? = null
    var onHfpDisconnected: (() -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dev = device(i)
            val base = mapOf("device" to dev?.name, "address" to maskMac(dev?.address))
            when (i.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> log.log("bt", "ACL conectado", base)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> log.log("bt", "ACL desconectado", base)
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val s = i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    log.log("hfp", "Conexión HFP: ${BluetoothInventory.stateName(s)}", base)
                    if (s == BluetoothProfile.STATE_DISCONNECTED) onHfpDisconnected?.invoke()
                }
                BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED -> {
                    val s = i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    val name = when (s) {
                        BluetoothHeadset.STATE_AUDIO_CONNECTED -> "SCO_CONECTADO"
                        BluetoothHeadset.STATE_AUDIO_CONNECTING -> "SCO_CONECTANDO"
                        else -> "SCO_DESCONECTADO"
                    }
                    log.log("hfp", "Audio HFP: $name", base)
                    onScoStateChanged?.invoke(s == BluetoothHeadset.STATE_AUDIO_CONNECTED)
                }
                BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT -> {
                    val cmd = i.getStringExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD)
                    val type = i.getIntExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE, -1)
                    @Suppress("DEPRECATION")
                    val args = i.extras?.get(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS) as? Array<*>
                    log.log("hfp-at", "Comando AT de fabricante: $cmd", base + mapOf("type" to type, "args" to args?.map { it.toString() }))
                }
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    val s = i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    log.log("a2dp", "Conexión A2DP: ${BluetoothInventory.stateName(s)}", base)
                }
                BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED -> {
                    val s = i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    log.log("a2dp", if (s == BluetoothA2dp.STATE_PLAYING) "A2DP reproduciendo" else "A2DP detenido", base)
                }
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    val s = i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                    log.log("audio", "SCO (AudioManager): $s")
                }
                ACTION_BATTERY_LEVEL_CHANGED -> {
                    log.log("bt", "Nivel de batería reportado", base + mapOf("level" to i.getIntExtra(EXTRA_BATTERY_LEVEL, -1)))
                }
            }
        }
    }

    private val audioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            added.forEach { log.log("audio", "Dispositivo de audio agregado", BluetoothInventory.audioDevice(it)) }
        }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            removed.forEach { log.log("audio", "Dispositivo de audio removido", BluetoothInventory.audioDevice(it)) }
        }
    }

    fun start() {
        if (registered) return
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT)
            // Categorías de fabricante conocidas: Apple (76, +XAPL/+IPHONEACCEV), Plantronics (85, +XEVENT), Google (224).
            listOf(76, 85, 224).forEach {
                addCategory("${BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY}.$it")
            }
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)
            @Suppress("DEPRECATION")
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
        }
        // Un intent sin categorías coincide con cualquier filtro; las categorías
        // solo restringen los eventos AT de fabricante. Los broadcasts del
        // sistema Bluetooth requieren receptor exportado.
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        else ctx.registerReceiver(receiver, f)
        audio.registerAudioDeviceCallback(audioCallback, Handler(Looper.getMainLooper()))
        registered = true
        log.log("lab", "Monitor Bluetooth iniciado")
    }

    fun stop() {
        if (!registered) return
        ctx.unregisterReceiver(receiver)
        audio.unregisterAudioDeviceCallback(audioCallback)
        registered = false
    }

    private fun device(i: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    companion object {
        // Acción pública en el código fuente de AOSP aunque oculta en el SDK.
        const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
    }
}
