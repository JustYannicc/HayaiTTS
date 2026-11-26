package dev.ahmedmohamed.hayaitts.tts.engine

import dev.ahmedmohamed.hayaitts.data.playground.PitchResampler

/**
 * Sample-rate conversion. The framework fixes one sample rate per utterance
 * at `SynthesisCallback.start()`, but a fallback engine may produce another
 * rate (Edge 24 kHz, Piper 16/22.05 kHz), so later sections are converted.
 */
object Resampler {
    fun resample(samples: FloatArray, from: Int, to: Int): FloatArray =
        if (from == to) samples
        else PitchResampler.byRatio(samples, from.toDouble() / to, (samples.size.toLong() * to / from).toInt())
}
