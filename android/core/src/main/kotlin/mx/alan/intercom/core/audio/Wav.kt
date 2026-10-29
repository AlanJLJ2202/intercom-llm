package mx.alan.intercom.core.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Codifica PCM 16-bit mono a WAV (para STT en la nube y evidencia del laboratorio). */
object Wav {
    fun encode(pcm: ShortArray, sampleRate: Int): ByteArray {
        val dataLen = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + dataLen); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(sampleRate); bb.putInt(sampleRate * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(dataLen)
        for (s in pcm) bb.putShort(s)
        return bb.array()
    }

    /** Estadísticos simples para la evidencia de Exp. 4 (¿llega audio real del casco?). */
    fun stats(pcm: ShortArray, sampleRate: Int): AudioStats {
        var peak = 0
        var clipped = 0
        for (s in pcm) {
            val a = kotlin.math.abs(s.toInt())
            if (a > peak) peak = a
            if (a >= 32_700) clipped++
        }
        return AudioStats(
            durationMs = pcm.size * 1000L / sampleRate,
            rmsDbfs = EnergyVad.dbfs(pcm),
            peak = peak,
            clippedRatio = if (pcm.isEmpty()) 0.0 else clipped.toDouble() / pcm.size,
        )
    }

    fun concat(parts: List<ShortArray>): ShortArray {
        return ShortArray(parts.sumOf { it.size }).also { dst ->
            var o = 0
            parts.forEach { it.copyInto(dst, o); o += it.size }
        }
    }
}

data class AudioStats(val durationMs: Long, val rmsDbfs: Double, val peak: Int, val clippedRatio: Double)
