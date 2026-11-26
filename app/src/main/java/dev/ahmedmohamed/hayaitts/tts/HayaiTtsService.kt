package dev.ahmedmohamed.hayaitts.tts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import co.touchlab.kermit.Logger
import dev.ahmedmohamed.hayaitts.data.voices.parseLocale
import dev.ahmedmohamed.hayaitts.domain.model.InstalledVoice
import dev.ahmedmohamed.hayaitts.domain.model.VoiceRoute
import dev.ahmedmohamed.hayaitts.tts.engine.Engines
import dev.ahmedmohamed.hayaitts.tts.engine.LocalSpeechEngine
import dev.ahmedmohamed.hayaitts.tts.engine.Resampler

/**
 * System-wide TTS engine.
 *
 * Voices exposed to the framework:
 * - `chain:<lang>`: the user's priority list for a language (Settings →
 *   Reading). This is the default voice for any locale that has a profile;
 *   each section goes to the first engine/voice in the list that works.
 * - `<voiceId>` / `<voiceId>#<sid>#<name>`: an installed on-device voice.
 * - `edge:<ShortName>`: a Microsoft Edge online voice (when enabled).
 * An explicitly chosen voice reads first; its language's list is the fallback.
 */
class HayaiTtsService : TextToSpeechService() {

    private val log = Logger.withTag("HayaiTtsService")

    @Volatile private var stopRequested: Boolean = false
    @Volatile private var lastSelectedVoiceName: String = ""

    private val engines by lazy { Engines.get(applicationContext) }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val requested = TtsLocales.toBcp47(lang, country)
        if (requested.isEmpty()) return TextToSpeech.LANG_NOT_SUPPORTED
        val tags = availableLanguageTags(applicationContext)
        return when (TtsLocales.matchLevel(tags, requested)) {
            TtsLocales.COUNTRY -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            TtsLocales.LANGUAGE -> TextToSpeech.LANG_AVAILABLE
            else -> TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val status = onIsLanguageAvailable(lang, country, variant)
        if (status >= TextToSpeech.LANG_AVAILABLE) {
            val tag = TtsLocales.toBcp47(lang, country)
            defaultVoiceName(tag)?.let { lastSelectedVoiceName = it }
            prewarm(tag)
        }
        return status
    }

    /**
     * The stock implementation returns the bare locale tag and validates it
     * as a voice name, which never matched, so `setLanguage()` failed for
     * every client. Return a real voice instead.
     */
    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? =
        defaultVoiceName(TtsLocales.toBcp47(lang, country))

    override fun onGetLanguage(): Array<String> {
        val tag = languageOfVoiceName(lastSelectedVoiceName) ?: "en-US"
        val parts = (TtsLocales.toIso3(tag) ?: "eng-USA").split('-')
        return arrayOf(parts[0], parts.getOrElse(1) { "" }, "")
    }

    override fun onGetVoices(): List<Voice> {
        val chains = engines.planner().profiles.map { p ->
            Voice(CHAIN + p.language, parseLocale(p.language), Voice.QUALITY_VERY_HIGH, Voice.LATENCY_NORMAL, false, emptySet())
        }
        val local = localVoices().flatMap { v ->
            val locale = parseLocale(TtsLocales.toBcp47(v.languages.firstOrNull() ?: "en-US"))
            if (v.speakers.size > 1) {
                v.speakers.map { s -> Voice("${v.voiceId}#${s.id}#${s.name}", locale, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet()) }
            } else {
                listOf(Voice(v.voiceId, locale, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet()))
            }
        }
        val online = if (engines.onlineEnabled()) {
            engines.edge?.engine?.voices().orEmpty().map { v ->
                Voice(EDGE + v.id, parseLocale(v.locale), Voice.QUALITY_VERY_HIGH, Voice.LATENCY_NORMAL, true, emptySet())
            }
        } else emptyList()
        return chains + local + online
    }

    override fun onIsValidVoiceName(name: String?): Int =
        if (name != null && isKnownVoiceName(name)) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(name: String?): Int {
        if (name == null || !isKnownVoiceName(name)) return TextToSpeech.ERROR
        lastSelectedVoiceName = name
        languageOfVoiceName(name)?.let(::prewarm)
        return TextToSpeech.SUCCESS
    }

