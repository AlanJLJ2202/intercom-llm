package mx.alan.intercom.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import mx.alan.intercom.app.EventLog
import mx.alan.intercom.app.OutputRoute
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Síntesis de voz local (TextToSpeech de Android): sin red, primera sílaba en
 * decenas de ms. La ruta de salida se elige con los AudioAttributes:
 *  - SCO: USAGE_VOICE_COMMUNICATION → sale por el enlace HFP abierto.
 *  - A2DP: USAGE_ASSISTANT → mejor calidad, pero exige cerrar SCO (EXP-03).
 * Los tonos (earcons) son cortos y de volumen moderado para no tapar
 * sonidos del entorno.
 */
class SpeechOutput(ctx: Context, private val log: EventLog) {
    private val ready = CountDownLatch(1)
    private val callbacks = ConcurrentHashMap<String, Callbacks>()
    @Volatile private var ok = false

    class Callbacks(val onStart: () -> Unit, val onDone: (interrupted: Boolean) -> Unit)

    private val tts = TextToSpeech(ctx.applicationContext) { status ->
        ok = status == TextToSpeech.SUCCESS
        ready.countDown()
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) { callbacks[id]?.onStart?.invoke() }
            override fun onDone(id: String) { callbacks.remove(id)?.onDone?.invoke(false) }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) { callbacks.remove(id)?.onDone?.invoke(true) }
            override fun onStop(id: String, interrupted: Boolean) { callbacks.remove(id)?.onDone?.invoke(true) }
        })
    }

    fun awaitReady(timeoutMs: Long = 3000): Boolean {
        ready.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (ok) {
            val res = tts.setLanguage(Locale.forLanguageTag("es-MX"))
            if (res < 0) tts.setLanguage(Locale.forLanguageTag("es"))
        }
        return ok
    }

    fun speak(text: String, route: OutputRoute, onStart: () -> Unit = {}, onDone: (Boolean) -> Unit = {}) {
        if (!ok) { log.log("tts", "TTS no disponible"); onDone(true); return }
        tts.setAudioAttributes(attributes(route))
        val id = UUID.randomUUID().toString()
        callbacks[id] = Callbacks(onStart, onDone)
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM,
                if (route == OutputRoute.SCO) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC)
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
    }

    /** Bloquea hasta que termine de hablar (con límite). Devuelve false si fue interrumpido. */
    fun speakBlocking(text: String, route: OutputRoute, onStart: () -> Unit = {}, timeoutMs: Long = 30_000): Boolean {
        val latch = CountDownLatch(1)
        var interrupted = false
        speak(text, route, onStart) { interrupted = it; latch.countDown() }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return !interrupted
    }

    fun stop() = tts.stop()

    fun shutdown() { tts.stop(); tts.shutdown() }

    /** Tono breve: "te escucho" (agudo) o "fin" (grave). */
    fun earcon(listening: Boolean, route: OutputRoute) {
        val stream = if (route == OutputRoute.SCO) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
        try {
            val tg = ToneGenerator(stream, 60)
            tg.startTone(if (listening) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_ACK, 120)
            Thread.sleep(160)
            tg.release()
        } catch (e: RuntimeException) {
            log.log("tts", "Earcon falló: ${e.message}")
        }
    }

    private fun attributes(route: OutputRoute) = AudioAttributes.Builder()
        .setUsage(if (route == OutputRoute.SCO) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
}
