package mx.alan.intercom.core

import mx.alan.intercom.core.control.InMemoryControlClient
import mx.alan.intercom.core.policy.InteractionPolicy
import mx.alan.intercom.core.policy.RideMode
import mx.alan.intercom.core.policy.Risk
import mx.alan.intercom.core.policy.ToolDecision
import mx.alan.intercom.core.tools.DeferredQueue
import mx.alan.intercom.core.tools.Scopes
import mx.alan.intercom.core.tools.ToolContext
import mx.alan.intercom.core.tools.ToolInvocation
import mx.alan.intercom.core.tools.ToolRegistry
import mx.alan.intercom.core.tools.registerStandardTools
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PolicyAndRegistryTest {
    private val policy = InteractionPolicy()

    @Test
    fun drivingShortensToTwoSentences() {
        val long = "Primera frase. Segunda frase corta. Tercera frase que no debe oírse. Cuarta."
        assertEquals("Primera frase. Segunda frase corta.", policy.shapeSpoken(RideMode.DRIVING, long))
        assertTrue(policy.shapeSpoken(RideMode.STOPPED, long).contains("Cuarta"))
    }

    @Test
    fun markdownIsStrippedForSpeech() {
        assertEquals("Hola mundo.", policy.shapeSpoken(RideMode.STOPPED, "**Hola** _mundo_."))
        assertEquals("Ver docs.", policy.shapeSpoken(RideMode.STOPPED, "Ver [docs](https://x.y)."))
    }

    @Test
    fun overlongSingleSentenceIsCutOnWordBoundary() {
        val text = (1..80).joinToString(" ") { "palabra$it" }
        val out = policy.shapeSpoken(RideMode.DRIVING, text)
        assertTrue(out.length <= 220)
        assertTrue(out.endsWith("…"))
    }

    @Test
    fun riskDecisionsByMode() {
        assertEquals(ToolDecision.EXECUTE, policy.decide(RideMode.DRIVING, Risk.LOW, true))
        assertEquals(ToolDecision.CONFIRM, policy.decide(RideMode.DRIVING, Risk.MEDIUM, true))
        assertEquals(ToolDecision.DEFER, policy.decide(RideMode.DRIVING, Risk.HIGH, true))
        assertEquals(ToolDecision.DENY, policy.decide(RideMode.DRIVING, Risk.HIGH, false))
        assertEquals(ToolDecision.CONFIRM, policy.decide(RideMode.STOPPED, Risk.HIGH, true))
    }

    private fun ctx(mode: RideMode) = ToolContext(mode, "s1", "2026-10-29T10:00:00-06:00", "frase")

    @Test
    fun ungrantedScopesAreHiddenAndDenied() {
        val control = InMemoryControlClient()
        val reg = ToolRegistry(policy, setOf(Scopes.IDEAS_WRITE), DeferredQueue()).registerStandardTools(control)
        assertEquals(listOf("save_idea"), reg.specs().map { it.name })
        assertIs<ToolInvocation.Denied>(reg.invoke("dictate_note", mapOf("text" to "x"), ctx(RideMode.STOPPED)))
        assertIs<ToolInvocation.Executed>(reg.invoke("save_idea", mapOf("text" to "x"), ctx(RideMode.STOPPED)))
        assertEquals(1, control.ideas.size)
        assertEquals("frase", control.ideas[0].context.utterance)
    }

    @Test
    fun highRiskIsDeferredWhileDrivingAndDrainedWhenStopped() {
        val control = InMemoryControlClient()
        val deferred = DeferredQueue()
        val reg = ToolRegistry(policy, Scopes.DEFAULT + Scopes.RESEARCH_START, deferred).registerStandardTools(control)
        val inv = reg.invoke("start_deep_research", mapOf("topic" to "traductores"), ctx(RideMode.DRIVING))
        assertIs<ToolInvocation.Deferred>(inv)
        assertTrue(control.tasks.isEmpty())
        val drained = reg.drainDeferred(ctx(RideMode.STOPPED))
        assertEquals(1, drained.size)
        assertEquals(1, control.tasks.size)
        assertTrue(deferred.pending().isEmpty())
    }

    @Test
    fun missingArgumentBecomesToolErrorNotCrash() {
        val reg = ToolRegistry(policy, Scopes.DEFAULT, DeferredQueue()).registerStandardTools(InMemoryControlClient())
        val inv = reg.invoke("save_idea", emptyMap(), ctx(RideMode.STOPPED))
        assertIs<ToolInvocation.Executed>(inv)
        assertTrue(inv.output.isError)
    }

    @Test
    fun wildcardScope() {
        val reg = ToolRegistry(policy, setOf("control.*"), DeferredQueue()).registerStandardTools(InMemoryControlClient())
        assertTrue(reg.specs().any { it.name == "start_deep_research" })
        assertFalse(reg.specs().any { it.name == "current_time" })
    }
}
