package mx.alan.intercom.core.bench

import mx.alan.intercom.core.control.InMemoryControlClient
import mx.alan.intercom.core.latency.LatencyTracker
import mx.alan.intercom.core.latency.Stages
import mx.alan.intercom.core.llm.AnthropicChatModel
import mx.alan.intercom.core.orchestrator.LocalIntentModel
import mx.alan.intercom.core.orchestrator.Orchestrator
import mx.alan.intercom.core.policy.InteractionPolicy
import mx.alan.intercom.core.policy.RideMode
import mx.alan.intercom.core.tools.DeferredQueue
import mx.alan.intercom.core.tools.Scopes
import mx.alan.intercom.core.tools.ToolRegistry
import mx.alan.intercom.core.tools.registerStandardTools

/**
 * EXP-09 (parte de software): mide la latencia texto→respuesta del
 * orquestador, desde la transcripción final hasta el texto listo para TTS.
 * No incluye STT, TTS ni Bluetooth: eso se mide en el teléfono.
 *
 * Uso: ANTHROPIC_API_KEY=... gradle :core:bench [-PbenchModel=claude-opus-5-5] [-PbenchReps=5]
 */
fun main(args: Array<String>) {
    val model = args.getOrNull(0) ?: AnthropicChatModel.DEFAULT_MODEL
    val reps = args.getOrNull(1)?.toIntOrNull() ?: 5
    val key = System.getenv("ANTHROPIC_API_KEY")
    val utterances = listOf(
        "Guarda esta idea: un mapa de cafés para motociclistas en el Bajío",
        "¿Qué hora es?",
        "Explícame brevemente qué es la latencia de red",
        "Oye, apunta que mañana revise la presión de las llantas",
    )
    val policy = InteractionPolicy()
    val cloud = key?.let { AnthropicChatModel.fromApiKey(it, model) }
    if (cloud == null) println("ANTHROPIC_API_KEY no definida: solo se mide el fast path local.")
    for (mode in RideMode.entries) {
        for (u in utterances) {
            val samples = mutableListOf<Long>()
            var last = ""
            repeat(reps) {
                val lt = LatencyTracker()
                val o = Orchestrator(cloud, LocalIntentModel(),
                    ToolRegistry(policy, Scopes.DEFAULT, DeferredQueue()).registerStandardTools(InMemoryControlClient()),
                    policy, { mode }, lt)
                lt.start(Stages.STT_FINAL)
                val r = o.handle(u)
                samples += lt.between(Stages.STT_FINAL, Stages.REPLY_READY) ?: -1
                last = "${r.kind}: ${r.spoken}"
            }
            samples.sort()
            val p50 = samples[samples.size / 2]
            val p90 = samples[((samples.size - 1) * 0.9).toInt()]
            println("[$mode] p50=${p50}ms p90=${p90}ms n=$reps  «$u» → $last")
        }
    }
}
