package mx.alan.intercom.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import mx.alan.intercom.app.AppGraph
import mx.alan.intercom.app.EventLog
import mx.alan.intercom.app.OutputRoute
import mx.alan.intercom.app.RouteStrategy
import mx.alan.intercom.app.SttEngine
import mx.alan.intercom.app.bt.Permissions
import mx.alan.intercom.app.lab.LabActions
import mx.alan.intercom.app.session.VoiceSessionService
import mx.alan.intercom.core.lab.DeviceContext
import mx.alan.intercom.core.lab.ExperimentStatus
import mx.alan.intercom.core.policy.RideMode

/**
 * Pantalla única: laboratorio de diagnóstico + control de sesión + ajustes.
 * Construida en código (sin XML ni AndroidX) para minimizar dependencias.
 * Pensada para usarse DETENIDO; en ruta se opera por voz y botones del casco.
 */
class MainActivity : Activity() {
    private lateinit var lab: LabActions
    private lateinit var status: TextView
    private lateinit var events: TextView
    private lateinit var experiments: TextView
    private val listener: (EventLog.Event) -> Unit = { renderEvents() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(this)
        lab = LabActions(this)
        setContentView(buildUi())
        requestMissingPermissions()
    }

    override fun onResume() {
        super.onResume()
        AppGraph.events.addListener(listener)
        renderAll()
    }

    override fun onPause() {
        AppGraph.events.removeListener(listener)
        super.onPause()
    }

