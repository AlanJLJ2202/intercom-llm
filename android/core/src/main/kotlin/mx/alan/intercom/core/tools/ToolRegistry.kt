package mx.alan.intercom.core.tools

import mx.alan.intercom.core.llm.ToolSpec
import mx.alan.intercom.core.policy.InteractionPolicy
import mx.alan.intercom.core.policy.RideMode
import mx.alan.intercom.core.policy.ToolDecision
import java.util.UUID

/**
 * Registro de herramientas con permisos por scope y decisión de política.
 *
 * El registro es la única puerta entre el modelo y los efectos reales:
 * valida scope, aplica la política del modo y gestiona confirmaciones
 * y aplazamientos.
 */
class ToolRegistry(
    private val policy: InteractionPolicy,
    grantedScopes: Set<String>,
    private val deferred: DeferredQueue,
) {
    private val tools = linkedMapOf<String, VoiceTool>()
    private val granted = grantedScopes.toMutableSet()

    fun register(tool: VoiceTool): ToolRegistry {
        require(tool.spec.name !in tools) { "Herramienta duplicada: ${tool.spec.name}" }
        tools[tool.spec.name] = tool
        return this
    }

    fun grant(scope: String) { granted += scope }
    fun revoke(scope: String) { granted -= scope }

    fun isGranted(scope: String): Boolean =
        scope in granted || granted.any { it.endsWith(".*") && scope.startsWith(it.removeSuffix("*")) }

    /** Herramientas visibles para el modelo: solo las de scopes otorgados. */
    fun specs(): List<ToolSpec> = tools.values.filter { isGranted(it.scope) }.map { it.spec }

    fun names(): Set<String> = tools.keys

    fun invoke(name: String, args: Map<String, Any?>, ctx: ToolContext): ToolInvocation {
        val tool = tools[name] ?: return ToolInvocation.Denied("Herramienta desconocida: $name")
        if (!isGranted(tool.scope)) return ToolInvocation.Denied("Sin permiso para ${tool.scope}")
        return when (policy.decide(ctx.mode, tool.risk, tool.deferrable)) {
            ToolDecision.EXECUTE -> runSafely(tool, args, ctx)
            ToolDecision.CONFIRM -> ToolInvocation.NeedsConfirmation(
                PendingConfirmation(UUID.randomUUID().toString(), name, args, tool.confirmationPrompt(args)),
            )
            ToolDecision.DEFER -> {
                val item = deferred.add(name, args, ctx)
                ToolInvocation.Deferred(item)
            }
            ToolDecision.DENY -> ToolInvocation.Denied("No disponible en modo ${ctx.mode}")
        }
    }

    /** Ejecuta una acción ya confirmada por el usuario. Revalida scope. */
    fun executeConfirmed(pending: PendingConfirmation, ctx: ToolContext): ToolInvocation {
        val tool = tools[pending.toolName] ?: return ToolInvocation.Denied("Herramienta desconocida")
        if (!isGranted(tool.scope)) return ToolInvocation.Denied("Sin permiso para ${tool.scope}")
        return runSafely(tool, pending.args, ctx)
    }

    /** Ejecuta pendientes aplazados; solo permitido en modo detenido. */
    fun drainDeferred(ctx: ToolContext): List<Pair<DeferredItem, ToolInvocation>> {
        require(ctx.mode == RideMode.STOPPED) { "Los pendientes solo se ejecutan detenido" }
        return deferred.drain().map { item ->
            val tool = tools[item.toolName]
            item to if (tool == null || !isGranted(tool.scope)) {
                ToolInvocation.Denied("Ya no disponible: ${item.toolName}")
            } else {
                runSafely(tool, item.args, ctx)
            }
        }
    }

    private fun runSafely(tool: VoiceTool, args: Map<String, Any?>, ctx: ToolContext): ToolInvocation =
        try {
            ToolInvocation.Executed(tool.execute(args, ctx))
        } catch (e: ToolArgumentException) {
            ToolInvocation.Executed(ToolOutput(e.message ?: "Argumentos inválidos", isError = true))
        } catch (e: Exception) {
            ToolInvocation.Executed(ToolOutput("Error en ${tool.spec.name}: ${e.message}", isError = true))
        }
}

data class PendingConfirmation(
    val id: String,
    val toolName: String,
    val args: Map<String, Any?>,
    val prompt: String,
)

sealed interface ToolInvocation {
    data class Executed(val output: ToolOutput) : ToolInvocation
    data class NeedsConfirmation(val pending: PendingConfirmation) : ToolInvocation
    data class Deferred(val item: DeferredItem) : ToolInvocation
    data class Denied(val reason: String) : ToolInvocation
}
