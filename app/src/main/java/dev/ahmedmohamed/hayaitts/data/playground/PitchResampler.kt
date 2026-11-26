package dev.ahmedmohamed.hayaitts.data.playground

import kotlin.math.max
import kotlin.math.min

/**
 * Linear-interpolation resampler. Compresses/expands the source FloatArray to
 * produce a pitch effect (with a tempo side-effect). No DSP dependency.
 */
object PitchResampler {
    fun resample(samples: FloatArray, pitch: Float): FloatArray {
        val safePitch = pitch.coerceIn(0.25f, 4f)
        if (kotlin.math.abs(safePitch - 1f) < 0.001f) return samples
        return byRatio(samples, safePitch.toDouble())
    }

    /** Reads the source at [ratio] source samples per output sample (also used for rate conversion). */
    fun byRatio(samples: FloatArray, ratio: Double, outLength: Int = (samples.size / ratio).toInt()): FloatArray {
        if (samples.isEmpty()) return samples
        val srcLen = samples.size
        val outLen = max(1, outLength)
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val srcFloor = min(srcPos.toInt(), srcLen - 1)
            val srcCeil = min(srcFloor + 1, srcLen - 1)
            val frac = (srcPos - srcFloor).toFloat()
            val a = samples[srcFloor]
            val b = samples[srcCeil]
            out[i] = a + (b - a) * frac
        }
        return out
    }
}
