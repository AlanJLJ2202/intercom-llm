package mx.alan.intercom.core.llm

/**
 * Contrato neutral de proveedor para el modelo conversacional.
 *
 * El orquestador nunca habla directamente con un SDK: recibe un [ChatModel]
 * y le entrega historial + herramientas permitidas. Así se pueden intercambiar
 * Anthropic, OpenAI, Gemini o un modelo local sin tocar la lógica de voz.
 */
interface ChatModel {
    val id: String

    /** Un turno del modelo: texto y/o solicitudes de herramientas. */
    fun respond(request: ChatRequest): ModelTurn
}

data class ChatRequest(
    val system: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec>,
    /** Límite duro de tokens de salida; el modo conducción lo reduce. */
    val maxOutputTokens: Long,
)

/** Definición de herramienta en JSON Schema (subconjunto: object con propiedades simples). */
data class ToolSpec(
    val name: String,
    val description: String,
    val properties: Map<String, PropertySpec>,
    val required: List<String>,
)

data class PropertySpec(val type: String, val description: String, val enum: List<String>? = null)

sealed interface ChatMessage {
    data class User(val text: String) : ChatMessage

    /**
     * Turno del asistente. [providerPayload] conserva el contenido original del
     * proveedor (p. ej. bloques de Anthropic con thinking) para reenviarlo sin cambios.
     */
    data class Assistant(
        val text: String,
        val toolCalls: List<ToolCall>,
        val providerPayload: Any? = null,
    ) : ChatMessage

    data class ToolResults(val results: List<ToolResultMessage>) : ChatMessage
}

data class ToolCall(val id: String, val name: String, val arguments: Map<String, Any?>)

data class ToolResultMessage(val callId: String, val content: String, val isError: Boolean = false)

data class ModelTurn(
    val text: String,
    val toolCalls: List<ToolCall>,
    val stopReason: String,
    val providerPayload: Any? = null,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
)

class ChatModelException(message: String, cause: Throwable? = null, val retryable: Boolean = false) :
    RuntimeException(message, cause)
