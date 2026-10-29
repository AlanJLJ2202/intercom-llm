package mx.alan.intercom.core

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mx.alan.intercom.core.llm.AnthropicChatModel
import mx.alan.intercom.core.llm.ChatMessage
import mx.alan.intercom.core.llm.ChatRequest
import mx.alan.intercom.core.llm.PropertySpec
import mx.alan.intercom.core.llm.ToolCall
import mx.alan.intercom.core.llm.ToolResultMessage
import mx.alan.intercom.core.llm.ToolSpec
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifica el contrato HTTP con un servidor simulado (sin llamar a la API real):
 * forma de la petición (modelo, tools, effort, fallbacks) y lectura de tool_use.
 */
class AnthropicMappingTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun consecutiveUserTurnsAreMerged() {
        val params = AnthropicChatModel.toParams(
            listOf(
                ChatMessage.User("hola"),
                ChatMessage.Assistant("", listOf(ToolCall("tu_1", "save_idea", mapOf("text" to "x")))),
                ChatMessage.ToolResults(listOf(ToolResultMessage("tu_1", "ok"))),
                ChatMessage.User("sí"),
            ),
        )
        assertEquals(3, params.size)
        assertTrue(params[0].isUser())
        assertTrue(params[1].isAssistant())
        assertTrue(params[2].isUser())
    }

    @Test
    fun requestShapeAndToolUseParsing() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """
                    {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
                     "content":[{"type":"text","text":"Voy."},
                                {"type":"tool_use","id":"toolu_1","name":"save_idea","input":{"text":"traductor"}}],
                     "stop_reason":"tool_use","stop_sequence":null,
                     "usage":{"input_tokens":120,"output_tokens":30}}
                    """.trimIndent(),
                ),
            )
            server.start()
            val client = AnthropicOkHttpClient.builder().apiKey("test").baseUrl(server.url("/").toString()).maxRetries(0).build()
            val model = AnthropicChatModel(client)
            val turn = model.respond(
                ChatRequest(
                    "sistema",
                    listOf(ChatMessage.User("guarda la idea del traductor")),
                    listOf(ToolSpec("save_idea", "Guarda", mapOf("text" to PropertySpec("string", "idea")), listOf("text"))),
                    300,
                ),
            )
            assertEquals("tool_use", turn.stopReason)
            assertEquals("Voy.", turn.text)
            assertEquals("save_idea", turn.toolCalls.single().name)
            assertEquals("traductor", turn.toolCalls.single().arguments["text"])
            assertEquals(120, turn.inputTokens)

            val recorded = server.takeRequest()
            assertEquals("/v1/messages", recorded.path)
            assertTrue(recorded.getHeader("anthropic-beta")!!.contains(AnthropicChatModel.FALLBACK_BETA))
            val body = mapper.readTree(recorded.body.readUtf8())
            assertEquals("claude-opus-5-5", body["model"].asText())
            assertEquals(300, body["max_tokens"].asInt())
            assertEquals("low", body["output_config"]["effort"].asText())
            assertEquals("default", body["fallbacks"].asText())
            assertEquals("save_idea", body["tools"][0]["name"].asText())
            assertEquals("string", body["tools"][0]["input_schema"]["properties"]["text"]["type"].asText())
            assertEquals("sistema", body["system"].asText())
        }
    }
}
