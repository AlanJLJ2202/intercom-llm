package mx.alan.intercom.core.orchestrator

import mx.alan.intercom.core.llm.ChatMessage
import mx.alan.intercom.core.llm.ChatModel
import mx.alan.intercom.core.llm.ChatRequest
import mx.alan.intercom.core.llm.ModelTurn
import mx.alan.intercom.core.llm.ToolCall
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Enrutador de intenciones local (español), sin red.
 *
 * Dos usos:
 * 1. *Fast path*: órdenes de captura frecuentes ("guarda esta idea…") se
 *    resuelven sin LLM: menor latencia y funcionan sin cobertura.
 * 2. *Fallback*: si el proveedor en la nube falla, el usuario aún puede
 *    capturar ideas, notas y recordatorios.
 *
 * Si no reconoce la frase devuelve stopReason = [NO_MATCH].
 */
class LocalIntentModel : ChatModel {
    override val id = "local-intents-es"
    private val seq = AtomicInteger()

    private data class Rule(val regex: Regex, val build: (MatchResult) -> ToolCall)

    private fun call(name: String, vararg args: Pair<String, Any?>) =
        ToolCall("local_${seq.incrementAndGet()}", name, mapOf(*args))

    private val rules = listOf(
        Rule(Regex("""^(?:encontre|tengo|guarda|anota|apunta)\s+(?:una\s+|esta\s+)?idea\s+para\s+el\s+radar[\s:,.-]*(.+)$""")) {
            call("send_to_radar", "text" to cleanTail(it.groupValues[1]))
        },
        Rule(Regex("""^(?:manda|envia|agrega|pon)\s+(?:esto\s+)?(?:al|en el)\s+radar[\s:,.-]*(.+)$""")) {
            call("send_to_radar", "text" to cleanTail(it.groupValues[1]))
        },
        Rule(Regex("""^(?:guarda|anota|apunta|registra)\s+(?:esta|una|la)?\s*idea(?:\s+en\s+mis\s+notas)?[\s:,.-]*(.+)$""")) {
            call("save_idea", "text" to cleanTail(it.groupValues[1]))
        },
        Rule(Regex("""^(?:registra|graba|toma|guarda)\s+(?:una\s+)?nota(?:\s+de\s+voz)?[\s:,.-]*(.+)$""")) {
            call("dictate_note", "text" to it.groupValues[1].trim())
        },
        Rule(Regex("""^recuerdame\s+(.+?)\s+(al llegar a casa|al llegar al trabajo|cuando llegue a casa|cuando me detenga|manana|en la noche|esta noche|mas tarde)$""")) {
            call("create_task", "title" to cleanTail(it.groupValues[1]), "trigger" to it.groupValues[2])
        },
        Rule(Regex("""^recuerdame\s+(.+)$""")) { call("create_task", "title" to cleanTail(it.groupValues[1])) },
        Rule(Regex("""^que\s+(?:tengo|hay)\s+pendiente(?:s)?(?:\s+(?:hoy|para hoy))?$""")) { call("pending_today") },
        Rule(Regex("""^(?:que hora es|dime la hora)$""")) { call("current_time") },
    )

    override fun respond(request: ChatRequest): ModelTurn {
        return when (val last = request.messages.lastOrNull()) {
            is ChatMessage.ToolResults -> ModelTurn(summarize(last), emptyList(), "end_turn")
            is ChatMessage.User -> {
                val norm = normalize(last.text)
                val allowed = request.tools.map { it.name }.toSet()
                rules.firstNotNullOfOrNull { r -> r.regex.find(norm)?.let(r.build) }
                    ?.takeIf { it.name in allowed }
                    ?.let { ModelTurn("", listOf(restoreText(it, last.text)), "tool_use") }
                    ?: ModelTurn("", emptyList(), NO_MATCH)
            }
            else -> ModelTurn("", emptyList(), NO_MATCH)
        }
    }

    private fun summarize(results: ChatMessage.ToolResults): String =
        results.results.joinToString(" ") { r -> if (r.isError) "No pude completarlo: ${r.content}" else "Listo. ${r.content}" }

    /**
     * Las reglas operan sobre texto normalizado (sin acentos, minúsculas) pero
     * el contenido guardado debe conservar la ortografía original del usuario.
     */
    private fun restoreText(call: ToolCall, original: String): ToolCall {
        val key = listOf("text", "title").firstOrNull { call.arguments[it] is String } ?: return call
        val normValue = call.arguments[key] as String
        val idx = normalize(original).indexOf(normValue)
        if (idx < 0) return call
        // normalize() conserva la longitud carácter a carácter, así que los
        // índices sobre el texto original (mismo recorte) son equivalentes.
        val cased = stripOuterPunct(original.trim()).substring(idx, idx + normValue.length)
        return call.copy(arguments = call.arguments + (key to cased))
    }

    companion object {
        const val NO_MATCH = "no_match"

        private fun stripOuterPunct(s: String) = s.trim().trimEnd('.', '!', '?', ',').trimStart('¿', '¡')

        fun normalize(s: String): String {
            val base = stripOuterPunct(s.trim().lowercase())
            // Quitar diacríticos carácter por carácter para conservar índices.
            val sb = StringBuilder(base.length)
            for (ch in base) {
                val d = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
                sb.append(d[0])
            }
            return sb.toString()
        }

        private fun cleanTail(s: String) = s.trim().trimEnd('.', ',', ';')
    }
}
