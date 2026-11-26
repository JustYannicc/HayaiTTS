package dev.ahmedmohamed.hayaitts.tts.engine

/**
 * One way of turning text into PCM. HayaiTTS routes each section of a
 * request through a per-language priority list of (engine, voice) routes;
 * the first route that works reads the section.
 */
interface SpeechEngine {
    val kind: EngineKind

    /** Voices this engine can speak with right now. Must be cheap (cached); never blocks on network. */
    fun voices(): List<EngineVoice>

    /**
     * Synthesizes [text] with [voiceId]. Blocking; called off the main thread.
     *
     * [language] is BCP-47 (`de-DE`). [speed] and [pitch] are multipliers
     * (1.0 = normal). [isCancelled] is polled so long work can stop early.
     *
     * Throws [EngineUnavailable] when the engine as a whole can't work right
     * now (offline, rejected handshake, timeout); the router benches it.
     * Throws [EngineFailure] when only this voice/text failed; the router
     * tries the next route without benching.
     */
    fun synthesize(
        voiceId: String,
        text: String,
        language: String,
        speed: Float,
        pitch: Float,
        isCancelled: () -> Boolean,
    ): EngineAudio

    /** Optional: open connections / load models ahead of the first request. */
    fun prewarm() {}
}

enum class EngineKind(val id: String) {
    LOCAL("local"),
    EDGE("edge");

    companion object {
        fun fromId(id: String): EngineKind? = entries.firstOrNull { it.id == id }
    }
}

data class EngineVoice(
    val engine: EngineKind,
    /** Stable id the engine accepts in [SpeechEngine.synthesize]. */
    val id: String,
    /** Human-readable, e.g. `Katja · Female`. */
    val displayName: String,
    /** BCP-47, e.g. `de-DE`. */
    val locale: String,
    val requiresNetwork: Boolean,
)

/** Mono float PCM in -1..1. */
class EngineAudio(val samples: FloatArray, val sampleRate: Int)

class EngineUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

class EngineFailure(message: String, cause: Throwable? = null) : Exception(message, cause)
