package mx.alan.intercom.core.lab

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File

/**
 * Registro estructurado de experimentos (metodología: observar → hipótesis →
 * experimentar → medir → documentar → iterar).
 *
 * Regla: un experimento FÍSICO solo puede quedar CONFIRMED/REFUTED si su
 * evidencia proviene del dispositivo real ([Evidence.source] = DEVICE).
 */
enum class ExperimentStatus { PENDING, RUNNING, CONFIRMED, REFUTED, INCONCLUSIVE, BLOCKED }

enum class EvidenceSource {
    /** Capturado contra el EJEAS real (logs de la app, bluetoothctl, audio). */
    DEVICE,
    /** Simulación o prueba automatizada sin hardware. */
    SIMULATION,
    /** Documentación pública (manual, ficha del fabricante). */
    DOCUMENTATION,
}

data class Evidence(
    val source: EvidenceSource,
    val description: String,
    val capturedAt: String,
    val artifact: String? = null,
    val data: Map<String, Any?> = emptyMap(),
)

data class Experiment(
    val id: String,
    val title: String,
    val hypothesis: String,
    val procedure: List<String>,
    val physical: Boolean,
    val status: ExperimentStatus = ExperimentStatus.PENDING,
    val evidence: List<Evidence> = emptyList(),
    val result: String? = null,
    val nextStep: String? = null,
    val device: DeviceContext? = null,
)

data class DeviceContext(
    val model: String?,
    val firmware: String?,
    val phone: String?,
    val os: String?,
)

class ExperimentRuleViolation(message: String) : IllegalStateException(message)

class ExperimentLog(private val file: File? = null) {
    private val mapper = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    private val items = linkedMapOf<String, Experiment>()

    init {
        if (file != null && file.exists()) mapper.readValue<List<Experiment>>(file).forEach { items[it.id] = it }
    }

    fun all(): List<Experiment> = items.values.toList()
    fun get(id: String): Experiment? = items[id]

    fun upsert(e: Experiment): Experiment {
        validate(e)
        items[e.id] = e
        persist()
        return e
    }

    fun addEvidence(id: String, ev: Evidence): Experiment {
        val e = items[id] ?: throw NoSuchElementException(id)
        return upsert(e.copy(evidence = e.evidence + ev, status = if (e.status == ExperimentStatus.PENDING) ExperimentStatus.RUNNING else e.status))
    }

    fun conclude(id: String, status: ExperimentStatus, result: String, nextStep: String?, device: DeviceContext? = null): Experiment {
        val e = items[id] ?: throw NoSuchElementException(id)
        return upsert(e.copy(status = status, result = result, nextStep = nextStep, device = device ?: e.device))
    }

    fun toJson(): String = mapper.writeValueAsString(all())

    private fun validate(e: Experiment) {
        val conclusive = e.status == ExperimentStatus.CONFIRMED || e.status == ExperimentStatus.REFUTED
        if (conclusive && e.physical && e.evidence.none { it.source == EvidenceSource.DEVICE }) {
            throw ExperimentRuleViolation(
                "El experimento físico ${e.id} no puede ser ${e.status} sin evidencia capturada del dispositivo real",
            )
        }
    }

    private fun persist() {
        file?.let { it.parentFile?.mkdirs(); mapper.writeValue(it, all()) }
    }

    companion object {
        /** Los 10 experimentos obligatorios del protocolo, en estado inicial. */
        fun standardPlan(): List<Experiment> = listOf(
            Experiment("EXP-01", "Identificar dispositivo y revisión",
                "El intercomunicador se anuncia con un nombre y clase Bluetooth que permiten distinguir V6 Pro de V6 Pro+, y la etiqueta/caja indica revisión y firmware.",
                listOf("Lab → Inventario Bluetooth", "Anotar nombre, clase de dispositivo, OUI", "Fotografiar etiqueta y caja", "Conectar USB-C a PC y ejecutar lab/usb_probe.py"),
                physical = true),
            Experiment("EXP-02", "Detectar perfiles Bluetooth disponibles",
                "El dispositivo expone HFP (0x111E), A2DP Sink (0x110B) y AVRCP; posiblemente un servicio BLE propietario para SafeRiding.",
                listOf("Lab → Inventario (fetchUuidsWithSdp)", "Lab → Escaneo BLE (solo lectura)", "PC Linux: lab/bt_inventory.py y lab/ble_gatt_dump.py"),
                physical = true),
            Experiment("EXP-03", "Reproducir audio desde el teléfono",
                "TTS con USAGE_VOICE_COMMUNICATION sale por SCO (HFP) y con USAGE_ASSISTANT/MEDIA por A2DP.",
                listOf("Lab → Probar salida SCO", "Lab → Probar salida A2DP", "Registrar dispositivo de salida enrutado"),
                physical = true),
            Experiment("EXP-04", "Capturar audio desde el micrófono del intercomunicador",
                "Con setCommunicationDevice(BT SCO) AudioRecord(VOICE_COMMUNICATION) recibe audio del micrófono del casco a 16 kHz (mSBC) u 8 kHz (CVSD).",
                listOf("Lab → Grabar 5 s por SCO", "Verificar AudioRecord.getRoutedDevice()", "Comparar nivel dBFS hablando vs silencio"),
                physical = true),
            Experiment("EXP-05", "Comprobar activación mediante botón",
                "El botón de asistente del EJEAS envía AT+BVRA=1 (HFP) y Android lanza ACTION_VOICE_COMMAND hacia nuestra actividad; alternativamente envía AVRCP play/pause.",
                listOf("Seleccionar la app como manejador de comandos de voz", "Pulsar/mantener cada botón", "Lab → Registro de eventos: intents y media keys recibidos"),
                physical = true),
            Experiment("EXP-06", "Iniciar una conversación con IA",
                "Una frase dicha al casco produce una respuesta audible de Claude por los altavoces del casco.",
                listOf("Configurar API key", "Iniciar sesión (botón o teléfono)", "Hacer pregunta simple", "Guardar transcripción y latencias"),
                physical = true),
            Experiment("EXP-07", "Ejecutar una herramienta sencilla",
                "'¿Qué hora es?' ejecuta current_time y la respuesta se escucha en el casco.",
                listOf("Sesión activa", "Decir '¿qué hora es?'", "Ver evento tool:current_time en el registro"),
                physical = true),
            Experiment("EXP-08", "Guardar una idea en Control",
                "'Guarda esta idea: …' crea un registro en Control con la frase original y contexto.",
                listOf("Levantar control-bridge", "Configurar URL y token", "Dictar idea", "Verificar GET /v1/ideas"),
                physical = true),
            Experiment("EXP-09", "Medir latencia",
                "Primera respuesta audible < 2 s en consultas simples con fast path local; < 3.5 s vía nube.",
                listOf("10 repeticiones por tipo de consulta", "Exportar marcas activation→tts_start", "Calcular p50/p90"),
                physical = true),
            Experiment("EXP-10", "Reconexión, interrupciones y recuperación",
                "La sesión se suspende ante llamada o intercom entre cascos, y se recupera (o termina limpiamente) al volver el enlace.",
                listOf("Apagar/encender casco durante sesión", "Recibir llamada", "Activar intercom con otro casco", "Cortar datos móviles"),
                physical = true),
        )
    }
}
