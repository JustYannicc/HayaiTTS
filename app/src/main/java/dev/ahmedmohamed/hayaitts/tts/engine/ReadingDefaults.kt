package dev.ahmedmohamed.hayaitts.tts.engine

import android.content.Context
import dev.ahmedmohamed.hayaitts.domain.model.LanguageProfile
import dev.ahmedmohamed.hayaitts.domain.model.VoiceRoute

/**
 * Starting routes for a language: the online engine's default voice, then
 * the best installed voice. First-run seeding lives in LanguageSync.
 */
object ReadingDefaults {

    /** Builds the engine set off the main thread so the first request or settings screen doesn't pay for it. */
    fun warmInBackground(context: Context) {
        Thread({ runCatching { Engines.get(context) } }, "hayai-warm-engines").start()
    }

    fun defaultsFor(languages: List<String>, online: OnlineEngine?): List<LanguageProfile> = languages.map { tag ->
        LanguageProfile(tag, listOfNotNull(online?.let { o -> o.defaultVoiceFor(tag)?.let { VoiceRoute(o.engine.kind.id, it) } }, VoiceRoute.LocalAuto))
    }
}
