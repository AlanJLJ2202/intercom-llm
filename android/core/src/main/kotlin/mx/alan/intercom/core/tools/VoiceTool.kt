package mx.alan.intercom.core.tools

import mx.alan.intercom.core.llm.ToolSpec
import mx.alan.intercom.core.policy.RideMode
import mx.alan.intercom.core.policy.Risk

/**
 * Herramienta que el agente puede invocar por voz.
 *
 * Cada herramienta declara un *scope* (permiso) y un nivel de riesgo. El
 * modelo nunca recibe herramientas cuyo scope no se haya otorgado.
 */
interface VoiceTool {
    val spec: ToolSpec
    val scope: String
    val risk: Risk

    /** Si en modo conducción puede aplazarse en lugar de negarse. */
    val deferrable: Boolean get() = true

    /** Frase breve para pedir confirmación por voz. */
    fun confirmationPrompt(args: Map<String, Any?>): String = "¿Confirmas ${spec.name}?"

    fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolOutput
}

data class ToolContext(
    val mode: RideMode,
    val sessionId: String,
    val nowIso: String,
    /** Última frase del usuario: se adjunta como contexto a ideas y notas. */
    val lastUtterance: String,
)

data class ToolOutput(val content: String, val isError: Boolean = false)

class ToolArgumentException(message: String) : IllegalArgumentException(message)

fun Map<String, Any?>.requireString(key: String): String {
    val v = this[key]
    if (v !is String || v.isBlank()) throw ToolArgumentException("Falta el argumento '$key'")
    return v.trim()
}

fun Map<String, Any?>.optString(key: String): String? = (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
