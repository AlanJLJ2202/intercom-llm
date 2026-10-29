package mx.alan.intercom.core

import mx.alan.intercom.core.llm.ChatMessage
import mx.alan.intercom.core.llm.ChatRequest
import mx.alan.intercom.core.orchestrator.LocalIntentModel
import mx.alan.intercom.core.policy.InteractionPolicy
import mx.alan.intercom.core.tools.DeferredQueue
import mx.alan.intercom.core.tools.Scopes
import mx.alan.intercom.core.tools.ToolRegistry
import mx.alan.intercom.core.tools.registerStandardTools
import mx.alan.intercom.core.control.InMemoryControlClient
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalIntentModelTest {
    private val model = LocalIntentModel()
    private val tools = ToolRegistry(InteractionPolicy(), Scopes.DEFAULT, DeferredQueue())
        .registerStandardTools(InMemoryControlClient()).specs()

    private fun route(text: String) = model.respond(ChatRequest("", listOf(ChatMessage.User(text)), tools, 0))

    @Test
    fun savesIdeaKeepingOriginalSpelling() {
        val t = route("Guarda esta idea: Un café con rampa para motos en León.")
        assertEquals("save_idea", t.toolCalls.single().name)
        assertEquals("Un café con rampa para motos en León", t.toolCalls.single().arguments["text"])
    }

    @Test
    fun radarIdea() {
        val t = route("Encontré una idea para el radar: un dispositivo que traduce conversaciones mientras viajo. Investígalo después")
        assertEquals("send_to_radar", t.toolCalls.single().name)
        assertEquals(
            "un dispositivo que traduce conversaciones mientras viajo. Investígalo después",
            t.toolCalls.single().arguments["text"],
        )
    }

    @Test
    fun reminderWithTrigger() {
        val t = route("Recuérdame investigar esto al llegar a casa")
        val c = t.toolCalls.single()
        assertEquals("create_task", c.name)
        assertEquals("investigar esto", c.arguments["title"])
        assertEquals("al llegar a casa", c.arguments["trigger"])
    }

    @Test
    fun voiceNoteAndQueries() {
        assertEquals("dictate_note", route("Registra una nota de voz: revisar cadena").toolCalls.single().name)
        assertEquals("pending_today", route("¿Qué tengo pendiente hoy?").toolCalls.single().name)
        assertEquals("current_time", route("¿Qué hora es?").toolCalls.single().name)
    }

    @Test
    fun openQuestionsGoToCloud() {
        assertEquals(LocalIntentModel.NO_MATCH, route("Explícame brevemente qué es un transformer").stopReason)
    }

    @Test
    fun respectsAvailableTools() {
        val t = model.respond(ChatRequest("", listOf(ChatMessage.User("¿Qué hora es?")), emptyList(), 0))
        assertEquals(LocalIntentModel.NO_MATCH, t.stopReason)
    }
}
