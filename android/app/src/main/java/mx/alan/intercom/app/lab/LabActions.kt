package mx.alan.intercom.app.lab

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import mx.alan.intercom.app.AppGraph
import mx.alan.intercom.app.OutputRoute
import mx.alan.intercom.app.RouteStrategy
import mx.alan.intercom.app.audio.BluetoothAudioRouter
import mx.alan.intercom.app.audio.PcmCapture
import mx.alan.intercom.app.audio.SpeechOutput
import mx.alan.intercom.app.bt.BleProbe
import mx.alan.intercom.app.bt.BluetoothInventory
import mx.alan.intercom.app.bt.HeadsetProfile
import mx.alan.intercom.core.audio.Wav
import mx.alan.intercom.core.lab.Evidence
import mx.alan.intercom.core.lab.EvidenceSource
import mx.alan.intercom.core.latency.LatencyTracker
import mx.alan.intercom.core.llm.ChatMessage
import mx.alan.intercom.core.llm.ChatRequest
import java.io.File
import java.time.OffsetDateTime
import java.util.concurrent.Executors

/**
 * Experimentos ejecutables desde la pantalla de laboratorio. Cada acción
 * registra eventos y agrega evidencia (fuente DEVICE) al experimento
 * correspondiente; la CONCLUSIÓN (confirmado/refutado) la marca el usuario,
 * porque solo él puede afirmar, p. ej., que oyó el audio en el casco.
 */
class LabActions(private val ctx: Context) {
    private val log get() = AppGraph.events
    private val io = Executors.newSingleThreadExecutor()
    private val headset = HeadsetProfile(ctx)
    private val inventory = BluetoothInventory(ctx, headset)
    private val ble = BleProbe(ctx, AppGraph.events)
    private val speech by lazy { SpeechOutput(ctx, AppGraph.events) }

    private fun now() = OffsetDateTime.now().toString()

    private fun evidence(exp: String, desc: String, data: Map<String, Any?>, artifact: String? = null) {
        try {
            AppGraph.experiments.addEvidence(exp, Evidence(EvidenceSource.DEVICE, desc, now(), artifact, data))
        } catch (e: Exception) {
            log.log("lab", "No se pudo registrar evidencia: ${e.message}")
        }
    }

    /** EXP-01 + EXP-02 */
    fun runInventory(done: (Map<String, Any?>) -> Unit) = io.execute {
        val report = inventory.collect(refreshSdp = true)
        log.log("inventory", "Inventario Bluetooth", report)
        @Suppress("UNCHECKED_CAST")
        val candidates = (report["bonded"] as? List<Map<String, Any?>>).orEmpty().filter { it["candidate"] == true }
        evidence("EXP-01", "Inventario: ${candidates.size} candidatos a intercomunicador", mapOf("candidates" to candidates, "platform" to report["platform"]))
        evidence("EXP-02", "UUIDs SDP de candidatos", mapOf("uuids" to candidates.map { it["name"] to it["uuids"] }))
        done(report)
    }

    /** EXP-02 (BLE) */
    fun runBleScan(done: (Int) -> Unit) = ble.scan { results ->
        val summary = results.map { ble.describe(it) }
        evidence("EXP-02", "Escaneo BLE: ${results.size} anuncios", mapOf("results" to summary.filter { it["candidate"] == true }.ifEmpty { summary.take(10) }))
        val candidate = results.firstOrNull { BluetoothInventory.looksLikeIntercom(it.device.name ?: it.scanRecord?.deviceName) }
        if (candidate != null) {
            ble.dumpGatt(candidate.device) { evidence("EXP-02", "Volcado GATT (solo lectura)", it) }
        } else {
            log.log("ble", "Ningún anuncio BLE parece del intercomunicador; no se conecta GATT")
        }
        done(results.size)
    }

    /** EXP-03: salida de audio por SCO o A2DP. */
    fun testOutput(route: OutputRoute, done: () -> Unit) = io.execute {
        val router = BluetoothAudioRouter(ctx, headset, log)
        speech.awaitReady()
        val t = LatencyTracker().also { it.start() }
        val routeRes = if (route == OutputRoute.SCO) router.acquire(AppGraph.settings.routeStrategy) else null
        speech.speakBlocking("Prueba de audio por ${if (route == OutputRoute.SCO) "manos libres" else "música estéreo"}. Uno, dos, tres.", route,
            onStart = { t.mark("tts_start") })
        if (routeRes != null) router.release()
        evidence("EXP-03", "Salida $route reproducida (confirmar en la app si se oyó en el casco)", mapOf(
            "route" to route.name, "routeOk" to routeRes?.ok, "routeDetail" to routeRes?.detail, "latency" to t.snapshot(),
        ))
        done()
    }

    /** EXP-04: graba 5 s por la ruta de voz y los reproduce de vuelta (loopback). */
    fun recordLoopback(strategy: RouteStrategy, seconds: Int = 5, done: (String) -> Unit) = io.execute {
        val router = BluetoothAudioRouter(ctx, headset, log)
        val route = router.acquire(strategy)
        val capture = PcmCapture(log)
        val frames = mutableListOf<ShortArray>()
        speech.awaitReady()
        speech.speakBlocking("Habla después del tono durante $seconds segundos.", OutputRoute.SCO)
        speech.earcon(true, OutputRoute.SCO)
        capture.start(router.headsetInputDevice()) { synchronized(frames) { frames += it } }
        Thread.sleep(seconds * 1000L)
        capture.stop()
        val rate = capture.sampleRate
        val pcm = synchronized(frames) { Wav.concat(frames) }
        val stats = Wav.stats(pcm, rate)
        val file = File(ctx.filesDir, "lab/loopback_${strategy.name.lowercase()}.wav").apply { parentFile?.mkdirs(); writeBytes(Wav.encode(pcm, rate)) }
        speech.speakBlocking("Reproduciendo grabación.", OutputRoute.SCO)
        play(pcm, rate)
        router.release()
        val summary = "rate=$rate Hz, ${stats.durationMs} ms, RMS ${"%.1f".format(stats.rmsDbfs)} dBFS, ruta=${capture.routedDevice?.get("type")}"
        evidence("EXP-04", "Grabación loopback ($strategy): $summary", mapOf(
            "strategy" to strategy.name, "routeOk" to route.ok, "routeDetail" to route.detail,
            "routedDevice" to capture.routedDevice, "stats" to stats,
        ), artifact = file.absolutePath)
        done(summary)
    }

    /** EXP-06 parcial: prueba de conectividad con el proveedor de IA (texto). */
    fun pingModel(done: (String) -> Unit) = io.execute {
        val model = AppGraph.cloudModel() ?: return@execute done("Falta API key de Anthropic")
        val t0 = System.nanoTime()
        val res = try {
            model.respond(ChatRequest("Responde en una frase corta en español.", listOf(ChatMessage.User("Di hola y la palabra listo.")), emptyList(), 100)).text
        } catch (e: Exception) { "Error: ${e.message}" }
        val ms = (System.nanoTime() - t0) / 1_000_000
        log.log("llm", "Ping ${model.id}: $res", mapOf("ms" to ms))
        done("$res ($ms ms)")
    }

    private fun play(pcm: ShortArray, rate: Int) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(maxOf(pcm.size * 2, 2))
            .build()
        track.write(pcm, 0, pcm.size)
        track.play()
        Thread.sleep(pcm.size * 1000L / rate + 200)
        track.release()
    }
}
