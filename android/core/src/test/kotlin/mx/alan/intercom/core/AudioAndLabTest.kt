package mx.alan.intercom.core

import mx.alan.intercom.core.audio.EnergyVad
import mx.alan.intercom.core.audio.UtteranceCollector
import mx.alan.intercom.core.audio.VadConfig
import mx.alan.intercom.core.audio.VadEvent
import mx.alan.intercom.core.audio.Wav
import mx.alan.intercom.core.lab.BluetoothUuidCatalog
import mx.alan.intercom.core.lab.Evidence
import mx.alan.intercom.core.lab.EvidenceSource
import mx.alan.intercom.core.lab.ExperimentLog
import mx.alan.intercom.core.lab.ExperimentRuleViolation
import mx.alan.intercom.core.lab.ExperimentStatus
import mx.alan.intercom.core.latency.LatencyTracker
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AudioAndLabTest {
    private val rate = 16_000
    private val frame = rate * 20 / 1000
    private val rnd = Random(42)

    private fun noise(amp: Int) = ShortArray(frame) { (rnd.nextInt(-amp, amp + 1)).toShort() }
    private fun voice(amp: Int, n: Int) = ShortArray(frame) { i ->
        (amp * sin(2 * PI * 220 * (n * frame + i) / rate) + rnd.nextInt(-200, 200)).toInt().toShort()
    }

    @Test
    fun vadDetectsSpeechOverWindNoiseAndEndpoints() {
        val vad = EnergyVad(VadConfig(sampleRate = rate, endSilenceMs = 400))
        val events = mutableListOf<VadEvent>()
        repeat(50) { events += vad.process(noise(600)) }        // 1 s de "viento"
        repeat(40) { events += vad.process(voice(8000, it)) }   // 0.8 s de voz
        repeat(30) { events += vad.process(noise(600)) }        // silencio
        assertEquals(1, events.count { it == VadEvent.SPEECH_START })
        assertEquals(1, events.count { it == VadEvent.SPEECH_END })
        assertTrue(events.indexOf(VadEvent.SPEECH_START) in 50..55)
    }

    @Test
    fun shortClickDoesNotTriggerSpeech() {
        val vad = EnergyVad(VadConfig(sampleRate = rate))
        repeat(30) { vad.process(noise(300)) }
        assertEquals(VadEvent.SILENCE, vad.process(voice(12000, 0)))
        assertEquals(VadEvent.SILENCE, vad.process(noise(300)))
    }

    @Test
    fun collectorReturnsUtteranceWithPreRoll() {
        val c = UtteranceCollector(EnergyVad(VadConfig(sampleRate = rate, endSilenceMs = 300)), rate, preRollMs = 200)
        repeat(20) { c.push(noise(300)) }
        var completed: UtteranceCollector.Result.Completed? = null
        for (i in 0 until 50) c.push(voice(9000, i))
        for (i in 0 until 30) { val r = c.push(noise(300)); if (r is UtteranceCollector.Result.Completed) completed = r }
        val pcm = completed!!.pcm
        assertTrue(pcm.size >= 50 * frame, "incluye toda la voz")
        assertTrue(Wav.stats(pcm, rate).rmsDbfs > -20)
    }

    @Test
    fun collectorTimesOutWithoutSpeech() {
        val c = UtteranceCollector(EnergyVad(VadConfig(sampleRate = rate)), rate, noSpeechTimeoutMs = 1000)
        val results = (0 until 60).map { c.push(noise(200)) }
        assertIs<UtteranceCollector.Result.NoSpeech>(results.first { it is UtteranceCollector.Result.NoSpeech })
    }

    @Test
    fun wavHeader() {
        val wav = Wav.encode(ShortArray(160), 8000)
        assertEquals(44 + 320, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
    }

    @Test
    fun uuidCatalog() {
        assertEquals("HFP Hands-Free", BluetoothUuidCatalog.describe("0000111e-0000-1000-8000-00805f9b34fb").name)
        assertTrue(BluetoothUuidCatalog.describe("0000111E-0000-1000-8000-00805F9B34FB").audioCritical)
        val custom = BluetoothUuidCatalog.describe("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        assertTrue(!custom.standard && custom.shortId == null)
    }

    @Test
    fun physicalExperimentCannotBeConfirmedWithoutDeviceEvidence() {
        val log = ExperimentLog()
        ExperimentLog.standardPlan().forEach { log.upsert(it) }
        log.addEvidence("EXP-02", Evidence(EvidenceSource.DOCUMENTATION, "Ficha: Bluetooth 5.1", "2026-10-29"))
        assertFailsWith<ExperimentRuleViolation> {
            log.conclude("EXP-02", ExperimentStatus.CONFIRMED, "HFP presente", null)
        }
        log.addEvidence("EXP-02", Evidence(EvidenceSource.DEVICE, "fetchUuidsWithSdp: 0x111E", "2026-10-29"))
        assertEquals(ExperimentStatus.CONFIRMED, log.conclude("EXP-02", ExperimentStatus.CONFIRMED, "HFP presente", null).status)
    }

    @Test
    fun latencyTrackerWithFakeClock() {
        var t = 0L
        val lt = LatencyTracker { t }
        lt.start()
        t = 350_000_000; lt.mark("speech_end")
        t = 1_450_000_000; lt.mark("tts_start")
        assertEquals(1100, lt.between("speech_end", "tts_start"))
        assertEquals(1450, lt.snapshot()["tts_start"])
    }
}
