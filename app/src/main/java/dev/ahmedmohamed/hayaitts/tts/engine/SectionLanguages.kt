package dev.ahmedmohamed.hayaitts.tts.engine

/**
 * Assigns one of the user's languages to each section (closed-world, after
 * Narrateify's ReadingPlanner): with one language there's no detection;
 * otherwise a section of [minDetectChars]+ overrides the running context
 * only if the guess equals it or is confident, only longer sections move the
 * context, and a short section between two neighbours that agree is snapped
 * to them. Anything not in [languages] is read as the closest of them.
 *
 * [detect] returns base-language → confidence (e.g. `de` → 0.93), or null.
 */
class SectionLanguages(
    private val detect: (String) -> Map<String, Float>?,
    private val minDetectChars: Int = 24,
    private val moveContextChars: Int = 48,
    private val confident: Float = 0.8f,
) {
    fun assign(sections: List<String>, languages: List<String>, initial: String): List<String> {
        if (sections.isEmpty()) return emptyList()
        if (languages.size < 2) return List(sections.size) { initial }
        val byBase = languages.associateBy { it.substringBefore('-').lowercase() }
        var context = initial
        val out = sections.map { text ->
            val guess = if (text.length >= minDetectChars) best(text, byBase) else null
            val chosen = when {
                guess == null -> context
                guess.first == context || guess.second >= confident -> guess.first
                else -> context
            }
            if (guess != null && chosen == guess.first && text.length >= moveContextChars) context = chosen
            chosen
        }.toMutableList()
        for (i in 1 until out.size - 1) {
            if (sections[i].length < moveContextChars && out[i - 1] == out[i + 1] && out[i] != out[i - 1]) out[i] = out[i - 1]
        }
        return out
    }

    private fun best(text: String, byBase: Map<String, String>): Pair<String, Float>? {
        val scores = runCatching { detect(text) }.getOrNull() ?: return null
        return scores.entries
            .mapNotNull { (base, score) -> byBase[base.lowercase()]?.let { it to score } }
            .maxByOrNull { it.second }
    }
}
