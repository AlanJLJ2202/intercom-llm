package mx.alan.intercom.core.orchestrator

import mx.alan.intercom.core.latency.LatencyTracker
import mx.alan.intercom.core.latency.Stages
import mx.alan.intercom.core.llm.ChatMessage
import mx.alan.intercom.core.llm.ChatModel
import mx.alan.intercom.core.llm.ChatModelException
import mx.alan.intercom.core.llm.ChatRequest
import mx.alan.intercom.core.llm.ToolResultMessage
import mx.alan.intercom.core.policy.InteractionPolicy
import mx.alan.intercom.core.policy.RideMode
import mx.alan.intercom.core.tools.PendingConfirmation
import mx.alan.intercom.core.tools.ToolContext
import mx.alan.intercom.core.tools.ToolInvocation
import mx.alan.intercom.core.tools.ToolRegistry
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Orquestador de un turno conversacional por voz.
 *
 * Recibe texto transcrito y devuelve el texto a sintetizar. Es síncrono y
 * sin dependencias de Android: el servicio de voz lo llama en un hilo de
 * trabajo. Reglas clave:
 *  - "cancela/detente/silencio" se atiende antes que cualquier otra cosa;
 *  - las confirmaciones pendientes se resuelven con sí/no;
 *  - el fast path local evita la nube para capturas simples;
 *  - el historial nunca se edita: si crece o cambia el modo, se reinicia.
 */
