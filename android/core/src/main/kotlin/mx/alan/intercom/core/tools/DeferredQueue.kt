package mx.alan.intercom.core.tools

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.util.UUID

data class DeferredItem(
    val id: String,
    val toolName: String,
    val args: Map<String, Any?>,
    val createdAt: String,
    val context: String,
)

/**
 * Cola de acciones aplazadas hasta que el usuario esté detenido.
 * Persistencia opcional en JSON para sobrevivir reinicios del servicio.
 */
class DeferredQueue(private val file: File? = null) {
    private val mapper = jacksonObjectMapper()
    private val items = mutableListOf<DeferredItem>()

    init {
        if (file != null && file.exists()) {
            items += mapper.readValue<List<DeferredItem>>(file)
        }
    }

    @Synchronized
    fun add(toolName: String, args: Map<String, Any?>, ctx: ToolContext): DeferredItem {
        val item = DeferredItem(UUID.randomUUID().toString(), toolName, args, ctx.nowIso, ctx.lastUtterance)
        items += item
        persist()
        return item
    }

    @Synchronized
    fun pending(): List<DeferredItem> = items.toList()

    @Synchronized
    fun drain(): List<DeferredItem> {
        val out = items.toList()
        items.clear()
        persist()
        return out
    }

    private fun persist() {
        file?.let {
            it.parentFile?.mkdirs()
            mapper.writeValue(it, items)
        }
    }
}
