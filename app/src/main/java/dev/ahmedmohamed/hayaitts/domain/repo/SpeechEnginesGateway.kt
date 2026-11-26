package dev.ahmedmohamed.hayaitts.domain.repo

import dev.ahmedmohamed.hayaitts.core.result.Outcome
import dev.ahmedmohamed.hayaitts.domain.model.LanguageProfile
import dev.ahmedmohamed.hayaitts.domain.model.VoiceRoute

/**
 * What the Reading settings screen needs from the speech engines: the voices
 * each engine offers, online health, and a way to hear a language's routes.
 * Engines are process singletons, so this is a thin adapter, not a store.
 */
interface SpeechEnginesGateway {
    /** True when an online engine (Edge) is compiled in. Builds the engines on first use, so it's off the main thread. */
    suspend fun onlineAvailable(): Boolean

    /** Every voice the engines can speak with now. Cheap; never waits on the network. */
    suspend fun voices(): List<RouteVoice>

    /** Routes a newly added language starts with: the online default (if any), then the best installed voice. */
    suspend fun defaultRoutesFor(language: String): List<VoiceRoute>

    /** True while the online engine is benched after failing to connect. */
    fun isOnlineBenched(): Boolean

    /** Clears every bench so the next request tries the online engine again. */
    fun resetHealth()

    /** Synthesizes a short sample through [profile]'s resolved routes. */
    suspend fun synthesizeSample(profile: LanguageProfile): Outcome<SpokenSample>

    /** Plays [sample]; returns when playback ends or is stopped. */
    suspend fun play(sample: SpokenSample)

    fun stopPlayback()
}

/** One pickable voice. [languages] are BCP-47 tags the voice can read. */
data class RouteVoice(val route: VoiceRoute, val displayName: String, val languages: List<String>)

/** Mono float PCM plus which route actually produced it. */
class SpokenSample(val route: VoiceRoute, val voiceName: String, val samples: FloatArray, val sampleRate: Int)
