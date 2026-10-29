package mx.alan.intercom.core.control

/**
 * Adaptador hacia "Control" (sistema de agentes del usuario).
 *
 * No se duplica el motor de agentes de Control: este cliente solo consume
 * una API segura. El contrato HTTP de referencia está en
 * `control-bridge/openapi.yaml` y lo implementa `control-bridge/` en Node.
 */
interface ControlClient {
    fun saveIdea(idea: IdeaInput): ControlRecord
    fun sendToRadar(idea: IdeaInput): ControlRecord
    fun createTask(task: TaskInput): ControlRecord
    fun addNote(note: NoteInput): ControlRecord
    fun queryKnowledge(question: String): KnowledgeAnswer
    fun recentResults(limit: Int = 3): List<ResultSummary>
    fun pendingToday(): List<TaskSummary>
}

data class IdeaInput(
    val text: String,
    val tags: List<String> = emptyList(),
    /** Frase original dictada, modo y hora: contexto para investigar después. */
    val context: CaptureContext,
    val researchLater: Boolean = true,
)

data class TaskInput(val title: String, val due: String? = null, val trigger: String? = null, val context: CaptureContext)
data class NoteInput(val text: String, val context: CaptureContext)

data class CaptureContext(
    val utterance: String,
    val mode: String,
    val capturedAt: String,
    val sessionId: String,
    val source: String = "ejeas-v6-pro-voice",
)

data class ControlRecord(val id: String, val kind: String, val status: String = "stored", val queuedOffline: Boolean = false)
data class KnowledgeAnswer(val answer: String, val sources: List<String> = emptyList())
data class ResultSummary(val id: String, val title: String, val summary: String, val updatedAt: String)
data class TaskSummary(val id: String, val title: String, val due: String?)

class ControlException(message: String, cause: Throwable? = null, val retryable: Boolean = false) :
    RuntimeException(message, cause)
