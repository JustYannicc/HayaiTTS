package dev.ahmedmohamed.hayaitts.domain.model

/**
 * Pure edits on the user's language profiles, as the Reading settings
 * screen applies them. Every edit returns a new list; an edit that doesn't
 * apply (unknown language, out-of-range index, removing a language's last
 * route) returns the input unchanged so callers never have to pre-check.
 */
object ReadingProfileEdits {

    /**
     * Makes the profile list cover exactly [allowedBases] (base languages like
     * `de`): keeps existing profiles (and their order and routes) for allowed
     * languages, drops the others, and appends a profile for each newly
     * allowed language from [newProfile].
     */
    fun reconcile(
        profiles: List<LanguageProfile>,
        allowedBases: Set<String>,
        newProfile: (base: String) -> LanguageProfile,
    ): List<LanguageProfile> {
        val kept = profiles.filter { LanguageTags.base(it.language) in allowedBases }
        val have = kept.mapTo(mutableSetOf()) { LanguageTags.base(it.language) }
        return kept + allowedBases.filter { it !in have }.sorted().map(newProfile)
    }

    fun addLanguage(profiles: List<LanguageProfile>, language: String, routes: List<VoiceRoute>): List<LanguageProfile> {
        if (language.isBlank() || profiles.any { it.language.equals(language, ignoreCase = true) }) return profiles
        return profiles + LanguageProfile(language, routes.distinct().ifEmpty { listOf(VoiceRoute.LocalAuto) })
    }

    fun removeLanguage(profiles: List<LanguageProfile>, language: String): List<LanguageProfile> =
        profiles.filterNot { it.language == language }

    /** Moves [language] by [delta] places (-1 up, +1 down), clamped to the list. */
    fun moveLanguage(profiles: List<LanguageProfile>, language: String, delta: Int): List<LanguageProfile> {
        val from = profiles.indexOfFirst { it.language == language }
        return if (from < 0) profiles else profiles.move(from, from + delta)
    }

    /** Appends [route] to [language]'s list unless it's already there. */
    fun addRoute(profiles: List<LanguageProfile>, language: String, route: VoiceRoute): List<LanguageProfile> =
        profiles.editRoutes(language) { routes -> if (route in routes) routes else routes + route }

    /** Removes the route at [index]; a language always keeps at least one route. */
    fun removeRoute(profiles: List<LanguageProfile>, language: String, index: Int): List<LanguageProfile> =
        profiles.editRoutes(language) { routes ->
            if (routes.size <= 1 || index !in routes.indices) routes else routes.filterIndexed { i, _ -> i != index }
        }

    /** Moves the route at [index] by [delta] places (-1 up, +1 down), clamped to the list. */
    fun moveRoute(profiles: List<LanguageProfile>, language: String, index: Int, delta: Int): List<LanguageProfile> =
        profiles.editRoutes(language) { routes -> if (index !in routes.indices) routes else routes.move(index, index + delta) }

    private fun List<LanguageProfile>.editRoutes(
        language: String,
        edit: (List<VoiceRoute>) -> List<VoiceRoute>,
    ): List<LanguageProfile> = map { if (it.language == language) it.copy(routes = edit(it.routes)) else it }

    private fun <T> List<T>.move(from: Int, to: Int): List<T> {
        val target = to.coerceIn(0, lastIndex)
        if (target == from) return this
        return toMutableList().apply { add(target, removeAt(from)) }
    }
}
