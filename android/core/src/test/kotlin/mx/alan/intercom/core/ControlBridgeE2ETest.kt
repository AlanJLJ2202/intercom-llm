package mx.alan.intercom.core

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mx.alan.intercom.core.control.HttpControlClient
import mx.alan.intercom.core.orchestrator.LocalIntentModel
import mx.alan.intercom.core.orchestrator.Orchestrator
import mx.alan.intercom.core.orchestrator.ReplyKind
import mx.alan.intercom.core.policy.InteractionPolicy
import mx.alan.intercom.core.policy.RideMode
import mx.alan.intercom.core.tools.DeferredQueue
import mx.alan.intercom.core.tools.Scopes
import mx.alan.intercom.core.tools.ToolRegistry
import mx.alan.intercom.core.tools.registerStandardTools
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integración real núcleo Kotlin ↔ control-bridge (Node). Se ejecuta solo si
 * CONTROL_E2E_URL y CONTROL_E2E_TOKEN están definidos (ver scripts/e2e_control.sh).
 * Simula la frase transcrita; no involucra el casco.
 */
class ControlBridgeE2ETest {
    @Test
    fun radarIdeaReachesControl() {
        val url = System.getenv("CONTROL_E2E_URL") ?: return
        val token = System.getenv("CONTROL_E2E_TOKEN") ?: return
        val policy = InteractionPolicy()
        val orchestrator = Orchestrator(
            cloud = null,
            local = LocalIntentModel(),
            registry = ToolRegistry(policy, Scopes.DEFAULT, DeferredQueue()).registerStandardTools(HttpControlClient(url, token)),
            policy = policy,
            modeProvider = { RideMode.DRIVING },
        )
        val phrase = "Encontré una idea para el radar: un dispositivo que traduce conversaciones mientras viajo. Investígalo después"
        val reply = orchestrator.handle(phrase)
        println("Respuesta hablada: ${reply.spoken}")
        assertEquals(ReplyKind.TOOL_DONE, reply.kind)

        val readToken = System.getenv("CONTROL_E2E_READ_TOKEN") ?: return
        val body = OkHttpClient().newCall(
            Request.Builder().url("${url.trimEnd('/')}/v1/radar/ideas").header("Authorization", "Bearer $readToken").build(),
        ).execute().use { it.body!!.string() }
        val first = jacksonObjectMapper().readTree(body)["items"][0]
        assertTrue(first["text"].asText().startsWith("un dispositivo que traduce conversaciones"))
        assertEquals(phrase, first["context"]["utterance"].asText())
        assertEquals("DRIVING", first["context"]["mode"].asText())
        println("Registro en Control: $first")
    }
}
