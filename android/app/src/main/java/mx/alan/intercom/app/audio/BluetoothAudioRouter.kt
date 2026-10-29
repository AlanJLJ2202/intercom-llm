package mx.alan.intercom.app.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import mx.alan.intercom.app.EventLog
import mx.alan.intercom.app.RouteStrategy
import mx.alan.intercom.app.bt.BluetoothInventory
import mx.alan.intercom.app.bt.HeadsetProfile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Abre y cierra el enlace de voz bidireccional (HFP/SCO) con el casco.
 *
 * Tres estrategias experimentales (EXP-04):
 *  - COMMUNICATION_DEVICE: AudioManager.setCommunicationDevice (API 31+;
 *    startBluetoothSco en versiones previas). Android lo trata como VoIP.
 *  - VOICE_RECOGNITION: BluetoothHeadset.startVoiceRecognition, que envía
 *    +BVRA:1 al casco; semánticamente es "sesión de asistente".
 *  - BOTH: ambas, para comparar qué deja el micrófono del casco activo.
 */
@SuppressLint("MissingPermission")
class BluetoothAudioRouter(
    private val ctx: Context,
    private val headset: HeadsetProfile,
    private val log: EventLog,
) {
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var vrDevice: BluetoothDevice? = null
    private var usedLegacySco = false
    var onFocusLost: ((transient: Boolean) -> Unit)? = null

    data class RouteResult(
        val ok: Boolean,
        val strategy: RouteStrategy,
        val device: AudioDeviceInfo?,
        val hfpDevice: String?,
        val elapsedMs: Long,
        val detail: String,
    )

    fun headsetInputDevice(): AudioDeviceInfo? =
        if (Build.VERSION.SDK_INT >= 31) {
            audio.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
        } else {
            audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        }

    fun acquire(strategy: RouteStrategy, timeoutMs: Long = 4000): RouteResult {
        val t0 = System.nanoTime()
        headset.connect()
        requestFocus()
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val hfp = headset.hfpDevices().firstOrNull()
        val details = mutableListOf<String>()

        if (strategy != RouteStrategy.COMMUNICATION_DEVICE && hfp != null) {
            val ok = headset.startVoiceRecognition(hfp)
            vrDevice = if (ok) hfp else null
            details += "startVoiceRecognition=$ok"
        }
        if (strategy != RouteStrategy.VOICE_RECOGNITION) {
            details += setCommunicationDevice(timeoutMs)
        }
        val scoUp = waitForSco(hfp, timeoutMs)
        details += "scoConectado=$scoUp"
        val dev = headsetInputDevice()
        val ms = (System.nanoTime() - t0) / 1_000_000
        val result = RouteResult(scoUp || (dev != null && strategy == RouteStrategy.COMMUNICATION_DEVICE && currentCommIsHeadset()),
            strategy, dev, hfp?.name, ms, details.joinToString(", "))
        log.log("route", if (result.ok) "Ruta de voz al casco lista" else "No se pudo abrir la ruta de voz", mapOf(
            "strategy" to strategy.name, "elapsedMs" to ms, "hfp" to hfp?.name, "detail" to result.detail,
            "commDevice" to if (Build.VERSION.SDK_INT >= 31) audio.communicationDevice?.let(BluetoothInventory::audioDevice) else null,
        ))
        return result
    }

    private fun setCommunicationDevice(timeoutMs: Long): String {
        if (Build.VERSION.SDK_INT >= 31) {
            val dev = headsetInputDevice() ?: return "sinDispositivoSCO"
            return "setCommunicationDevice=${audio.setCommunicationDevice(dev)}"
        }
        // API < 31: ruta heredada.
        val latch = CountDownLatch(1)
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                @Suppress("DEPRECATION")
                if (i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) == AudioManager.SCO_AUDIO_STATE_CONNECTED) latch.countDown()
            }
        }
        @Suppress("DEPRECATION")
        ctx.registerReceiver(rx, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        try {
            @Suppress("DEPRECATION")
            audio.startBluetoothSco()
            @Suppress("DEPRECATION")
            audio.isBluetoothScoOn = true
            usedLegacySco = true
            return "startBluetoothSco=${latch.await(timeoutMs, TimeUnit.MILLISECONDS)}"
        } finally {
            ctx.unregisterReceiver(rx)
        }
    }

    private fun currentCommIsHeadset(): Boolean =
        Build.VERSION.SDK_INT >= 31 && audio.communicationDevice?.type.let {
            it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it == AudioDeviceInfo.TYPE_BLE_HEADSET
        }

    private fun waitForSco(hfp: BluetoothDevice?, timeoutMs: Long): Boolean {
        if (hfp == null) return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (headset.isScoConnected(hfp)) return true
            Thread.sleep(50)
        }
        return headset.isScoConnected(hfp)
    }

    fun release() {
        vrDevice?.let { headset.stopVoiceRecognition(it) }
        vrDevice = null
        if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
        if (usedLegacySco) {
            @Suppress("DEPRECATION")
            audio.stopBluetoothSco()
            @Suppress("DEPRECATION")
            audio.isBluetoothScoOn = false
            usedLegacySco = false
        }
        audio.mode = AudioManager.MODE_NORMAL
        focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        focusRequest = null
        log.log("route", "Ruta de voz liberada")
    }

    /**
     * Foco transitorio: la música se pausa mientras hablamos. Una pérdida de
     * foco (llamada entrante, navegación) suspende la sesión: la comunicación
     * prioritaria siempre gana.
     */
    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                log.log("focus", "Cambio de foco de audio: $change")
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> onFocusLost?.invoke(false)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onFocusLost?.invoke(true)
                }
            }
            .build()
        val res = audio.requestAudioFocus(req)
        focusRequest = req
        log.log("focus", "Solicitud de foco: $res")
    }

    fun isInCall(): Boolean = audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_RINGTONE
}
