package dev.ahmedmohamed.hayaitts.domain.model

/**
 * Language matching for the catalog, which mixes `en`, `en-US`, `en_GB`,
 * `eng`-style tags. Users pick languages ("English", "German"), not tags,
 * so everything here compares base languages.
 */
object LanguageTags {

    /** `en-US` / `en_GB` / `EN` → `en`. */
    fun base(tag: String): String = tag.trim().replace('_', '-').substringBefore('-').lowercase()

    /**
     * Whether a voice belongs to the user's languages. Its main (first)
     * language must be one of them, so a Persian/English or Chinese/English
     * bilingual model doesn't show up for an English reader. Broadly
     * multilingual models (3+ languages, e.g. Supertonic) count if they
     * speak any of them.
     */
    fun voiceIn(voiceLanguages: List<String>, allowed: Set<String>): Boolean {
        if (allowed.isEmpty()) return true
        val bases = allowed.mapTo(mutableSetOf(), ::base)
        val voice = voiceLanguages.map(::base).distinct()
        if (voice.isEmpty()) return false
        return voice.first() in bases || (voice.size >= MULTILINGUAL && voice.any { it in bases })
    }

    /** True if any of the voice's languages has a base in [selected] (explicit filter chips). */
    fun speaksAny(voiceLanguages: List<String>, selected: Set<String>): Boolean {
        val bases = selected.mapTo(mutableSetOf(), ::base)
        return voiceLanguages.any { base(it) in bases }
    }

    private const val MULTILINGUAL = 3
}
