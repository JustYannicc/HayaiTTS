package dev.ahmedmohamed.hayaitts.tts.engine

import android.content.Context
import android.os.LocaleList
import dev.ahmedmohamed.hayaitts.domain.model.LanguageProfile
import dev.ahmedmohamed.hayaitts.domain.model.VoiceRoute
import dev.ahmedmohamed.hayaitts.domain.repo.ReadingProfilesRepository
import dev.ahmedmohamed.hayaitts.tts.TtsLocales
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext

/**
 * First run: one profile per system language (up to two, like Narrateify),
 * each "online voice, then best installed voice". Runs once; an emptied list
 * stays empty.
 */
object ReadingDefaults {

    fun seedInBackground(context: Context) {
        Thread({ runCatching { seed(context) } }, "hayai-seed-profiles").start()
    }

    private fun seed(context: Context) = runBlocking {
        val repo = GlobalContext.getOrNull()?.getOrNull<ReadingProfilesRepository>() ?: return@runBlocking
        if (repo.seeded.first()) return@runBlocking
        if (repo.profiles.first().isEmpty()) repo.setProfiles(defaultsFor(systemLanguages(), Engines.get(context).edge))
        repo.markSeeded()
    }

    fun defaultsFor(languages: List<String>, online: OnlineEngine?): List<LanguageProfile> = languages.map { tag ->
        LanguageProfile(tag, listOfNotNull(online?.let { o -> o.defaultVoiceFor(tag)?.let { VoiceRoute(o.engine.kind.id, it) } }, VoiceRoute.LocalAuto))
    }

    private fun systemLanguages(): List<String> {
        val list = LocaleList.getDefault()
        return (0 until list.size()).map { TtsLocales.toBcp47(list[it].toLanguageTag()) }
            .filter { it.isNotBlank() }
            .distinctBy { it.substringBefore('-') }
            .take(2)
            .ifEmpty { listOf("en-US") }
    }
}
