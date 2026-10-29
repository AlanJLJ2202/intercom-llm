package mx.alan.intercom.app

import android.content.Context
import mx.alan.intercom.core.control.ControlClient
import mx.alan.intercom.core.control.HttpControlClient
import mx.alan.intercom.core.control.InMemoryControlClient
import mx.alan.intercom.core.control.OutboxControlClient
import mx.alan.intercom.core.lab.ExperimentLog
import mx.alan.intercom.core.llm.AnthropicChatModel
import mx.alan.intercom.core.llm.ChatModel
import mx.alan.intercom.core.policy.InteractionPolicy
import mx.alan.intercom.core.policy.RideMode
import mx.alan.intercom.core.tools.DeferredQueue
import mx.alan.intercom.core.tools.ToolRegistry
import mx.alan.intercom.core.tools.registerStandardTools
import java.io.File

/**
 * Contenedor de dependencias de la app (sin frameworks de DI: el grafo es
 * pequeño y así el código del laboratorio es fácil de seguir).
 */
object AppGraph {
    lateinit var settings: Settings; private set
    lateinit var events: EventLog; private set
    lateinit var experiments: ExperimentLog; private set
    lateinit var deferred: DeferredQueue; private set
    val policy = InteractionPolicy()
    private var outbox: OutboxControlClient? = null
    private var initialized = false

    @Synchronized
    fun init(ctx: Context) {
        if (initialized) return
        val app = ctx.applicationContext
        settings = Settings(app)
        events = EventLog(File(app.filesDir, "lab/events.jsonl"))
        experiments = ExperimentLog(File(app.filesDir, "lab/experiments.json"))
        if (experiments.all().isEmpty()) ExperimentLog.standardPlan().forEach { experiments.upsert(it) }
        deferred = DeferredQueue(File(app.filesDir, "deferred.json"))
        initialized = true
    }

    fun mode(): RideMode = settings.mode

    /** Control real si hay URL y token; si no, memoria local (modo demo). */
    @Synchronized
    fun control(ctx: Context): ControlClient {
        val url = settings.controlUrl
        val token = settings.controlToken
        if (url.isBlank() || token.isBlank()) return InMemoryControlClient()
        return outbox ?: OutboxControlClient(
            HttpControlClient(url, token),
            File(ctx.applicationContext.filesDir, "control-outbox.json"),
        ).also { outbox = it }
    }

    fun flushOutbox(): Int = outbox?.flush() ?: 0

    @Synchronized
    fun resetControl() { outbox = null }

    fun cloudModel(): ChatModel? =
        settings.anthropicKey.takeIf { it.isNotBlank() }?.let { AnthropicChatModel.fromApiKey(it, settings.anthropicModel) }

    fun registry(ctx: Context): ToolRegistry =
        ToolRegistry(policy, settings.grantedScopes, deferred).registerStandardTools(control(ctx))
}
