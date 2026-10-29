package mx.alan.intercom.core.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlockParam
import java.time.Duration

/**
 * Proveedor Anthropic (Claude) mediante el SDK oficial de Java.
 *
 * - Bucle de herramientas manual: el orquestador decide qué se ejecuta
 *   (scopes, confirmación, aplazamiento), por eso no se usa el tool runner.
 * - El contenido del asistente se reenvía tal cual (`Message.toParam()`)
 *   para conservar los bloques de razonamiento entre turnos.
 * - Esfuerzo bajo por defecto: en voz la latencia manda.
 */
class AnthropicChatModel(
    private val client: AnthropicClient,
    private val model: String = DEFAULT_MODEL,
    private val effort: OutputConfig.Effort = OutputConfig.Effort.LOW,
    /** Fallback del lado del servidor ante `refusal` (beta). */
    private val serverSideFallbacks: Boolean = true,
) : ChatModel {
    override val id: String = "anthropic:$model"

    override fun respond(request: ChatRequest): ModelTurn {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(request.maxOutputTokens)
            .system(request.system)
            .messages(toParams(request.messages))
            .outputConfig(OutputConfig.builder().effort(effort).build())
            .apply {
                request.tools.forEach { addTool(toTool(it)) }
                if (serverSideFallbacks) {
                    putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                    putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                }
            }
            .build()

        val message: Message = try {
            client.messages().create(params)
        } catch (e: RateLimitException) {
            throw ChatModelException("Límite de uso de Claude", e, retryable = true)
        } catch (e: AnthropicServiceException) {
            throw ChatModelException("Claude respondió ${e.statusCode()}: ${e.message}", e, retryable = e.statusCode() >= 500)
        } catch (e: AnthropicIoException) {
            throw ChatModelException("Sin conexión con Claude: ${e.message}", e, retryable = true)
        }
        return fromMessage(message)
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

        fun fromApiKey(apiKey: String, model: String = DEFAULT_MODEL, timeout: Duration = Duration.ofSeconds(20)) =
            AnthropicChatModel(
                AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(timeout).maxRetries(1).build(),
                model,
            )

        internal fun fromMessage(message: Message): ModelTurn {
            val text = message.content().mapNotNull { b -> b.text().map { it.text() }.orElse(null) }.joinToString(" ").trim()
            val calls = message.content().mapNotNull { b ->
                b.toolUse().map { tu ->
                    @Suppress("UNCHECKED_CAST")
                    val args = (tu._input().convert(Map::class.java) as Map<String, Any?>?) ?: emptyMap()
                    ToolCall(tu.id(), tu.name(), args)
                }.orElse(null)
            }
            return ModelTurn(
                text = text,
                toolCalls = calls,
                stopReason = message.stopReason().map { it.asString() }.orElse("unknown"),
                providerPayload = message.toParam(),
                inputTokens = message.usage().inputTokens(),
                outputTokens = message.usage().outputTokens(),
            )
        }

        /**
         * Convierte el historial neutral a MessageParam. Los turnos de usuario
         * consecutivos (resultados de herramienta + nueva frase) se fusionan en
         * un solo mensaje con varios bloques.
         */
        internal fun toParams(messages: List<ChatMessage>): List<MessageParam> {
            val out = mutableListOf<MessageParam>()
            val userBlocks = mutableListOf<ContentBlockParam>()
            fun flushUser() {
                if (userBlocks.isNotEmpty()) {
                    out += MessageParam.ofUserOfContentBlockParams(userBlocks.toList())
                    userBlocks.clear()
                }
            }
            for (m in messages) {
                when (m) {
                    is ChatMessage.User -> userBlocks += ContentBlockParam.ofText(m.text)
                    is ChatMessage.ToolResults -> m.results.forEach { r ->
                        userBlocks += ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder().toolUseId(r.callId).content(r.content).isError(r.isError).build(),
                        )
                    }
                    is ChatMessage.Assistant -> {
                        flushUser()
                        out += (m.providerPayload as? MessageParam) ?: assistantFromNeutral(m)
                    }
                }
            }
            flushUser()
            return out
        }

        private fun assistantFromNeutral(m: ChatMessage.Assistant): MessageParam {
            val blocks = mutableListOf<ContentBlockParam>()
            if (m.text.isNotBlank()) blocks += ContentBlockParam.ofText(m.text)
            m.toolCalls.forEach { c ->
                val input = ToolUseBlockParam.Input.builder().apply {
                    c.arguments.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) }
                }.build()
                blocks += ContentBlockParam.ofToolUse(ToolUseBlockParam.builder().id(c.id).name(c.name).input(input).build())
            }
            if (blocks.isEmpty()) blocks += ContentBlockParam.ofText("(sin texto)")
            return MessageParam.ofAssistantOfContentBlockParams(blocks)
        }

        internal fun toTool(spec: ToolSpec): Tool =
            Tool.builder()
                .name(spec.name)
                .description(spec.description)
                .inputSchema(
                    Tool.InputSchema.builder()
                        .properties(
                            Tool.InputSchema.Properties.builder().apply {
                                spec.properties.forEach { (name, p) ->
                                    val schema = mutableMapOf<String, Any>("type" to p.type, "description" to p.description)
                                    p.enum?.let { schema["enum"] = it }
                                    putAdditionalProperty(name, JsonValue.from(schema))
                                }
                            }.build(),
                        )
                        .required(spec.required)
                        .build(),
                )
                .build()
    }
}
