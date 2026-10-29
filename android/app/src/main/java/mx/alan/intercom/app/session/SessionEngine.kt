package mx.alan.intercom.app.session

import android.content.Context
import mx.alan.intercom.app.AppGraph
import mx.alan.intercom.app.EventLog
import mx.alan.intercom.app.OutputRoute
import mx.alan.intercom.app.SttEngine
import mx.alan.intercom.app.audio.BluetoothAudioRouter
import mx.alan.intercom.app.audio.PcmCapture
import mx.alan.intercom.app.audio.SpeechOutput
import mx.alan.intercom.app.audio.SystemRecognizer
import mx.alan.intercom.core.audio.EnergyVad
import mx.alan.intercom.core.audio.UtteranceCollector
import mx.alan.intercom.core.audio.VadConfig
import mx.alan.intercom.core.audio.Wav
import mx.alan.intercom.core.latency.LatencyTracker
import mx.alan.intercom.core.latency.Stages
import mx.alan.intercom.core.orchestrator.LocalIntentModel
import mx.alan.intercom.core.orchestrator.Orchestrator
import mx.alan.intercom.core.orchestrator.ReplyKind
import mx.alan.intercom.core.policy.RideMode
import mx.alan.intercom.core.stt.OpenAiTranscriber
import mx.alan.intercom.core.stt.SpeechToTextException
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pipeline de una sesión de voz:
 * activación → ruta SCO → tono → escuchar → STT → orquestador → TTS → (repetir) → cierre.
 *
 * Corre en un hilo de trabajo propio. [cancel] es inmediato: corta TTS,
 * captura y reconocimiento, y libera la ruta de audio.
 */
class SessionEngine(
    private val ctx: Context,
    private val router: BluetoothAudioRouter,
    private val speech: SpeechOutput,
    private val log: EventLog,
) {
    private val cancelled = AtomicBoolean(false)
    private val capture = PcmCapture(log)
    @Volatile var active = false; private set

    fun cancel(reason: String) {
        if (!active) return
        log.log("session", "Cancelación: $reason")
        cancelled.set(true)
        speech.stop()
        capture.stop()
    }

    fun run(trigger: String) {
        val settings = AppGraph.settings
        active = true
        cancelled.set(false)
        val latency = LatencyTracker().also { it.start() }
        log.log("session", "Sesión iniciada", mapOf("trigger" to trigger, "mode" to settings.mode.name, "stt" to settings.sttEngine.name))
        val route = router.acquire(settings.routeStrategy)
        latency.mark(Stages.ROUTE_READY)
        val output = if (route.ok) settings.outputRoute else OutputRoute.A2DP
        try {
            speech.awaitReady()
            if (!route.ok) {
                speech.speakBlocking("No pude abrir el micrófono del casco.", output)
                return
            }
            val orchestrator = Orchestrator(
                cloud = AppGraph.cloudModel(),
                local = LocalIntentModel(),
                registry = AppGraph.registry(ctx),
                policy = AppGraph.policy,
                modeProvider = { AppGraph.mode() },
                latency = latency,
                useLocalFastPath = settings.useLocalFastPath,
            )
            val pendingDeferred = AppGraph.deferred.pending().size
            if (settings.mode == RideMode.STOPPED && pendingDeferred > 0) {
                speech.speakBlocking("Tienes $pendingDeferred pendientes aplazados. Di: ejecuta pendientes.", output)
            }
            for (turn in 0 until settings.maxTurns()) {
                if (cancelled.get()) break
                speech.earcon(listening = true, route = output)
                latency.mark(Stages.CAPTURE_START)
                val text = listen(latency) ?: break
                if (cancelled.get()) break
                log.log("session", "Usuario: $text")
                val reply = if (LocalIntentModel.normalize(text).startsWith("ejecuta pendientes")) orchestrator.runDeferred()
                else orchestrator.handle(text)
                log.log("session", "Asistente: ${reply.spoken}", mapOf("kind" to reply.kind.name, "events" to reply.events.map { "${it.type}:${it.detail}" }))
                if (reply.spoken.isNotBlank()) {
                    speech.speakBlocking(reply.spoken, output, onStart = { latency.mark(Stages.TTS_START) })
                    latency.mark(Stages.TTS_DONE)
                }
                log.log("latency", latency.report(), latency.snapshot() + mapOf(
                    "speechEndToTts" to latency.between(Stages.SPEECH_END, Stages.TTS_START),
                    "sttToTts" to latency.between(Stages.STT_FINAL, Stages.TTS_START),
                ))
                if (reply.endSession || reply.kind == ReplyKind.EMPTY) break
                latency.start(Stages.ACTIVATION + "_turn${turn + 1}")
            }
            if (!cancelled.get()) speech.earcon(listening = false, route = output)
        } catch (e: Exception) {
            log.log("session", "Error en la sesión: ${e.message}", mapOf("exception" to e.javaClass.name))
        } finally {
            capture.stop()
            router.release()
            active = false
            try { AppGraph.flushOutbox().takeIf { it > 0 }?.let { log.log("control", "Sincronizadas $it entradas pendientes") } } catch (_: Exception) {}
            log.log("session", "Sesión terminada")
        }
    }

    private fun listen(latency: LatencyTracker): String? {
        val s = AppGraph.settings
        val useCloud = s.sttEngine == SttEngine.CLOUD && s.openAiKey.isNotBlank()
        return if (useCloud) listenCloud(latency, s.openAiKey) else listenSystem(latency)
    }

    private fun listenSystem(latency: LatencyTracker): String? {
        val out = SystemRecognizer(ctx, log).listenOnce(onSpeechEnd = { latency.mark(Stages.SPEECH_END) })
        latency.mark(Stages.STT_FINAL)
        if (out.text.isNullOrBlank()) {
            log.log("stt", "Sin transcripción (error ${out.error})")
            return null
        }
        return out.text
    }

    private fun listenCloud(latency: LatencyTracker, key: String): String? {
        val frames = LinkedBlockingQueue<ShortArray>()
        if (!capture.start(router.headsetInputDevice()) { frames.offer(it) }) return null
        if (!capture.isRoutedToHeadset()) log.log("capture", "ADVERTENCIA: la captura no está enrutada al casco", mapOf("routed" to capture.routedDevice))
        val rate = capture.sampleRate
        val collector = UtteranceCollector(EnergyVad(VadConfig(sampleRate = rate)), rate)
        var pcm: ShortArray? = null
        while (!cancelled.get()) {
            val f = frames.poll(500, TimeUnit.MILLISECONDS) ?: continue
            when (val r = collector.push(f)) {
                is UtteranceCollector.Result.Started -> latency.mark(Stages.SPEECH_START)
                is UtteranceCollector.Result.Completed -> { pcm = r.pcm; break }
                is UtteranceCollector.Result.NoSpeech -> break
                else -> {}
            }
        }
        capture.stop()
        latency.mark(Stages.SPEECH_END)
        val audio = pcm ?: return null
        val stats = Wav.stats(audio, rate)
        File(ctx.filesDir, "lab/last_utterance.wav").apply { parentFile?.mkdirs(); writeBytes(Wav.encode(audio, rate)) }
        log.log("capture", "Enunciado capturado", mapOf("ms" to stats.durationMs, "rmsDbfs" to stats.rmsDbfs, "peak" to stats.peak, "rate" to rate))
        return try {
            OpenAiTranscriber(key).transcribe(audio, rate).text.also { latency.mark(Stages.STT_FINAL) }
        } catch (e: SpeechToTextException) {
            log.log("stt", "STT en la nube falló: ${e.message}")
            null
        }
    }
}