    private fun requestMissingPermissions() {
        val missing = Permissions.missing(this)
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 48) }
        fun header(t: String) = root.addView(TextView(this).apply { text = t; textSize = 18f; setTypeface(null, Typeface.BOLD); setPadding(0, 32, 0, 8) })
        fun button(t: String, onClick: () -> Unit) = root.addView(Button(this).apply { text = t; isAllCaps = false; setOnClickListener { onClick() } })
        fun field(hint: String, value: String, secret: Boolean, save: (String) -> Unit) = root.addView(EditText(this).apply {
            this.hint = hint
            setText(value)
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setOnFocusChangeListener { _, has -> if (!has) save(text.toString()) }
        })

        status = TextView(this).apply { textSize = 14f }
        root.addView(status)

        header("Sesión de voz")
        button("Armar modo ruta (servicio en primer plano)") { startForegroundService(VoiceSessionService.intent(this, VoiceSessionService.ACTION_ARM)) }
        button("Iniciar sesión ahora (Estrategia C)") { startForegroundService(VoiceSessionService.intent(this, VoiceSessionService.ACTION_START, "ui")) }
        button("Detener sesión") { startService(VoiceSessionService.intent(this, VoiceSessionService.ACTION_STOP)) }
        button("Desarmar") { startService(VoiceSessionService.intent(this, VoiceSessionService.ACTION_DISARM)) }
        button("Alternar modo DETENIDO / CONDUCCIÓN") {
            AppGraph.settings.mode = if (AppGraph.mode() == RideMode.STOPPED) RideMode.DRIVING else RideMode.STOPPED
            AppGraph.events.log("mode", "Modo manual: ${AppGraph.mode()}")
            renderAll()
        }

        header("Laboratorio EJEAS")
        button("EXP-01/02 · Inventario Bluetooth (SDP)") { lab.runInventory { runOnUiThread { renderAll() } } }
        button("EXP-02 · Escaneo BLE + GATT solo lectura") { lab.runBleScan { runOnUiThread { renderAll() } } }
        button("EXP-03 · Probar salida por SCO (HFP)") { lab.testOutput(OutputRoute.SCO) { runOnUiThread { renderAll() } } }
        button("EXP-03 · Probar salida por A2DP") { lab.testOutput(OutputRoute.A2DP) { runOnUiThread { renderAll() } } }
        button("EXP-04 · Grabar 5 s y reproducir (estrategia actual)") { lab.recordLoopback(AppGraph.settings.routeStrategy) { runOnUiThread { toastStatus(it) } } }
        button("EXP-05 · Activar/desactivar botones multimedia como PTT") {
            AppGraph.settings.mediaButtonTrigger = !AppGraph.settings.mediaButtonTrigger
            startForegroundService(VoiceSessionService.intent(this, VoiceSessionService.ACTION_ARM))
            renderAll()
        }
        button("EXP-05 · Elegir app de asistente / comandos de voz") {
            val i = Intent(if (Build.VERSION.SDK_INT >= 29) android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS else android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)
            startActivity(i)
        }
        button("EXP-06 · Ping al modelo (texto)") { lab.pingModel { runOnUiThread { toastStatus(it) } } }
        button("Concluir un experimento…") { concludeDialog() }
        button("Exportar evidencia (compartir JSON)") { export() }

        header("Ajustes")
        field("Anthropic API key", AppGraph.settings.anthropicKey, true) { AppGraph.settings.anthropicKey = it }
        field("Modelo Claude", AppGraph.settings.anthropicModel, false) { AppGraph.settings.anthropicModel = it }
        field("OpenAI API key (solo STT en la nube, opcional)", AppGraph.settings.openAiKey, true) { AppGraph.settings.openAiKey = it }
        field("URL de Control (https://…)", AppGraph.settings.controlUrl, false) { AppGraph.settings.controlUrl = it; AppGraph.resetControl() }
        field("Token de Control", AppGraph.settings.controlToken, true) { AppGraph.settings.controlToken = it; AppGraph.resetControl() }
        button("STT: alternar SISTEMA / NUBE") {
            AppGraph.settings.sttEngine = if (AppGraph.settings.sttEngine == SttEngine.SYSTEM) SttEngine.CLOUD else SttEngine.SYSTEM; renderAll()
        }
        button("Salida: alternar SCO / A2DP") {
            AppGraph.settings.outputRoute = if (AppGraph.settings.outputRoute == OutputRoute.SCO) OutputRoute.A2DP else OutputRoute.SCO; renderAll()
        }
        button("Ruta: siguiente estrategia (comm device / voice recognition / ambas)") {
            val all = RouteStrategy.entries
            AppGraph.settings.routeStrategy = all[(all.indexOf(AppGraph.settings.routeStrategy) + 1) % all.size]; renderAll()
        }
        button("Fast path local: activar/desactivar") { AppGraph.settings.useLocalFastPath = !AppGraph.settings.useLocalFastPath; renderAll() }

        header("Experimentos")
        experiments = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        root.addView(experiments)

        header("Eventos (más recientes primero)")
        events = TextView(this).apply { textSize = 11f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        root.addView(events)

        return ScrollView(this).apply { addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
    }

    private fun renderAll() {
        val s = AppGraph.settings
        status.text = buildString {
            appendLine("Modo: ${s.mode}   ·   STT: ${s.sttEngine}   ·   Salida: ${s.outputRoute}")
            appendLine("Ruta: ${s.routeStrategy}   ·   Fast path: ${s.useLocalFastPath}   ·   Botones PTT: ${s.mediaButtonTrigger}")
            appendLine("IA: ${if (s.anthropicKey.isBlank()) "sin configurar (solo capturas locales)" else s.anthropicModel}")
            appendLine("Control: ${if (s.controlUrl.isBlank()) "demo en memoria" else s.controlUrl}")
            appendLine("Pendientes aplazados: ${AppGraph.deferred.pending().size}")
            val missing = Permissions.missing(this@MainActivity)
            if (missing.isNotEmpty()) appendLine("Permisos faltantes: ${missing.joinToString { it.substringAfterLast('.') }}")
        }
        experiments.text = AppGraph.experiments.all().joinToString("\n\n") { e ->
            "${e.id} [${e.status}] ${e.title}\n  H: ${e.hypothesis}\n  Evidencias: ${e.evidence.size}${e.result?.let { "\n  Resultado: $it" } ?: ""}${e.nextStep?.let { "\n  Siguiente: $it" } ?: ""}"
        }
        renderEvents()
    }

    private fun renderEvents() {
        events.text = AppGraph.events.recent(60).reversed().joinToString("\n") { "${it.ts.substring(11, 19)} [${it.category}] ${it.message}" }
    }

    private fun toastStatus(msg: String) {
        AppGraph.events.log("lab", msg)
        renderAll()
    }

    /** El usuario concluye: solo él puede afirmar lo que oyó/vio en el casco. */
    private fun concludeDialog() {
        val list = AppGraph.experiments.all()
        AlertDialog.Builder(this).setTitle("Experimento").setItems(list.map { "${it.id} [${it.status}]" }.toTypedArray()) { _, idx ->
            val exp = list[idx]
            val statuses = ExperimentStatus.entries
            AlertDialog.Builder(this).setTitle("${exp.id}: resultado").setItems(statuses.map { it.name }.toTypedArray()) { _, sIdx ->
                val input = EditText(this).apply { hint = "Resultado observado y siguiente paso (separados por |)" }
                AlertDialog.Builder(this).setTitle("Describe el resultado").setView(input).setPositiveButton("Guardar") { _, _ ->
                    val parts = input.text.toString().split('|')
                    try {
                        AppGraph.experiments.conclude(exp.id, statuses[sIdx], parts[0].trim(), parts.getOrNull(1)?.trim(),
                            DeviceContext(model = null, firmware = null, phone = "${Build.MANUFACTURER} ${Build.MODEL}", os = "Android ${Build.VERSION.RELEASE}"))
                    } catch (e: Exception) {
                        AppGraph.events.log("lab", "No se pudo concluir: ${e.message}")
                    }
                    renderAll()
                }.show()
            }.show()
        }.show()
    }

    private fun export() {
        val payload = "{\"experiments\":${AppGraph.experiments.toJson()},\n\"events\":[${AppGraph.events.exportJsonl().trim().lines().filter { it.isNotBlank() }.joinToString(",")}]}"
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "Evidencia EJEAS lab")
            putExtra(Intent.EXTRA_TEXT, payload)
        }, "Exportar evidencia"))
    }
}
