package dev.ahmedmohamed.hayaitts.domain.model

import java.util.Locale

/**
 * Readable names for catalog voices whose upstream title is just the
 * anonymous speaker label (`Speaker_0`). The name is rebuilt from the
 * bundle id, dropping only tokens that are the voice's own language codes:
 * `vits-coqui-de-css10` → `Coqui CSS10`,
 * `sherpa-onnx-zipvoice-distill-int8-zh-en-emilia` → `ZipVoice Emilia (distill, int8)`.
 */
object VoiceTitles {

    private val ANONYMOUS = Regex("""(?i)^speaker[ _-]?\d+$""")
    private val DROP = setOf("vits", "sherpa", "onnx", "tts", "piper", "icefall", "matcha", "model", "models", "hf")
    private val VARIANTS = listOf("distill", "int8", "fp16", "fp32")
    /** The tier chip already shows quality. */
    private val QUALITY = setOf("low", "medium", "high", "x")
    private val DATE_TOKEN = Regex("""^\d{2,4}$""")
    private val CASING = mapOf(
        "ljspeech" to "LJSpeech", "ljs" to "LJS", "vctk" to "VCTK", "mms" to "MMS", "css10" to "CSS10",
        "coqui" to "Coqui", "melo" to "MeloTTS", "zipvoice" to "ZipVoice", "pocket" to "Pocket",
        "supertonic" to "Supertonic", "kokoro" to "Kokoro", "kitten" to "Kitten", "emilia" to "Emilia",
        "neon" to "Neon", "cv" to "Common Voice",
    )

    fun display(id: String, title: String, languages: List<String>): String =
        if (title.isBlank() || ANONYMOUS.matches(title.trim())) fromId(id, languages) ?: title else title

    fun fromId(id: String, languages: List<String>): String? {
        val codes = languageCodes(languages)
        val tokens = id.split('-').filter { it.isNotBlank() }
        val variants = VARIANTS.filter { v -> tokens.any { it.equals(v, ignoreCase = true) } }
        val words = tokens
            .filter { t ->
                val l = t.lowercase()
                l !in DROP && l !in VARIANTS && !DATE_TOKEN.matches(l) && !isOwnLanguage(l, codes)
            }
            .flatMap { it.split('_') }
            .filter { it.isNotBlank() && it.lowercase() !in QUALITY }
            .map { t -> CASING[t.lowercase()] ?: t.replaceFirstChar { it.uppercase() } }
        if (words.isEmpty()) return null
        val suffix = if (variants.isEmpty()) "" else " (${variants.joinToString(", ")})"
        return words.joinToString(" ") + suffix
    }

    /** `en_GB`, `fa_en`, `de`, `deu` — a token naming the voice's own language(s). */
    private fun isOwnLanguage(token: String, codes: Set<String>): Boolean =
        token.split('_').first() in codes

    /** Base codes plus their ISO 639-2 forms (`de` → `de`, `deu`), plus `yue`-style 3-letter tags as-is. */
    private fun languageCodes(languages: List<String>): Set<String> = languages.flatMap { tag ->
        val base = LanguageTags.base(tag)
        listOfNotNull(base, runCatching { Locale(base).isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() })
    }.toSet()
}
