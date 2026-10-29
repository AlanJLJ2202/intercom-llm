package mx.alan.intercom.core

import mx.alan.intercom.core.control.InMemoryControlClient
import mx.alan.intercom.core.llm.ChatMessage
import mx.alan.intercom.core.llm.ChatModelException
import mx.alan.intercom.core.llm.ModelTurn
import mx.alan.intercom.core.llm.ToolCall
import mx.alan.intercom.core.orchestrator.LocalIntentModel
import mx.alan.intercom.core.orchestrator.Orchestrator
import mx.alan.intercom.core.orchestrator.ReplyKind
import mx.alan.intercom.core.policy.InteractionPolicy
import mx.alan.intercom.core.policy.RideMode
import mx.alan.intercom.core.tools.DeferredQueue
import mx.alan.intercom.core.tools.Scopes
import mx.alan.intercom.core.tools.ToolRegistry
import mx.alan.intercom.core.tools.registerStandardTools
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OrchestratorTest {
    private val control = InMemoryControlClient()
    private val policy = InteractionPolicy()
    private var mode = RideMode.STOPPED

    private fun orchestrator(cloud: ScriptedChatModel?, scopes: Set<String> = Scopes.DEFAULT, fastPath: Boolean = true) =
        Orchestrator(
            cloud = cloud,
            local = LocalIntentModel(),
            registry = ToolRegistry(policy, scopes, DeferredQueue()).registerStandardTools(control),
            policy = policy,
            modeProvider = { mode },
            useLocalFastPath = fastPath,
        )

    @Test
    fun fastPathSavesIdeaWithoutCloud() {
        val cloud = ScriptedChatModel()
        val r = orchestrator(cloud).handle("Guarda esta idea: rutas de moto con café")
        assertEquals(ReplyKind.TOOL_DONE, r.kind)
        assertTrue(r.spoken.startsWith("Listo."))
        assertEquals(0, cloud.requests.size)
        assertEquals("rutas de moto con café", control.ideas.single().text)
    }

    @Test
    fun cloudToolLoopThenAnswer() {
        val cloud = ScriptedChatModel(
            ModelTurn("", listOf(ToolCall("tu_1", "save_idea", mapOf("text" to "traductor de viaje"))), "tool_use"),
            ModelTurn("Guardé la idea del traductor.", emptyList(), "end_turn"),
        )
        val r = orchestrator(cloud, fastPath = false).handle("Oye, apunta lo del traductor de viaje")
        assertEquals(ReplyKind.TOOL_DONE, r.kind)
        assertEquals("Guardé la idea del traductor.", r.spoken)
        assertEquals(2, cloud.requests.size)
        // Segunda petición: user, assistant(tool_use), tool_results
        val second = cloud.requests[1].messages
        assertIs<ChatMessage.ToolResults>(second.last())
        assertEquals("tu_1", (second.last() as ChatMessage.ToolResults).results.single().callId)
        assertEquals(1, control.ideas.size)
    }

    @Test
    fun drivingModeUsesShortLimitsAndPrompt() {
        mode = RideMode.DRIVING
        val cloud = ScriptedChatModel(ModelTurn("Uno. Dos. Tres. Cuatro.", emptyList(), "end_turn"))
        val r = orchestrator(cloud).handle("Explícame qué es un transformer")
        assertEquals("Uno. Dos.", r.spoken)
        assertTrue(cloud.requests.single().system.contains("MODO CONDUCCIÓN"))
        assertEquals(300, cloud.requests.single().maxOutputTokens)
    }

    @Test
    fun deepResearchWhileDrivingIsDeferred() {
        mode = RideMode.DRIVING
        val cloud = ScriptedChatModel(
            ModelTurn("", listOf(ToolCall("tu_1", "start_deep_research", mapOf("topic" to "LoRa"))), "tool_use"),
        )
        val o = orchestrator(cloud, Scopes.DEFAULT + Scopes.RESEARCH_START)
        val r = o.handle("Investiga a fondo LoRa para motos")
        assertEquals(ReplyKind.DEFERRED, r.kind)
        assertTrue(control.tasks.isEmpty())
        assertEquals(ReplyKind.DEFERRED, o.runDeferred().kind) // aún conduciendo
        mode = RideMode.STOPPED
        assertEquals(ReplyKind.TOOL_DONE, o.runDeferred().kind)
        assertEquals(1, control.tasks.size)
    }

    @Test
    fun confirmationFlowWhenStopped() {
        val cloud = ScriptedChatModel(
            ModelTurn("", listOf(ToolCall("tu_1", "start_deep_research", mapOf("topic" to "LoRa"))), "tool_use"),
        )
        val o = orchestrator(cloud, Scopes.DEFAULT + Scopes.RESEARCH_START)
        val ask = o.handle("Investiga a fondo LoRa")
        assertEquals(ReplyKind.NEEDS_CONFIRMATION, ask.kind)
        assertTrue(ask.awaitingConfirmation)
        assertTrue(control.tasks.isEmpty())
        val done = o.handle("Sí")
        assertEquals(ReplyKind.TOOL_DONE, done.kind)
        assertEquals(1, control.tasks.size)
    }

    @Test
    fun declinedConfirmationDoesNothing() {
        val cloud = ScriptedChatModel(
            ModelTurn("", listOf(ToolCall("tu_1", "start_deep_research", mapOf("topic" to "LoRa"))), "tool_use"),
        )
        val o = orchestrator(cloud, Scopes.DEFAULT + Scopes.RESEARCH_START)
        o.handle("Investiga a fondo LoRa")
        assertEquals(ReplyKind.DECLINED, o.handle("No").kind)
        assertTrue(control.tasks.isEmpty())
    }

    @Test
    fun cancelAlwaysWinsAndEndsSession() {
        val cloud = ScriptedChatModel()
        val r = orchestrator(cloud).handle("¡Cancela!")
        assertEquals(ReplyKind.CANCELLED, r.kind)
        assertTrue(r.endSession)
        assertEquals(0, cloud.requests.size)
    }

    @Test
    fun cloudFailureFallsBackToLocalCapture() {
        val cloud = ScriptedChatModel().apply { failWith = ChatModelException("sin red", retryable = true) }
        val o = orchestrator(cloud, fastPath = false)
        val r = o.handle("Guarda esta idea: casco con HUD")
        assertEquals(ReplyKind.TOOL_DONE, r.kind)
        assertEquals(1, control.ideas.size)
        val r2 = o.handle("Explícame la relatividad")
        assertEquals(ReplyKind.ERROR, r2.kind)
    }

    @Test
    fun noCloudConfiguredStillCaptures() {
        val o = orchestrator(null)
        assertEquals(ReplyKind.TOOL_DONE, o.handle("Recuérdame cambiar el aceite mañana").kind)
        assertEquals(ReplyKind.UNHANDLED, o.handle("Cuéntame un chiste").kind)
    }

    @Test
    fun modeChangeResetsHistory() {
        val cloud = ScriptedChatModel(ModelTurn("a.", emptyList(), "end_turn"), ModelTurn("b.", emptyList(), "end_turn"))
        val o = orchestrator(cloud)
        o.handle("Hola, explícame algo")
        mode = RideMode.DRIVING
        o.handle("Y otra cosa")
        assertEquals(1, cloud.requests[1].messages.size)
    }
}
