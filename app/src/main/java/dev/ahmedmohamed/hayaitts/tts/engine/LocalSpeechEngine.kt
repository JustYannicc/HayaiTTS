package dev.ahmedmohamed.hayaitts.tts.engine

import dev.ahmedmohamed.hayaitts.data.playground.VoiceTuning
import dev.ahmedmohamed.hayaitts.data.playground.VoiceTuningRepository
import dev.ahmedmohamed.hayaitts.domain.model.InstalledVoice
import dev.ahmedmohamed.hayaitts.domain.repo.DefaultsRepository
import dev.ahmedmohamed.hayaitts.domain.repo.SettingsRepository
import dev.ahmedmohamed.hayaitts.tts.SherpaTtsRuntime
import dev.ahmedmohamed.hayaitts.tts.TtsLocales
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * On-device sherpa-onnx voices behind the [SpeechEngine] contract.
 *
 * Voice ids are `voiceId` or `voiceId#speakerId`. Per-voice Studio tuning
 * and the default speaker are applied here so every caller (system TTS,
 * router fallbacks) sounds the same.
 */
class LocalSpeechEngine(
    private val runtime: SherpaTtsRuntime,
    private val tuning: VoiceTuningRepository?,
    private val settings: SettingsRepository?,
    private val defaults: DefaultsRepository?,
) : SpeechEngine {

    override val kind = EngineKind.LOCAL

    fun installed(): List<InstalledVoice> = runtime.listAvailableVoices()

    override fun voices(): List<EngineVoice> = installed().flatMap { v ->
        val locale = TtsLocales.toBcp47(v.languages.firstOrNull() ?: "")
        if (v.speakers.size > 1) {
            v.speakers.map { s -> EngineVoice(kind, "${v.voiceId}#${s.id}", "${v.title} · ${s.name}", locale, false) }
        } else {
            listOf(EngineVoice(kind, v.voiceId, v.title, locale, false))
        }
    }

    /** The user's per-locale default if installed, else the installed voice that matches [bcp47] best. */
    fun bestVoiceFor(bcp47: String): InstalledVoice? {
        if (bcp47.isBlank()) return null
        val voices = installed()
        if (voices.isEmpty()) return null
        val pinnedMap = defaults?.let { runCatching { runBlocking { it.defaults.first() } }.getOrNull() }.orEmpty()
        val language = bcp47.substringBefore('-')
        val pinned = listOf(bcp47, language).firstNotNullOfOrNull { tag ->
            pinnedMap.entries.firstOrNull { it.key.equals(tag, ignoreCase = true) }?.value
        }
        voices.firstOrNull { it.voiceId == pinned }?.let { return it }
        return voices
            .map { it to TtsLocales.matchLevel(it.languages, bcp47) }
            .filter { it.second > TtsLocales.NOT_SUPPORTED }
            .maxByOrNull { it.second }
            ?.first
    }

    fun defaultSpeakerOf(voiceId: String): Int? =
        settings?.let { runCatching { runBlocking { it.defaultSpeakerByVoice.first()[voiceId] } }.getOrNull() }

    override fun synthesize(
        voiceId: String,
        text: String,
        language: String,
        speed: Float,
        pitch: Float,
        isCancelled: () -> Boolean,
    ): EngineAudio {
        val (id, explicitSid) = parse(voiceId)
        val voice = installed().firstOrNull { it.voiceId == id } ?: throw EngineFailure("Voice $id is not installed")
        val t = tuning?.let { runCatching { runBlocking { it.tuningFor(id).first() } }.getOrNull() } ?: VoiceTuning.Default
        val sid = explicitSid ?: defaultSpeakerOf(id) ?: voice.speakers.firstOrNull()?.id ?: 0
        return try {
            val out = runtime.synthesize(
                voiceId = id,
                text = text,
                sid = sid,
                speed = (speed * t.speed).coerceIn(0.25f, 4f),
                pitch = (pitch * t.pitch).coerceIn(0.5f, 2f),
                lengthScale = t.lengthScale,
                noiseScale = t.noiseScale,
                noiseScaleW = t.noiseScaleW,
                languageHint = language.ifBlank { null },
            )
            EngineAudio(out.samples, out.sampleRate)
        } catch (e: Throwable) {
            throw EngineFailure("Local synthesis failed for $id: ${e.message}", e)
        }
    }

    companion object {
        fun parse(voiceId: String): Pair<String, Int?> {
            val parts = voiceId.split('#', limit = 3)
            return parts[0] to parts.getOrNull(1)?.toIntOrNull()
        }
    }
}
