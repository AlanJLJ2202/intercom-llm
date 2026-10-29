package mx.alan.intercom.core.policy

/**
 * Modo de interacción. Se configura MANUALMENTE: no se asume que el sistema
 * pueda detectar con fiabilidad si el usuario conduce. Señales externas
 * (velocidad GPS, Android Auto) solo pueden *subir* a DRIVING, nunca bajar.
 */
enum class RideMode { STOPPED, DRIVING }

enum class Risk {
    /** Lectura o registro reversible y breve (hora, guardar idea). */
    LOW,
    /** Escritura que conviene confirmar (crear tarea con fecha). */
    MEDIUM,
    /** Acción sensible, larga o externa (enviar mensajes, borrar, investigar a fondo). */
    HIGH,
}

data class PolicyLimits(
    val maxSpokenChars: Int,
    val maxSentences: Int,
    val maxOutputTokens: Long,
    val maxToolRounds: Int,
    /** Riesgo máximo que se ejecuta de inmediato. */
    val maxImmediateRisk: Risk,
    /** Riesgo máximo que se puede ejecutar tras confirmación por voz. */
    val maxConfirmableRisk: Risk?,
)

class InteractionPolicy(
    private val stopped: PolicyLimits = PolicyLimits(
        maxSpokenChars = 1200,
        maxSentences = 12,
        maxOutputTokens = 1200,
        maxToolRounds = 5,
        maxImmediateRisk = Risk.LOW,
        maxConfirmableRisk = Risk.HIGH,
    ),
    private val driving: PolicyLimits = PolicyLimits(
        maxSpokenChars = 220,
        maxSentences = 2,
        maxOutputTokens = 300,
        maxToolRounds = 2,
        maxImmediateRisk = Risk.LOW,
        maxConfirmableRisk = Risk.MEDIUM,
    ),
) {
    fun limits(mode: RideMode): PolicyLimits = if (mode == RideMode.DRIVING) driving else stopped

    fun decide(mode: RideMode, risk: Risk, deferrable: Boolean): ToolDecision {
        val l = limits(mode)
        return when {
            risk <= l.maxImmediateRisk -> ToolDecision.EXECUTE
            l.maxConfirmableRisk != null && risk <= l.maxConfirmableRisk -> ToolDecision.CONFIRM
            deferrable -> ToolDecision.DEFER
            else -> ToolDecision.DENY
        }
    }

    /**
     * Recorta la respuesta hablada al límite del modo. Corta en frontera de
     * oración para que la síntesis no termine a media palabra.
     */
    fun shapeSpoken(mode: RideMode, text: String): String {
        val l = limits(mode)
        val clean = text
            .replace(Regex("""[*_`#>]+"""), "")
            .replace(Regex("""\[(.*?)]\((.*?)\)"""), "$1")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (clean.isEmpty()) return clean
        val sentences = Regex("""[^.!?¿¡]*[.!?]+|[^.!?]+$""").findAll(clean).map { it.value.trim() }
            .filter { it.isNotEmpty() }.toList()
        val out = StringBuilder()
        for ((i, s) in sentences.withIndex()) {
            if (i >= l.maxSentences) break
            val candidate = if (out.isEmpty()) s else "$out $s"
            if (candidate.length > l.maxSpokenChars) break
            out.clear().append(candidate)
        }
        if (out.isEmpty()) {
            // Una sola oración demasiado larga: cortar por palabras.
            val words = clean.split(' ')
            for (w in words) {
                if (out.length + w.length + 1 > l.maxSpokenChars - 1) break
                if (out.isNotEmpty()) out.append(' ')
                out.append(w)
            }
            out.append('…')
        }
        return out.toString()
    }
}

enum class ToolDecision { EXECUTE, CONFIRM, DEFER, DENY }
