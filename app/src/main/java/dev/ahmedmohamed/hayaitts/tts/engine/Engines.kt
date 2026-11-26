package dev.ahmedmohamed.hayaitts.tts.engine

import android.annotation.SuppressLint
import android.content.Context
import dev.ahmedmohamed.hayaitts.data.playground.VoiceTuningRepository
import dev.ahmedmohamed.hayaitts.domain.model.LanguageProfile
import dev.ahmedmohamed.hayaitts.domain.repo.DefaultsRepository
import dev.ahmedmohamed.hayaitts.domain.repo.ReadingProfilesRepository
import dev.ahmedmohamed.hayaitts.domain.repo.SettingsRepository
import dev.ahmedmohamed.hayaitts.tts.SherpaTtsRuntime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext

/**
 * Process-wide engine set: one router (so benching survives across
 * requests), the local engine, optional online engines, and the language
 * detector. Built lazily from Koin like [SherpaTtsRuntime].
 */
class Engines private constructor(context: Context) {

    private val koin get() = GlobalContext.getOrNull()

    val local = LocalSpeechEngine(
        runtime = SherpaTtsRuntime.get(context),
        tuning = koin?.getOrNull<VoiceTuningRepository>(),
        settings = koin?.getOrNull<SettingsRepository>(),
        defaults = koin?.getOrNull<DefaultsRepository>(),
    )

    /** Online engine, when one is compiled in. */
    val edge: OnlineEngine? = OnlineEngines.create(context)

    val router = SpeechRouter(listOfNotNull(local, edge?.engine).associateBy { it.kind })

    val sectionLanguages = SectionLanguages(AndroidLanguageDetector(context))

    private val profilesRepo get() = koin?.getOrNull<ReadingProfilesRepository>()

    fun profiles(): List<LanguageProfile> =
        profilesRepo?.let { runCatching { runBlocking { it.profiles.first() } }.getOrNull() }.orEmpty()

    fun onlineEnabled(): Boolean =
        edge != null && (profilesRepo?.let { runCatching { runBlocking { it.onlineEnginesEnabled.first() } }.getOrNull() } ?: true)

    fun planner(profiles: List<LanguageProfile> = profiles()): RoutePlanner =
        RoutePlanner(profiles, onlineEnabled()) { lang -> local.bestVoiceFor(lang)?.voiceId }

    companion object {
        @SuppressLint("StaticFieldLeak") // application context only
        @Volatile private var instance: Engines? = null

        fun get(context: Context): Engines = instance ?: synchronized(this) {
            instance ?: Engines(context.applicationContext).also { instance = it }
        }
    }
}

/** An online engine plus the helpers the app needs around it. */
interface OnlineEngine {
    val engine: SpeechEngine
    /** Default voice id for a BCP-47 tag, or null if the engine has none. */
    fun defaultVoiceFor(languageTag: String): String?
}
