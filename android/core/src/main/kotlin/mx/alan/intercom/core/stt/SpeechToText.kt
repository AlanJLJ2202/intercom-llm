package mx.alan.intercom.core.stt

/** Transcripción de un enunciado ya segmentado por el VAD. */
interface SpeechToText {
    val id: String
    fun transcribe(pcm: ShortArray, sampleRate: Int, languageTag: String = "es-MX"): Transcript
}

data class Transcript(val text: String, val confidence: Double? = null, val provider: String)

class SpeechToTextException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
