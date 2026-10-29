package mx.alan.intercom.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Registro estructurado de eventos del laboratorio (JSON Lines).
 * Todo lo que ocurre con Bluetooth, audio, botones y la sesión pasa por aquí:
 * es la evidencia que respalda (o refuta) cada experimento.
 */
class EventLog(private val file: File) {
    data class Event(val ts: String, val category: String, val message: String, val data: Map<String, Any?> = emptyMap())

    private val mapper = jacksonObjectMapper()
    private val recent = ArrayDeque<Event>()
    private val listeners = CopyOnWriteArrayList<(Event) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun log(category: String, message: String, data: Map<String, Any?> = emptyMap()) {
        val e = Event(OffsetDateTime.now().toString(), category, message, data)
        Log.i("IntercomLab", "[$category] $message ${if (data.isEmpty()) "" else data}")
        synchronized(this) {
            recent.addLast(e)
            while (recent.size > 500) recent.removeFirst()
            try {
                file.parentFile?.mkdirs()
                file.appendText(mapper.writeValueAsString(e) + "\n")
            } catch (_: Exception) {
            }
        }
        listeners.forEach { l -> main.post { l(e) } }
    }

    @Synchronized
    fun recent(n: Int = 100): List<Event> = recent.toList().takeLast(n)

    fun addListener(l: (Event) -> Unit) { listeners += l }
    fun removeListener(l: (Event) -> Unit) { listeners -= l }

    @Synchronized
    fun exportJsonl(): String = if (file.exists()) file.readText() else ""

    @Synchronized
    fun clear() { recent.clear(); file.delete() }
}
