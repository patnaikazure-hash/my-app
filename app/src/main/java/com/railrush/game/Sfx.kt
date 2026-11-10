package com.railrush.game

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Tiny synthesizer: generates the game's sound effects as WAV files on first launch. */
class Sfx(context: Context) {

    enum class Sound { COIN, JUMP, ROLL, SWISH, STUMBLE, CRASH, POWER }

    private val rate = 22050
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val ids = IntArray(Sound.entries.size)
    var enabled = true

    init {
        val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
        for (s in Sound.entries) {
            try {
                val file = File(dir, "${s.name.lowercase()}_v1.wav")
                if (!file.exists()) writeWav(file, synth(s))
                ids[s.ordinal] = pool.load(file.absolutePath, 1)
            } catch (e: Exception) {
                ids[s.ordinal] = 0
            }
        }
    }

    fun play(s: Sound, volume: Float = 1f) {
        if (!enabled) return
        val id = ids[s.ordinal]
        if (id != 0) pool.play(id, volume, volume, 1, 0, 1f)
    }

    fun release() = pool.release()

    private fun synth(s: Sound): ShortArray = when (s) {
        Sound.COIN -> tones(floatArrayOf(1318f, 1975f), floatArrayOf(0.05f, 0.13f))
        Sound.JUMP -> sweep(320f, 760f, 0.16f, square = false)
        Sound.ROLL -> sweep(520f, 160f, 0.18f, square = false)
        Sound.SWISH -> noise(0.12f, 0.12f)
        Sound.STUMBLE -> sweep(240f, 110f, 0.22f, square = true)
        Sound.CRASH -> noise(0.5f, 0.35f)
        Sound.POWER -> tones(floatArrayOf(523f, 659f, 784f, 1047f), floatArrayOf(0.07f, 0.07f, 0.07f, 0.16f))
    }

    private inline fun render(dur: Float, gen: (t: Float) -> Float): ShortArray {
        val n = (dur * rate).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / rate
            val decay = 1f - t / dur
            val env = min(1f, t / 0.005f) * decay * decay
            out[i] = (gen(t).coerceIn(-1f, 1f) * env * 0.6f * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun sweep(f0: Float, f1: Float, dur: Float, square: Boolean): ShortArray {
        var ph = 0.0
        return render(dur) { t ->
            val freq = f0 + (f1 - f0) * t / dur
            ph += 2.0 * PI * freq / rate
            val v = sin(ph).toFloat()
            if (square) (if (v >= 0f) 0.5f else -0.5f) else v
        }
    }

    private fun tones(freqs: FloatArray, durs: FloatArray): ShortArray {
        val total = durs.sum()
        var ph = 0.0
        return render(total) { t ->
            var acc = 0f
            var idx = freqs.size - 1
            for (i in durs.indices) {
                acc += durs[i]
                if (t < acc) { idx = i; break }
            }
            ph += 2.0 * PI * freqs[idx] / rate
            (sin(ph) * 0.8 + sin(ph * 2.0) * 0.2).toFloat()
        }
    }

    private fun noise(dur: Float, smooth: Float): ShortArray {
        val r = Random(42)
        var y = 0f
        return render(dur) { _ ->
            val x = r.nextFloat() * 2f - 1f
            y += smooth * (x - y)
            y * 2.2f
        }
    }

    private fun writeWav(file: File, pcm: ShortArray) {
        val dataLen = pcm.size * 2
        val buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataLen)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1) // PCM
        buf.putShort(1) // mono
        buf.putInt(rate)
        buf.putInt(rate * 2)
        buf.putShort(2)
        buf.putShort(16)
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataLen)
        for (s in pcm) buf.putShort(s)
        file.writeBytes(buf.array())
    }
}
