package mx.alan.intercom.core.control

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.util.UUID

/**
 * Decorador tolerante a pérdida de conectividad: las *escrituras* (ideas,
 * notas, tareas, radar) que fallan por red se guardan en una bandeja local
 * y se reenvían con [flush]. Las lecturas fallan explícitamente.
 *
 * En carretera la cobertura es intermitente; perder una idea dictada es el
 * peor resultado posible, así que se prioriza guardar localmente.
 */
class OutboxControlClient(private val delegate: ControlClient, private val file: File? = null) : ControlClient {
    data class Entry(val id: String, val op: String, val payload: Map<String, Any?>)

    private val mapper = jacksonObjectMapper()
    private val entries = mutableListOf<Entry>()

    init {
        if (file != null && file.exists()) entries += mapper.readValue<List<Entry>>(file)
    }

    @Synchronized fun pendingCount() = entries.size

    override fun saveIdea(idea: IdeaInput) = write("idea", idea) { delegate.saveIdea(idea) }
    override fun sendToRadar(idea: IdeaInput) = write("radar_idea", idea) { delegate.sendToRadar(idea) }
    override fun createTask(task: TaskInput) = write("task", task) { delegate.createTask(task) }
    override fun addNote(note: NoteInput) = write("note", note) { delegate.addNote(note) }
    override fun queryKnowledge(question: String) = delegate.queryKnowledge(question)
    override fun recentResults(limit: Int) = delegate.recentResults(limit)
    override fun pendingToday() = delegate.pendingToday()

    /** Reintenta la bandeja. Devuelve cuántas entradas se enviaron. */
    @Synchronized
    fun flush(): Int {
        var sent = 0
        val it = entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            try {
                when (e.op) {
                    "idea" -> delegate.saveIdea(mapper.convertValue(e.payload, IdeaInput::class.java))
                    "radar_idea" -> delegate.sendToRadar(mapper.convertValue(e.payload, IdeaInput::class.java))
                    "task" -> delegate.createTask(mapper.convertValue(e.payload, TaskInput::class.java))
                    "note" -> delegate.addNote(mapper.convertValue(e.payload, NoteInput::class.java))
                }
                it.remove()
                sent++
            } catch (ex: ControlException) {
                if (!ex.retryable) { it.remove() } else break
            }
        }
        persist()
        return sent
    }

    private fun write(op: String, payload: Any, call: () -> ControlRecord): ControlRecord =
        try {
            call()
        } catch (e: ControlException) {
            if (!e.retryable) throw e
            val entry = synchronized(this) {
                @Suppress("UNCHECKED_CAST")
                Entry(UUID.randomUUID().toString(), op, mapper.convertValue(payload, Map::class.java) as Map<String, Any?>)
                    .also { entries += it; persist() }
            }
            ControlRecord(entry.id, op, status = "queued_offline", queuedOffline = true)
        }

    private fun persist() {
        file?.let { it.parentFile?.mkdirs(); mapper.writeValue(it, entries) }
    }
}
