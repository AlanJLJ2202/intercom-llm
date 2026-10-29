package mx.alan.intercom.core.tools

import mx.alan.intercom.core.control.CaptureContext
import mx.alan.intercom.core.control.ControlClient
import mx.alan.intercom.core.control.IdeaInput
import mx.alan.intercom.core.control.NoteInput
import mx.alan.intercom.core.control.TaskInput
import mx.alan.intercom.core.llm.PropertySpec
import mx.alan.intercom.core.llm.ToolSpec
import mx.alan.intercom.core.policy.Risk
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Scopes conocidos. Se otorgan explícitamente en la configuración. */
object Scopes {
    const val IDEAS_WRITE = "control.ideas.write"
    const val RADAR_WRITE = "control.radar.write"
    const val TASKS_WRITE = "control.tasks.write"
    const val TASKS_READ = "control.tasks.read"
    const val NOTES_WRITE = "control.notes.write"
    const val KNOWLEDGE_READ = "control.knowledge.read"
    const val RESULTS_READ = "control.results.read"
    const val RESEARCH_START = "control.research.start"
    const val DEVICE_CLOCK = "device.clock"

    /** Conjunto por defecto: lectura y captura de bajo riesgo. Sin investigación profunda. */
    val DEFAULT = setOf(IDEAS_WRITE, RADAR_WRITE, TASKS_WRITE, TASKS_READ, NOTES_WRITE, KNOWLEDGE_READ, RESULTS_READ, DEVICE_CLOCK)
}

private fun ToolContext.capture() = CaptureContext(lastUtterance, mode.name, nowIso, sessionId)

private fun str(desc: String) = PropertySpec("string", desc)

class SaveIdeaTool(private val control: ControlClient) : VoiceTool {
    override val scope = Scopes.IDEAS_WRITE
    override val risk = Risk.LOW
    override val spec = ToolSpec(
        "save_idea",
        "Guarda una idea en Control para investigarla después. Úsala cuando el usuario diga 'guarda esta idea', 'anota la idea', etc.",
        mapOf("text" to str("La idea, redactada en una frase clara"), "tags" to str("Etiquetas separadas por coma (opcional)")),
        listOf("text"),
    )

    override fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolOutput {
        val r = control.saveIdea(IdeaInput(args.requireString("text"), tagsOf(args), ctx.capture()))
        return ToolOutput(if (r.queuedOffline) "Idea guardada sin conexión; se sincroniza después (id ${r.id})." else "Idea guardada (id ${r.id}).")
    }
}

class SendToRadarTool(private val control: ControlClient) : VoiceTool {
    override val scope = Scopes.RADAR_WRITE
    override val risk = Risk.LOW
    override val spec = ToolSpec(
        "send_to_radar",
        "Envía una oportunidad de negocio a China Opportunity Radar para investigarla después. Úsala cuando el usuario mencione 'el radar'.",
        mapOf("text" to str("Descripción de la oportunidad"), "tags" to str("Etiquetas separadas por coma (opcional)")),
        listOf("text"),
    )

    override fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolOutput {
        val r = control.sendToRadar(IdeaInput(args.requireString("text"), tagsOf(args) + "radar", ctx.capture()))
        return ToolOutput(if (r.queuedOffline) "Enviado al radar sin conexión; se sincroniza después." else "Enviado al radar (id ${r.id}).")
    }
}

class CreateTaskTool(private val control: ControlClient) : VoiceTool {
    override val scope = Scopes.TASKS_WRITE
    override val risk = Risk.LOW
    override val spec = ToolSpec(
        "create_task",
        "Crea una tarea o recordatorio en Control. 'trigger' describe condiciones como 'al llegar a casa'.",
        mapOf(
            "title" to str("Título breve de la tarea"),
            "due" to str("Fecha/hora ISO-8601 si el usuario la dio (opcional)"),
            "trigger" to str("Condición del recordatorio, p. ej. 'al llegar a casa' (opcional)"),
        ),
        listOf("title"),
    )

    override fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolOutput {
        val r = control.createTask(TaskInput(args.requireString("title"), args.optString("due"), args.optString("trigger"), ctx.capture()))
        return ToolOutput("Tarea creada (id ${r.id}${if (r.queuedOffline) ", pendiente de sincronizar" else ""}).")
    }
}

