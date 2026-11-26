package dev.ahmedmohamed.hayaitts.tts.edge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import dev.ahmedmohamed.hayaitts.tts.TtsLocales
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One entry of the service's voice list (only the fields we use). */
@Serializable
data class EdgeVoice(
    @SerialName("ShortName") val shortName: String,
    @SerialName("Locale") val locale: String,
    @SerialName("Gender") val gender: String? = null,
) {
    val isMultilingual: Boolean get() = "Multilingual" in shortName

    /** `en-US-AvaMultilingualNeural` + Female → `Ava (Multilingual) · Female`. */
    val displayName: String
        get() {
            var core = shortName.removePrefix("$locale-").removeSuffix("Neural")
            var suffix = ""
            if (core.endsWith("Multilingual")) {
                core = core.removeSuffix("Multilingual")
                suffix = " (Multilingual)"
            }
            return core + suffix + (gender?.let { " · $it" } ?: "")
        }
}

/**
 * Voice list: memory → disk cache (7 days) → network → hardcoded fallback.
 * Reads never touch the network; [voices] kicks a background refresh on
 * [executor] when the list is stale.
 */
internal class EdgeVoiceCatalog(
    client: OkHttpClient,
    private val cacheFile: File?,
    private val executor: Executor,
    private val endpoints: List<EdgeEndpoint> = EdgeEndpointV1.ALL,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var memory: List<EdgeVoice>
    /** Epoch millis when [memory] stops being good enough and the network should be tried. */
    private var expiresAt: Long
    private val refreshing = AtomicBoolean(false)
    private val http = client.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .callTimeout(FETCH_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    init {
        val cached = cacheFile?.let(::readCache)
        memory = cached?.voices?.takeIf { it.isNotEmpty() } ?: FALLBACK
        expiresAt = cached?.let { it.fetchedAt + CACHE_TTL_MS } ?: 0L
    }

    /** All voices without blocking; schedules a refresh when stale. */
    fun voices(): List<EdgeVoice> {
        val (current, stale) = synchronized(lock) { memory to (clock() >= expiresAt) }
        if (stale) refreshInBackground()
        return current
    }

    fun refreshInBackground() {
        if (!refreshing.compareAndSet(false, true)) return
        executor.execute {
            try {
                refreshNow()
            } finally {
                refreshing.set(false)
            }
        }
    }

    /** Blocking network fetch. Returns the new list, or null (and backs off [RETRY_AFTER_MS]) on failure. */
    fun refreshNow(): List<EdgeVoice>? {
        val fetched = fetch()?.let(::withPreferredDefaults)
        if (fetched == null) {
            synchronized(lock) { expiresAt = clock() + RETRY_AFTER_MS }
            return null
        }
        synchronized(lock) {
            memory = fetched
            expiresAt = clock() + CACHE_TTL_MS
        }
        cacheFile?.let { synchronized(cacheFile) { writeCache(it, CacheFile(clock(), fetched)) } }
        return fetched
    }

    fun defaultVoiceFor(languageTag: String): String? = defaultVoiceFor(voices(), languageTag)

    private fun fetch(): List<EdgeVoice>? {
        for (endpoint in endpoints) {
            val voices = runCatching {
                http.newCall(endpoint.voiceListRequest(EdgeClock.now())).execute().use { response ->
                    if (response.code != 200) return@use null
                    parseVoiceList(response.body?.string() ?: return@use null)
                }
            }.getOrNull()
            if (!voices.isNullOrEmpty()) return voices
        }
        return null
    }

    private fun readCache(file: File): CacheFile? = runCatching {
        json.decodeFromString(CacheFile.serializer(), file.readText())
    }.getOrNull()

    private fun writeCache(file: File, cache: CacheFile) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(CacheFile.serializer(), cache))
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }

    @Serializable
    private class CacheFile(val fetchedAt: Long, val voices: List<EdgeVoice>)

    companion object {
        const val CACHE_TTL_MS = 7L * 24 * 3600 * 1000
        const val RETRY_AFTER_MS = 5L * 60 * 1000
        const val FETCH_TIMEOUT_S = 6L

        private val json = Json { ignoreUnknownKeys = true }

        /** Parses the service's voice-list JSON array; drops entries without ShortName/Locale. */
        fun parseVoiceList(body: String): List<EdgeVoice> =
            json.decodeFromString(ListSerializer(EdgeVoice.serializer()), body)
                .filter { it.shortName.isNotBlank() && it.locale.isNotBlank() }

        /**
         * Adds hand-picked defaults the live list omits (as of 2026-11 it lists
         * de-DE Tanja/Christoph but not Katja, which still synthesizes), so the
         * default is always selectable. Only for locales the list covers.
         */
        fun withPreferredDefaults(voices: List<EdgeVoice>): List<EdgeVoice> {
            val names = voices.mapTo(HashSet()) { it.shortName }
            val locales = voices.mapTo(HashSet()) { it.locale }
            val missing = FALLBACK.filter {
                it.shortName !in names && it.locale in locales && PREFERRED_DEFAULTS[it.locale] == it.shortName
            }
            return voices + missing
        }

        /** Hand-picked plain (single-language) defaults; plain voices start faster than Multilingual ones. */
        val PREFERRED_DEFAULTS: Map<String, String> = mapOf(
            "en-US" to "en-US-AvaNeural",
            "en-GB" to "en-GB-SoniaNeural",
            "de-DE" to "de-DE-KatjaNeural",
            "de-CH" to "de-CH-LeniNeural",
            "de-AT" to "de-AT-IngridNeural",
            "fr-FR" to "fr-FR-DeniseNeural",
            "es-ES" to "es-ES-ElviraNeural",
            "it-IT" to "it-IT-ElsaNeural",
            "pt-BR" to "pt-BR-FranciscaNeural",
            "nl-NL" to "nl-NL-ColetteNeural",
            "ja-JP" to "ja-JP-NanamiNeural",
            "zh-CN" to "zh-CN-XiaoxiaoNeural",
        )

        /** Region with the best neural voices per base language. */
        private val PREFERRED_REGION: Map<String, String> = mapOf(
            "en" to "en-US", "de" to "de-DE", "fr" to "fr-FR", "es" to "es-ES", "it" to "it-IT",
            "pt" to "pt-BR", "nl" to "nl-NL", "ja" to "ja-JP", "zh" to "zh-CN",
        )

        /**
         * Default voice for a BCP-47 tag: exact locale, else the same base
         * language (preferred region first). Within a locale: the hand-picked
         * default when available, else plain (non-Multilingual) voices first.
         */
        fun defaultVoiceFor(voices: List<EdgeVoice>, languageTag: String): String? {
            val tag = TtsLocales.toBcp47(languageTag).ifEmpty { return null }
            val base = tag.substringBefore('-')
            val exact = voices.filter { it.locale.equals(tag, ignoreCase = true) }
            val pool = exact.ifEmpty {
                val sameBase = voices.filter { it.locale.substringBefore('-').equals(base, ignoreCase = true) }
                val region = PREFERRED_REGION[base]
                sameBase.filter { it.locale.equals(region, ignoreCase = true) }.ifEmpty { sameBase }
            }
            if (pool.isEmpty()) return null
            val locale = pool.first().locale
            PREFERRED_DEFAULTS[locale]?.let { preferred -> if (pool.any { it.shortName == preferred }) return preferred }
            return pool.sortedWith(compareBy<EdgeVoice>({ it.isMultilingual }, { it.locale }, { it.shortName }))
                .first().shortName
        }

        private fun v(name: String, gender: String) = EdgeVoice(name, name.split('-').take(2).joinToString("-"), gender)

        /** Offline fallback: plain voices only, so a stale list never routes to a slow Multilingual voice. */
        val FALLBACK: List<EdgeVoice> = listOf(
            v("en-US-AvaNeural", "Female"), v("en-US-AriaNeural", "Female"), v("en-US-JennyNeural", "Female"),
            v("en-US-GuyNeural", "Male"), v("en-US-AndrewNeural", "Male"), v("en-US-ChristopherNeural", "Male"),
            v("en-GB-SoniaNeural", "Female"), v("en-GB-RyanNeural", "Male"),
            v("de-DE-KatjaNeural", "Female"), v("de-DE-ConradNeural", "Male"), v("de-DE-AmalaNeural", "Female"),
            v("de-DE-KillianNeural", "Male"),
            v("de-CH-LeniNeural", "Female"), v("de-CH-JanNeural", "Male"),
            v("de-AT-IngridNeural", "Female"), v("de-AT-JonasNeural", "Male"),
            v("fr-FR-DeniseNeural", "Female"), v("fr-FR-HenriNeural", "Male"),
            v("es-ES-ElviraNeural", "Female"), v("es-ES-AlvaroNeural", "Male"),
            v("it-IT-ElsaNeural", "Female"), v("it-IT-DiegoNeural", "Male"),
            v("pt-BR-FranciscaNeural", "Female"), v("pt-BR-AntonioNeural", "Male"),
            v("nl-NL-ColetteNeural", "Female"), v("nl-NL-MaartenNeural", "Male"),
            v("ja-JP-NanamiNeural", "Female"), v("ja-JP-KeitaNeural", "Male"),
            v("zh-CN-XiaoxiaoNeural", "Female"), v("zh-CN-YunxiNeural", "Male"),
        )
    }
}
