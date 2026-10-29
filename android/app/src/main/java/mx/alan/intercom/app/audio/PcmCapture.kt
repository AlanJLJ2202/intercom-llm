package mx.alan.intercom.app.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import mx.alan.intercom.app.EventLog
import mx.alan.intercom.app.bt.BluetoothInventory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captura PCM 16-bit mono desde la fuente VOICE_COMMUNICATION, preferente-
 * mente desde el micrófono del casco (dispositivo SCO). Intenta 16 kHz
 * (mSBC/wideband) y cae a 8 kHz (CVSD). Reporta el dispositivo realmente
 * enrutado: es la evidencia clave de EXP-04.
 */
@SuppressLint("MissingPermission")
class PcmCapture(private val log: EventLog) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)
    var sampleRate: Int = 16_000; private set
    var routedDevice: Map<String, Any?>? = null; private set

    fun start(preferred: AudioDeviceInfo?, frameMs: Int = 20, onFrame: (ShortArray) -> Unit): Boolean {
        stop()
        for (rate in intArrayOf(16_000, 8_000)) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 5 * 2))
            } catch (e: Exception) {
                log.log("capture", "AudioRecord falló a $rate Hz: ${e.message}"); continue
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); continue }
            preferred?.let { rec.setPreferredDevice(it) }
            rec.startRecording()
            record = rec
            sampleRate = rate
            routedDevice = rec.routedDevice?.let(BluetoothInventory::audioDevice)
            log.log("capture", "Captura iniciada a $rate Hz", mapOf("routedDevice" to routedDevice, "preferred" to preferred?.let(BluetoothInventory::audioDevice)))
            running.set(true)
            val samples = rate * frameMs / 1000
            thread = Thread({
                val buf = ShortArray(samples)
                while (running.get()) {
                    var off = 0
                    while (off < samples && running.get()) {
                        val n = rec.read(buf, off, samples - off)
                        if (n <= 0) { log.log("capture", "read() devolvió $n"); running.set(false); break }
                        off += n
                    }
                    if (off == samples) onFrame(buf.copyOf())
                }
            }, "pcm-capture").apply { start() }
            return true
        }
        log.log("capture", "No se pudo iniciar AudioRecord")
        return false
    }

    fun isRoutedToHeadset(): Boolean =
        routedDevice?.get("type") in setOf("BLUETOOTH_SCO", "BLE_HEADSET")

    fun stop() {
        running.set(false)
        thread?.join(500)
        thread = null
        record?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        record = null
    }

}
