package mx.alan.intercom.core.latency

/**
 * Marca tiempos por etapa del pipeline de voz. Usa un reloj monotónico
 * inyectable (System.nanoTime en producción) para poder probarlo.
 *
 * Etapas canónicas: activation → capture_start → speech_end → stt_final →
 * llm_first → tool_* → reply_ready → tts_start (primera respuesta audible).
 */
class LatencyTracker(private val nanoClock: () -> Long = System::nanoTime) {
    private val marks = linkedMapOf<String, Long>()
    private var origin: Long? = null

    @Synchronized
    fun start(stage: String = Stages.ACTIVATION) {
        marks.clear()
        origin = nanoClock()
        marks[stage] = origin!!
    }

    @Synchronized
    fun mark(stage: String) {
        if (origin == null) start()
        marks[stage] = nanoClock()
    }

    /** Milisegundos desde el origen hasta cada etapa. */
    @Synchronized
    fun snapshot(): Map<String, Long> {
        val o = origin ?: return emptyMap()
        return marks.mapValues { (_, t) -> (t - o) / 1_000_000 }
    }

    /** Duración entre dos etapas, o null si falta alguna. */
    @Synchronized
    fun between(from: String, to: String): Long? {
        val a = marks[from] ?: return null
        val b = marks[to] ?: return null
        return (b - a) / 1_000_000
    }

    fun report(): String = snapshot().entries.joinToString(" | ") { "${it.key}=${it.value}ms" }
}

object Stages {
    const val ACTIVATION = "activation"
    const val ROUTE_READY = "route_ready"
    const val CAPTURE_START = "capture_start"
    const val SPEECH_START = "speech_start"
    const val SPEECH_END = "speech_end"
    const val STT_FINAL = "stt_final"
    const val LLM_FIRST = "llm_first"
    const val REPLY_READY = "reply_ready"
    const val TTS_START = "tts_start"
    const val TTS_DONE = "tts_done"
}
