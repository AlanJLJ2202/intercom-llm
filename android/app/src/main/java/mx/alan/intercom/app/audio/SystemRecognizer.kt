package mx.alan.intercom.app.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import mx.alan.intercom.app.EventLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * STT del sistema (SpeechRecognizer). Ventajas: streaming, endpointing y
 * reconocimiento en el dispositivo cuando está disponible. Incógnita
 * (EXP-04): si toma el audio del micrófono del casco cuando el dispositivo
 * de comunicación es SCO. El laboratorio lo compara contra la ruta CLOUD
 * (AudioRecord propio + VAD + transcripción en la nube).
 */
class SystemRecognizer(private val ctx: Context, private val log: EventLog) {
    private val main = Handler(Looper.getMainLooper())

    data class Outcome(val text: String?, val error: Int?, val speechStartMs: Long?, val speechEndMs: Long?)

    /** Escucha una frase. Bloquea el hilo llamador (no usar en el hilo principal). */
    fun listenOnce(timeoutMs: Long = 15_000, onSpeechEnd: () -> Unit = {}): Outcome {
        val latch = CountDownLatch(1)
        var outcome = Outcome(null, null, null, null)
        var recognizer: SpeechRecognizer? = null
        val t0 = System.nanoTime()
        var startMs: Long? = null
        var endMs: Long? = null
        main.post {
            if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
                outcome = Outcome(null, -1, null, null); latch.countDown(); return@post
            }
            val r = SpeechRecognizer.createSpeechRecognizer(ctx)
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) = log.log("stt", "Reconocedor listo")
                override fun onBeginningOfSpeech() { startMs = (System.nanoTime() - t0) / 1_000_000 }
                override fun onRmsChanged(rms: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() { endMs = (System.nanoTime() - t0) / 1_000_000; onSpeechEnd() }
                override fun onError(error: Int) {
                    log.log("stt", "Error del reconocedor: $error")
                    outcome = Outcome(null, error, startMs, endMs); latch.countDown()
                }
                override fun onResults(results: Bundle) {
                    val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    outcome = Outcome(text, null, startMs, endMs); latch.countDown()
                }
                override fun onPartialResults(p: Bundle) {
                    p.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { log.log("stt", "Parcial: $it") }
                }
                override fun onEvent(type: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            }
            r.startListening(intent)
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) outcome = Outcome(null, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, startMs, endMs)
        main.post { recognizer?.destroy() }
        return outcome
    }

}