    override fun onStop() {
        stopRequested = true
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        stopRequested = false
        val text = request.charSequenceText?.toString().orEmpty()
        val planner = engines.planner()

        val voiceName = request.voiceName?.takeIf { it.isNotBlank() } ?: lastSelectedVoiceName
        val explicit = explicitRoute(voiceName, request.params?.getString("speakerId")?.toIntOrNull())
        val requestLocale = TtsLocales.toBcp47(request.language, request.country)
        val baseLanguage = requestLocale.ifBlank { languageOfVoiceName(voiceName) ?: "en-US" }

        val sections = TextSections.split(text)
        if (sections.isEmpty()) {
            if (callback.start(DEFAULT_RATE, AudioFormat.ENCODING_PCM_16BIT, 1) == TextToSpeech.SUCCESS) callback.done()
            return
        }
        // A specific voice pins the language; a priority list lets each
        // section pick among the user's languages. The first section is
        // assigned on its own so detection over a long text never delays
        // the first audio; the rest is assigned once that's streaming.
        val initial = planner.profileFor(baseLanguage)?.language ?: baseLanguage
        fun assign(texts: List<String>) =
            if (explicit != null) List(texts.size) { baseLanguage }
            else engines.sectionLanguages.assign(texts, planner.languages, initial)
        var languages = assign(listOf(sections.first().text))

        val speed = (request.speechRate / 100f).coerceIn(0.25f, 4f)
        val pitch = (request.pitch / 100f).coerceIn(0.25f, 4f)
        var outRate = 0
        var frames = 0
        var lastError = TextToSpeech.ERROR_SYNTHESIS
        for ((i, section) in sections.withIndex()) {
            if (stopRequested) break
            if (i == 1) languages = assign(sections.map { it.text })
            val routes = planner.routesFor(languages[i], explicit)
            val routed = try {
                engines.router.synthesize(routes, section.text, languages[i], speed, pitch) { stopRequested }
            } catch (t: Throwable) {
                if (stopRequested) break
                log.e(t) { "No route could read section $i (${languages[i]}, routes=$routes)" }
                if (routes.isEmpty()) lastError = TextToSpeech.ERROR_NOT_INSTALLED_YET
                continue // one unreadable section shouldn't silence the rest
            }
            if (stopRequested) break
            if (outRate == 0) {
                outRate = routed.audio.sampleRate
                if (callback.start(outRate, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) return
            }
            val samples = Resampler.resample(routed.audio.samples, routed.audio.sampleRate, outRate)
            callback.rangeStart(frames, section.start, section.end)
            if (!streamPcm(callback, samples)) return
            frames += samples.size
        }
        if (outRate == 0) {
            if (stopRequested) {
                if (callback.start(DEFAULT_RATE, AudioFormat.ENCODING_PCM_16BIT, 1) == TextToSpeech.SUCCESS) callback.done()
            } else {
                callback.error(lastError)
            }
            return
        }
        callback.done()
    }

    private fun localVoices(): List<InstalledVoice> = runCatching { engines.local.installed() }.getOrDefault(emptyList())

    /** Priority list for the locale if the user has one, else the best installed local voice. */
    private fun defaultVoiceName(bcp47: String): String? {
        if (bcp47.isBlank()) return null
        engines.planner().profileFor(bcp47)?.let { return CHAIN + it.language }
        val voice = engines.local.bestVoiceFor(bcp47) ?: return null
        if (voice.speakers.size <= 1) return voice.voiceId
        val sid = engines.local.defaultSpeakerOf(voice.voiceId)
        val speaker = voice.speakers.firstOrNull { it.id == sid } ?: voice.speakers.first()
        return "${voice.voiceId}#${speaker.id}#${speaker.name}"
    }

    private fun isKnownVoiceName(name: String): Boolean = when {
        name.startsWith(CHAIN) -> engines.planner().profiles.any { it.language == name.removePrefix(CHAIN) }
        name.startsWith(EDGE) -> engines.edge != null
        else -> {
            val (id, sid) = LocalSpeechEngine.parse(name)
            val voice = localVoices().firstOrNull { it.voiceId == id }
            voice != null && (sid == null || voice.speakers.any { it.id == sid })
        }
    }

    /** The voice a caller picked, as a route; null for chain voices or nothing. */
    private fun explicitRoute(name: String, speakerParam: Int? = null): VoiceRoute? = when {
        name.isBlank() || name.startsWith(CHAIN) -> null
        name.startsWith(EDGE) -> VoiceRoute(VoiceRoute.ENGINE_EDGE, name.removePrefix(EDGE))
        isKnownVoiceName(name) -> LocalSpeechEngine.parse(name).let { (id, parsed) ->
            val sid = parsed ?: speakerParam
            VoiceRoute(VoiceRoute.ENGINE_LOCAL, if (sid == null) id else "$id#$sid")
        }
        else -> null
    }

    private fun languageOfVoiceName(name: String): String? = when {
        name.isBlank() -> null
        name.startsWith(CHAIN) -> name.removePrefix(CHAIN)
        name.startsWith(EDGE) -> name.removePrefix(EDGE).split('-').take(2).joinToString("-")
        else -> localVoices().firstOrNull { it.voiceId == LocalSpeechEngine.parse(name).first }
            ?.languages?.firstOrNull()?.let { TtsLocales.toBcp47(it) }
    }

    private fun prewarm(bcp47: String) {
        val first = engines.planner().routesFor(bcp47).firstOrNull() ?: return
        if (first.engine == VoiceRoute.ENGINE_EDGE) runCatching { engines.edge?.engine?.prewarm() }
    }

    /**
     * Float samples (-1..1) → little-endian 16-bit PCM, in chunks no larger
     * than [SynthesisCallback.getMaxBufferSize]. False if the callback gave up.
     */
    private fun streamPcm(callback: SynthesisCallback, samples: FloatArray): Boolean {
        val chunkBytes = callback.maxBufferSize.coerceAtLeast(BYTES_PER_SAMPLE * 64) and 0x7FFFFFFE
        val samplesPerChunk = chunkBytes / BYTES_PER_SAMPLE
        val buffer = ByteArray(chunkBytes)
        var cursor = 0
        while (cursor < samples.size && !stopRequested) {
            val take = minOf(samplesPerChunk, samples.size - cursor)
            var w = 0
            for (i in 0 until take) {
                val v = (samples[cursor + i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
                buffer[w] = (v and 0xFF).toByte()
                buffer[w + 1] = ((v shr 8) and 0xFF).toByte()
                w += 2
            }
            if (callback.audioAvailable(buffer, 0, w) != TextToSpeech.SUCCESS) return false
            cursor += take
        }
        return true
    }

    companion object {
        /** Languages the engine can speak: installed voices plus the user's priority lists. */
        fun availableLanguageTags(context: android.content.Context): List<String> {
            val engines = Engines.get(context)
            return runCatching { engines.local.installed() }.getOrDefault(emptyList()).flatMap { it.languages } +
                engines.planner().languages
        }

        private const val BYTES_PER_SAMPLE = 2
        private const val DEFAULT_RATE = 22050
        private const val CHAIN = "chain:"
        private const val EDGE = "edge:"
    }
}
