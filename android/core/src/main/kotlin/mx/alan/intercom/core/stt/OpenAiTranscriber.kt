package mx.alan.intercom.core.stt

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mx.alan.intercom.core.audio.Wav
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * STT en la nube vía la API de transcripción de OpenAI (multipart, WAV).
 *
 * Se usa cuando el reconocedor del sistema no toma el micrófono HFP o para
 * comparar calidad/latencia (Exp. 4 y 9). El modelo es configurable.
 */
class OpenAiTranscriber(
    private val apiKey: String,
    private val model: String = "gpt-4o-mini-transcribe",
    private val baseUrl: String = "https://api.openai.com",
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build(),
) : SpeechToText {
    override val id = "openai:$model"
    private val mapper = jacksonObjectMapper()

    override fun transcribe(pcm: ShortArray, sampleRate: Int, languageTag: String): Transcript {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("language", languageTag.substringBefore('-'))
            .addFormDataPart("response_format", "json")
            .addFormDataPart("file", "utterance.wav", Wav.encode(pcm, sampleRate).toRequestBody("audio/wav".toMediaType()))
            .build()
        val req = Request.Builder().url("${baseUrl.trimEnd('/')}/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey").post(body).build()
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw SpeechToTextException("STT ${resp.code}: ${text.take(200)}")
                return Transcript(mapper.readTree(text).path("text").asText().trim(), null, id)
            }
        } catch (e: IOException) {
            throw SpeechToTextException("STT sin conexión: ${e.message}", e)
        }
    }
}
