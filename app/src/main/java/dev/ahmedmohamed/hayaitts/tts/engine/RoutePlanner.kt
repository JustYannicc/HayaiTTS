package dev.ahmedmohamed.hayaitts.tts.engine

import dev.ahmedmohamed.hayaitts.domain.model.LanguageProfile
import dev.ahmedmohamed.hayaitts.domain.model.VoiceRoute
import dev.ahmedmohamed.hayaitts.tts.TtsLocales

/**
 * Turns the user's language profiles into concrete route lists for one
 * section: resolves `local/auto` to an installed voice, drops online engines
 * when they're switched off, and always ends with a local voice so something
 * speaks when the network is gone.
 */
class RoutePlanner(
    val profiles: List<LanguageProfile>,
    private val onlineEnabled: Boolean,
    /** Best installed local voice id for a BCP-47 tag, or null. */
    private val resolveLocalAuto: (String) -> String?,
) {
    val languages: List<String> get() = profiles.map { it.language }

    /** A planner lives for one request; resolve `auto` once per language, not per section. */
    private val autoCache = HashMap<String, String?>()

    /** Profile whose language best matches [bcp47]: exact region first, then same base language. */
    fun profileFor(bcp47: String): LanguageProfile? {
        if (bcp47.isBlank()) return null
        return profiles
            .map { it to TtsLocales.matchLevel(listOf(it.language), bcp47) }
            .filter { it.second > TtsLocales.NOT_SUPPORTED }
            .maxByOrNull { it.second }
            ?.first
    }

    /**
     * Routes for a section in [language]. [explicit] (a voice the caller
     * picked) goes first; the language's profile follows as fallback.
     */
    fun routesFor(language: String, explicit: VoiceRoute? = null): List<VoiceRoute> {
        val raw = listOfNotNull(explicit) + (profileFor(language)?.routes ?: emptyList()) + VoiceRoute.LocalAuto
        return raw
            .filter { onlineEnabled || it.engine != VoiceRoute.ENGINE_EDGE }
            .mapNotNull { route ->
                if (route.engine == VoiceRoute.ENGINE_LOCAL && route.voiceId == VoiceRoute.AUTO) {
                    autoCache.getOrPut(language) { resolveLocalAuto(language) }?.let { VoiceRoute(VoiceRoute.ENGINE_LOCAL, it) }
                } else route
            }
            .distinct()
    }
}