class DictateNoteTool(private val control: ControlClient) : VoiceTool {
    override val scope = Scopes.NOTES_WRITE
    override val risk = Risk.LOW
    override val spec = ToolSpec(
        "dictate_note",
        "Registra una nota de voz transcrita literalmente en Control.",
        mapOf("text" to str("Texto literal de la nota")),
        listOf("text"),
    )

    override fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolOutput {
        val r = control.addNote(NoteInput(args.requireString("text"), ctx.capture()))
        return ToolOutput("Nota registrada (id ${r.id}).")
    }
}

class AskKnowledgeTool(private val control: ControlClient) : VoiceTool {
    override val scope = Scopes.KNOWLEDGE_READ
    override val risk = Risk.LOW
    override val spec = ToolSpec(
        "ask_knowledge_agent",
        "Consulta al agente de conocimiento de Control (investigaciones, notas y hallazgos previos del usuario).",
        mapOf("question" to str("Pregunta concreta")),
        listOf("question"),
    )

    override fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolOutput {
        val a = control.queryKnowledge(args.requireString("question"))
        return ToolOutput(a.answer + if (a.sources.isNotEmpty()) " Fuentes: ${a.sources.take(3).joinToString()}" else "")
    }
}

class RecentResultsTool(private val control: ControlClient) : VoiceTool {
    override val scope = Scopes.RESULTS_READ
    override val risk = Risk.LOW
    override val spec = ToolSpec(
        "recent_results",
        "Resume los resultados más recientes de investigaciones en Control.",
        mapOf("limit" to PropertySpec("integer", "Cuántos resultados (1-5)")),
        emptyList(),
    )

    override fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolOutput {
        val limit = ((args["limit"] as? Number)?.toInt() ?: 3).coerceIn(1, 5)
        val items = control.recentResults(limit)
        if (items.isEmpty()) return ToolOutput("No hay resultados recientes.")
        return ToolOutput(items.joinToString("\n") { "- ${it.title}: ${it.summary}" })
    }
}

class PendingTodayTool(private val control: ControlClient) : VoiceTool {
    override val scope = Scopes.TASKS_READ
    override val risk = Risk.LOW
    override val spec = ToolSpec("pending_today", "Lista las tareas pendientes de hoy en Control.", emptyMap(), emptyList())

    override fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolOutput {
        val items = control.pendingToday()
        if (items.isEmpty()) return ToolOutput("No hay pendientes para hoy.")
        return ToolOutput(items.joinToString("\n") { "- ${it.title}" + (it.due?.let { d -> " ($d)" } ?: "") })
    }
}

class StartResearchTool(private val control: ControlClient) : VoiceTool {
    override val scope = Scopes.RESEARCH_START
    override val risk = Risk.HIGH
    override val spec = ToolSpec(
        "start_deep_research",
        "Inicia una investigación larga en Control. Es costosa: en conducción se aplaza automáticamente.",
        mapOf("topic" to str("Tema a investigar")),
        listOf("topic"),
    )

    override fun confirmationPrompt(args: Map<String, Any?>) =
        "¿Inicio una investigación a fondo sobre ${args["topic"]}? Di sí o no."

    override fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolOutput {
        val r = control.createTask(TaskInput("Investigar a fondo: ${args.requireString("topic")}", null, "research", ctx.capture()))
        return ToolOutput("Investigación encolada en Control (id ${r.id}).")
    }
}

class CurrentTimeTool(private val clock: () -> ZonedDateTime = { ZonedDateTime.now() }) : VoiceTool {
    override val scope = Scopes.DEVICE_CLOCK
    override val risk = Risk.LOW
    override val spec = ToolSpec("current_time", "Devuelve la fecha y hora local.", emptyMap(), emptyList())
    override fun execute(args: Map<String, Any?>, ctx: ToolContext) =
        ToolOutput(clock().format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM, HH:mm", Locale.forLanguageTag("es-MX"))))
}

private fun tagsOf(args: Map<String, Any?>): List<String> =
    args.optString("tags")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

/** Registra el conjunto estándar de herramientas sobre un cliente de Control. */
fun ToolRegistry.registerStandardTools(control: ControlClient): ToolRegistry = apply {
    register(SaveIdeaTool(control))
    register(SendToRadarTool(control))
    register(CreateTaskTool(control))
    register(DictateNoteTool(control))
    register(AskKnowledgeTool(control))
    register(RecentResultsTool(control))
    register(PendingTodayTool(control))
    register(StartResearchTool(control))
    register(CurrentTimeTool())
}
