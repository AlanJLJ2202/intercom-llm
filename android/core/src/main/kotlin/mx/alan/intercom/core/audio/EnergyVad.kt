package mx.alan.intercom.core.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Detector de actividad de voz por energía con piso de ruido adaptativo.
 *
 * Diseñado para audio HFP (8 kHz CVSD o 16 kHz mSBC) dentro de un casco:
 * el ruido de viento es alto y variable, así que el umbral se fija como
 * margen en dB sobre un piso de ruido que se adapta solo en silencio.
 * Es deliberadamente simple y determinista; puede sustituirse por
 * Silero/WebRTC VAD detrás de la misma interfaz [VoiceActivityDetector].
 */
interface VoiceActivityDetector {
    fun process(frame: ShortArray): VadEvent
    fun reset()
}

enum class VadEvent { SILENCE, SPEECH_START, SPEECH, SPEECH_END }

data class VadConfig(
    val sampleRate: Int = 16_000,
    val frameMs: Int = 20,
    /** Margen sobre el piso de ruido para considerar voz. */
    val marginDb: Double = 9.0,
    /** Piso mínimo absoluto (dBFS) para no disparar con silencio digital. */
    val minSpeechDbfs: Double = -50.0,
    /** Frames consecutivos con voz para confirmar inicio (anti-golpes). */
    val startFrames: Int = 3,
    /** Silencio necesario para cerrar el turno (endpointing). */
    val endSilenceMs: Int = 700,
    /** Adaptación del piso de ruido (0..1, por frame de silencio). */
    val noiseAdapt: Double = 0.05,
    /**
     * Frames iniciales para calibrar el piso de ruido (tras el tono de
     * activación). Durante la calibración no se detecta voz; el pre-roll del
     * colector conserva ese audio por si el usuario habla de inmediato.
     */
    val calibrationFrames: Int = 10,
)

class EnergyVad(private val cfg: VadConfig = VadConfig()) : VoiceActivityDetector {
    private var noiseDb = -60.0
    private var speaking = false
    private var voicedRun = 0
    private var silentRun = 0
    private var calibrated = 0
    private val endFrames = max(1, cfg.endSilenceMs / cfg.frameMs)

    var lastDbfs: Double = -100.0
        private set
    val noiseFloorDbfs: Double get() = noiseDb

    override fun process(frame: ShortArray): VadEvent {
        val db = dbfs(frame)
        lastDbfs = db
        if (calibrated < cfg.calibrationFrames) {
            noiseDb = if (calibrated == 0) db else noiseDb + (db - noiseDb) / (calibrated + 1)
            calibrated++
            return VadEvent.SILENCE
        }
        val voiced = db > max(noiseDb + cfg.marginDb, cfg.minSpeechDbfs)
        if (!speaking) {
            if (voiced) {
                voicedRun++
                if (voicedRun >= cfg.startFrames) {
                    speaking = true
                    silentRun = 0
                    return VadEvent.SPEECH_START
                }
            } else {
                voicedRun = 0
                noiseDb += (db - noiseDb) * cfg.noiseAdapt
            }
            return VadEvent.SILENCE
        }
        if (voiced) {
            silentRun = 0
            return VadEvent.SPEECH
        }
        silentRun++
        if (silentRun >= endFrames) {
            speaking = false
            voicedRun = 0
            return VadEvent.SPEECH_END
        }
        return VadEvent.SPEECH
    }

    override fun reset() {
        speaking = false
        voicedRun = 0
        silentRun = 0
    }

    companion object {
        fun dbfs(frame: ShortArray): Double {
            if (frame.isEmpty()) return -100.0
            var sum = 0.0
            for (s in frame) sum += s.toDouble() * s
            val rms = sqrt(sum / frame.size) / Short.MAX_VALUE
            return if (rms <= 1e-9) -100.0 else 20 * log10(rms)
        }
    }
}

/**
 * Acumula un enunciado completo: precarga ([preRollMs]) para no cortar la
 * primera sílaba y límite duro de duración para no grabar indefinidamente.
 */
class UtteranceCollector(
    private val vad: VoiceActivityDetector,
    private val sampleRate: Int,
    private val preRollMs: Int = 300,
    private val maxUtteranceMs: Int = 15_000,
    /** Si nadie habla en este tiempo, la sesión se da por terminada. */
    private val noSpeechTimeoutMs: Int = 6_000,
) {
    private val preRoll = ArrayDeque<ShortArray>()
    private val buffer = mutableListOf<ShortArray>()
    private var preRollSamples = 0
    private var bufferedSamples = 0
    private var idleSamples = 0
    private var active = false

    sealed interface Result {
        data object Listening : Result
        data object Started : Result
        data class Completed(val pcm: ShortArray, val truncated: Boolean) : Result
        data object NoSpeech : Result
    }

    fun push(frame: ShortArray): Result {
        val ev = vad.process(frame)
        if (!active) {
            preRoll.addLast(frame); preRollSamples += frame.size
            while (preRollSamples > sampleRate * preRollMs / 1000 && preRoll.size > 1) {
                preRollSamples -= preRoll.removeFirst().size
            }
            if (ev == VadEvent.SPEECH_START) {
                active = true
                buffer += preRoll; bufferedSamples = preRollSamples
                preRoll.clear(); preRollSamples = 0
                return Result.Started
            }
            idleSamples += frame.size
            if (idleSamples > sampleRate.toLong() * noSpeechTimeoutMs / 1000) {
                idleSamples = 0
                return Result.NoSpeech
            }
            return Result.Listening
        }
        buffer += frame; bufferedSamples += frame.size
        val truncated = bufferedSamples > sampleRate.toLong() * maxUtteranceMs / 1000
        if (ev == VadEvent.SPEECH_END || truncated) {
            val out = ShortArray(bufferedSamples)
            var off = 0
            for (f in buffer) { f.copyInto(out, off); off += f.size }
            buffer.clear(); bufferedSamples = 0; active = false; idleSamples = 0
            vad.reset()
            return Result.Completed(out, truncated)
        }
        return Result.Listening
    }
}