class Orchestrator(
    private val cloud: ChatModel?,
    private val local: LocalIntentModel,
    private val registry: ToolRegistry,
    private val policy: InteractionPolicy,
    private val modeProvider: () -> RideMode,
    private val latency: LatencyTracker = LatencyTracker(),
    private val clock: () -> OffsetDateTime = { OffsetDateTime.now() },
    private val useLocalFastPath: Boolean = true,
    private val maxHistoryMessages: Int = 40,
) {
    val sessionId: String = UUID.randomUUID().toString()
    private val history = mutableListOf<ChatMessage>()
    private var historyMode: RideMode? = null
    private var pending: PendingConfirmation? = null
    private val events = mutableListOf<TurnEvent>()

    @Synchronized
    fun handle(utterance: String): Reply {
        events.clear()
        val mode = modeProvider()
        val text = utterance.trim()
        if (text.isEmpty()) return reply(mode, "", ReplyKind.EMPTY)
        val norm = LocalIntentModel.normalize(text)
        val ctx = ToolContext(mode, sessionId, clock().toString(), text)

        if (CANCEL.matches(norm)) {
            pending = null
            return reply(mode, "Cancelado.", ReplyKind.CANCELLED, endSession = true)
        }
        if (END.matches(norm)) return reply(mode, "Hasta luego.", ReplyKind.ENDED, endSession = true)

        pending?.let { p ->
            pending = null
            when {
                YES.matches(norm) -> return confirmed(p, ctx, mode)
                NO.matches(norm) -> return reply(mode, "De acuerdo, no lo hago.", ReplyKind.DECLINED)
                else -> events += TurnEvent("confirmation_dropped", p.toolName)
            }
        }

        if (historyMode != mode || history.size > maxHistoryMessages) {
            history.clear()
            historyMode = mode
        }

        if (useLocalFastPath || cloud == null) {
            localTurn(text, ctx, mode)?.let { return it }
        }
        if (cloud == null) {
            return reply(mode, "No entendí. Sin conexión a la IA solo puedo guardar ideas, notas y recordatorios.", ReplyKind.UNHANDLED)
        }
        return cloudTurn(text, ctx, mode)
    }

    /** Ejecuta acciones aplazadas cuando el usuario ya está detenido. */
    @Synchronized
    fun runDeferred(): Reply {
        val mode = modeProvider()
        if (mode != RideMode.STOPPED) return reply(mode, "Lo haré cuando te detengas.", ReplyKind.DEFERRED)
        val ctx = ToolContext(mode, sessionId, clock().toString(), "(pendientes aplazados)")
        val done = registry.drainDeferred(ctx)
        if (done.isEmpty()) return reply(mode, "No hay pendientes aplazados.", ReplyKind.ANSWER)
        val ok = done.count { (_, inv) -> inv is ToolInvocation.Executed && !inv.output.isError }
        return reply(mode, "Ejecuté $ok de ${done.size} pendientes aplazados.", ReplyKind.TOOL_DONE)
    }

    private fun localTurn(text: String, ctx: ToolContext, mode: RideMode): Reply? {
        val scratch = mutableListOf<ChatMessage>(ChatMessage.User(text))
        val req = ChatRequest("", scratch, registry.specs(), 0)
        val turn = local.respond(req)
        if (turn.stopReason == LocalIntentModel.NO_MATCH || turn.toolCalls.isEmpty()) return null
        events += TurnEvent("fast_path", turn.toolCalls.joinToString { it.name })
        val outcome = runTools(turn.toolCalls, ctx)
        outcome.immediateReply?.let { return reply(mode, it.first, it.second) }
        val summary = local.respond(req.copy(messages = scratch + ChatMessage.ToolResults(outcome.results))).text
        // Se registra en el historial como texto plano para que la nube tenga contexto.
        history += ChatMessage.User(text)
        history += ChatMessage.Assistant(summary, emptyList())
        return reply(mode, summary, ReplyKind.TOOL_DONE)
    }

    private fun cloudTurn(text: String, ctx: ToolContext, mode: RideMode): Reply {
        val limits = policy.limits(mode)
        history += ChatMessage.User(text)
        var usedTool = false
        repeat(limits.maxToolRounds + 1) { round ->
            val allowTools = round < limits.maxToolRounds
            val request = ChatRequest(
                system = SystemPrompt.build(mode),
                messages = history.toList(),
                tools = if (allowTools) registry.specs() else emptyList(),
                maxOutputTokens = limits.maxOutputTokens,
            )
            val turn = try {
                cloud!!.respond(request)
            } catch (e: ChatModelException) {
                events += TurnEvent("cloud_error", e.message ?: "")
                // Se descarta el turno incompleto para no dejar historial inconsistente.
                history.clear()
                return fallbackAfterCloudError(text, ctx, mode)
            }
            if (round == 0) latency.mark(Stages.LLM_FIRST)
            events += TurnEvent("llm_round", "$round:${turn.stopReason}:in=${turn.inputTokens}:out=${turn.outputTokens}")
            history += ChatMessage.Assistant(turn.text, turn.toolCalls, turn.providerPayload)
            if (turn.toolCalls.isEmpty()) {
                val kind = when {
                    turn.stopReason == "refusal" -> ReplyKind.REFUSED
                    usedTool -> ReplyKind.TOOL_DONE
                    else -> ReplyKind.ANSWER
                }
                val spoken = turn.text.ifBlank { if (kind == ReplyKind.REFUSED) "No puedo ayudar con eso." else "Listo." }
                return reply(mode, spoken, kind)
            }
            usedTool = true
            val outcome = runTools(turn.toolCalls, ctx)
            history += ChatMessage.ToolResults(outcome.results)
            outcome.immediateReply?.let { return reply(mode, it.first, it.second) }
        }
        return reply(mode, "Terminé las acciones.", ReplyKind.TOOL_DONE)
    }

    private fun fallbackAfterCloudError(text: String, ctx: ToolContext, mode: RideMode): Reply =
        localTurn(text, ctx, mode)
            ?: reply(mode, "No hay conexión con la IA. Puedo guardar ideas, notas y recordatorios sin conexión.", ReplyKind.ERROR)

    private data class ToolsOutcome(val results: List<ToolResultMessage>, val immediateReply: Pair<String, ReplyKind>?)

    private fun runTools(calls: List<mx.alan.intercom.core.llm.ToolCall>, ctx: ToolContext): ToolsOutcome {
        val results = mutableListOf<ToolResultMessage>()
        var immediate: Pair<String, ReplyKind>? = null
        for (call in calls) {
            val inv = registry.invoke(call.name, call.arguments, ctx)
            latency.mark("tool_${call.name}")
            events += TurnEvent("tool", "${call.name}:${inv::class.simpleName}")
            results += when (inv) {
                is ToolInvocation.Executed -> ToolResultMessage(call.id, inv.output.content, inv.output.isError)
                is ToolInvocation.NeedsConfirmation -> {
                    pending = inv.pending
                    immediate = inv.pending.prompt to ReplyKind.NEEDS_CONFIRMATION
                    ToolResultMessage(call.id, "Pendiente de confirmación del usuario por voz.")
                }
                is ToolInvocation.Deferred -> {
                    if (immediate == null) immediate = "Lo dejo pendiente para cuando te detengas." to ReplyKind.DEFERRED
                    ToolResultMessage(call.id, "Aplazado hasta que el usuario esté detenido (id ${inv.item.id}).")
                }
                is ToolInvocation.Denied -> ToolResultMessage(call.id, inv.reason, isError = true)
            }
        }
        return ToolsOutcome(results, immediate)
    }

    private fun confirmed(p: PendingConfirmation, ctx: ToolContext, mode: RideMode): Reply =
        when (val inv = registry.executeConfirmed(p, ctx)) {
            is ToolInvocation.Executed ->
                if (inv.output.isError) reply(mode, "No pude: ${inv.output.content}", ReplyKind.ERROR)
                else reply(mode, "Hecho. ${inv.output.content}", ReplyKind.TOOL_DONE)
            is ToolInvocation.Denied -> reply(mode, inv.reason, ReplyKind.ERROR)
            else -> reply(mode, "No pude completar la acción.", ReplyKind.ERROR)
        }

    private fun reply(mode: RideMode, text: String, kind: ReplyKind, endSession: Boolean = false): Reply {
        latency.mark(Stages.REPLY_READY)
        return Reply(policy.shapeSpoken(mode, text), kind, endSession, events.toList(), pending != null)
    }

    companion object {
        private val CANCEL = Regex("""^(?:cancela(?:r)?|detente|alto|para|silencio|callate|olvidalo)(?:\s+(?:eso|todo|ya))?$""")
        private val END = Regex("""^(?:eso es todo|gracias,? eso es todo|termina(?:r)?(?:\s+sesion)?|adios|hasta luego)$""")
        private val YES = Regex("""^(?:si|sí|confirmo|confirmado|adelante|hazlo|de acuerdo|ok|okay|va)(?:\s+.*)?$""")
        private val NO = Regex("""^(?:no|nel|mejor no|no lo hagas)(?:\s+.*)?$""")
    }
}

enum class ReplyKind { ANSWER, TOOL_DONE, NEEDS_CONFIRMATION, DEFERRED, DECLINED, CANCELLED, ENDED, REFUSED, UNHANDLED, ERROR, EMPTY }

data class Reply(
    val spoken: String,
    val kind: ReplyKind,
    val endSession: Boolean,
    val events: List<TurnEvent>,
    val awaitingConfirmation: Boolean,
)

data class TurnEvent(val type: String, val detail: String)
