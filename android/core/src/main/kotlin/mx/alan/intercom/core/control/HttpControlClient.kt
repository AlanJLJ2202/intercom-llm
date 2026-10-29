package mx.alan.intercom.core.control

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Cliente HTTP del contrato Control v1 (ver control-bridge/openapi.yaml).
 * Autenticación por token Bearer con scopes; el servidor vuelve a validar
 * cada scope: el cliente no es la frontera de seguridad.
 */
class HttpControlClient(
    baseUrl: String,
    private val token: String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build(),
) : ControlClient {
    private val base = baseUrl.trimEnd('/')
    private val mapper = jacksonObjectMapper()
    private val json = "application/json".toMediaType()

    override fun saveIdea(idea: IdeaInput) = post("/v1/ideas", idea).toRecord("idea")
    override fun sendToRadar(idea: IdeaInput) = post("/v1/radar/ideas", idea).toRecord("radar_idea")
    override fun createTask(task: TaskInput) = post("/v1/tasks", task).toRecord("task")
    override fun addNote(note: NoteInput) = post("/v1/notes", note).toRecord("note")

    override fun queryKnowledge(question: String): KnowledgeAnswer {
        val node = post("/v1/knowledge/query", mapOf("question" to question))
        return KnowledgeAnswer(node.path("answer").asText(), node.path("sources").map { it.asText() })
    }

    override fun recentResults(limit: Int): List<ResultSummary> {
        val url = "$base/v1/results/recent".toHttpUrl().newBuilder().addQueryParameter("limit", "$limit").build()
        return execute(Request.Builder().url(url).get()).path("items").map {
            ResultSummary(it.path("id").asText(), it.path("title").asText(), it.path("summary").asText(), it.path("updatedAt").asText())
        }
    }

    override fun pendingToday(): List<TaskSummary> =
        execute(Request.Builder().url("$base/v1/tasks/today").get()).path("items").map {
            TaskSummary(it.path("id").asText(), it.path("title").asText(), it.path("due").takeIf { d -> !d.isNull && !d.isMissingNode }?.asText())
        }

    private fun post(path: String, body: Any): JsonNode =
        execute(Request.Builder().url("$base$path").post(mapper.writeValueAsString(body).toRequestBody(json)))

    private fun execute(builder: Request.Builder): JsonNode {
        val request = builder.header("Authorization", "Bearer $token").header("Accept", "application/json").build()
        try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw ControlException(
                        "Control respondió ${resp.code}: ${text.take(200)}",
                        retryable = resp.code == 429 || resp.code >= 500,
                    )
                }
                return if (text.isBlank()) mapper.createObjectNode() else mapper.readTree(text)
            }
        } catch (e: IOException) {
            throw ControlException("Sin conexión con Control: ${e.message}", e, retryable = true)
        }
    }

    private fun JsonNode.toRecord(kind: String) =
        ControlRecord(path("id").asText(), kind, path("status").asText("stored"))
}
