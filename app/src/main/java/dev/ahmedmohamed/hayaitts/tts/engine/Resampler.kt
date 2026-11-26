package dev.ahmedmohamed.hayaitts.tts.engine

/**
 * Linear resampler. The framework fixes one sample rate per utterance at
 * `SynthesisCallback.start()`, but a fallback engine may produce another
 * rate (Edge 24 kHz, Piper 16/22.05 kHz), so later sections are converted.
 */
object Resampler {
    fun resample(samples: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || samples.isEmpty()) return samples
        val outLen = ((samples.size.toLong() * to) / from).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        val step = from.toDouble() / to
        for (i in 0 until outLen) {
            val pos = i * step
            val idx = pos.toInt()
            val frac = (pos - idx).toFloat()
            val a = samples[idx.coerceAtMost(samples.size - 1)]
            val b = samples[(idx + 1).coerceAtMost(samples.size - 1)]
            out[i] = a + (b - a) * frac
        }
        return out
    }
}
