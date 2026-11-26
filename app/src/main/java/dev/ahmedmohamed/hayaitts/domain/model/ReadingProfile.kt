package dev.ahmedmohamed.hayaitts.domain.model

import kotlinx.serialization.Serializable

/**
 * One entry in a language's priority list. [engine] is an engine id
 * (`local`, `edge`); [voiceId] is that engine's voice id, or [AUTO] for
 * "the best installed voice for this language" (local only), so newly
 * installed voices are picked up without editing the list.
 */
@Serializable
data class VoiceRoute(val engine: String, val voiceId: String) {
    companion object {
        const val ENGINE_LOCAL = "local"
        const val ENGINE_EDGE = "edge"
        const val AUTO = "auto"
        val LocalAuto = VoiceRoute(ENGINE_LOCAL, AUTO)
    }
}

/** A language the user reads, with its ordered routes. The first route that works reads the text. */
@Serializable
data class LanguageProfile(val language: String, val routes: List<VoiceRoute>)
