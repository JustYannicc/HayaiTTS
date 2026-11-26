package dev.ahmedmohamed.hayaitts.data.routing

import android.icu.util.ULocale
import android.os.LocaleList
import co.touchlab.kermit.Logger
import dev.ahmedmohamed.hayaitts.domain.model.LanguageProfile
import dev.ahmedmohamed.hayaitts.domain.model.LanguageTags
import dev.ahmedmohamed.hayaitts.domain.repo.ReadingProfilesRepository
import dev.ahmedmohamed.hayaitts.domain.repo.SettingsRepository
import dev.ahmedmohamed.hayaitts.domain.repo.SpeechEnginesGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/**
 * Keeps "the languages I read" one setting: the allowed-language set
 * (Settings → Languages shown, which gates Browse) and the Reading priority
 * lists always cover the same base languages.
 *
 * Each side forwards only what it added or removed since its last emission,
 * so quick toggles on either screen never revert each other, and changes
 * this class makes itself come back as empty diffs. Picking German anywhere
 * gives it a priority list (online default voice, then the best installed
 * one); removing it anywhere removes it from both.
 */
class LanguageSync(
    private val settings: SettingsRepository,
    private val profiles: ReadingProfilesRepository,
    private val engines: SpeechEnginesGateway,
    private val scope: CoroutineScope,
) {
    private val log = Logger.withTag("LanguageSync")
    private val lock = Mutex()
    private var seenAllowed: Set<String> = emptySet()
    private var seenProfiles: Set<String> = emptySet()

    fun start() {
        scope.launch {
            lock.withLock { startup() }
            launch {
                settings.allowedLanguages.map(::bases).distinctUntilChanged()
                    .collect { now -> lock.withLock { onAllowed(now) } }
            }
            launch {
                profiles.profiles.map { list -> bases(list.map { it.language }) }.distinctUntilChanged()
                    .collect { now -> lock.withLock { onProfiles(now) } }
            }
        }
    }

    /**
     * First run seeds the system languages (up to two). After that, and on
     * upgrade from a build without sync, both sides become their union so
     * nothing the user set up is dropped.
     */
    private suspend fun startup() {
        var profileBases = bases(profiles.profiles.first().map { it.language })
        val allowed = bases(settings.allowedLanguages.first())
        if (!profiles.seeded.first()) {
            if (profileBases.isEmpty() && allowed.isEmpty()) {
                val seed = systemLanguages()
                profiles.updateProfiles { current -> current.ifEmpty { seed } }
                profileBases = seed.mapTo(sortedSetOf()) { LanguageTags.base(it.language) }
            }
            profiles.markSeeded()
        }
        val union = allowed + profileBases
        addProfiles(union - profileBases)
        if (union != allowed && union.isNotEmpty()) settings.setAllowedLanguages(union)
        seenAllowed = union
        seenProfiles = union
    }

    private suspend fun onAllowed(now: Set<String>) {
        val added = now - seenAllowed
        val removed = seenAllowed - now
        seenAllowed = now
        if (added.isEmpty() && removed.isEmpty()) return
        // Empty = "show everything" in Browse; don't wipe the priority lists for it.
        if (now.isEmpty()) return
        addProfiles(added)
        if (removed.isNotEmpty()) profiles.updateProfiles { list -> list.filterNot { LanguageTags.base(it.language) in removed } }
        seenProfiles = (seenProfiles + added) - removed
        log.i { "Languages shown +$added -$removed → priority lists follow" }
    }

    private suspend fun onProfiles(now: Set<String>) {
        val added = now - seenProfiles
        val removed = seenProfiles - now
        seenProfiles = now
        if (added.isEmpty() && removed.isEmpty()) return
        val current = bases(settings.allowedLanguages.first())
        val next = (current + added) - removed
        if (next != current) settings.setAllowedLanguages(next)
        seenAllowed = next
    }

    private suspend fun addProfiles(bases: Set<String>) {
        if (bases.isEmpty()) return
        val fresh = bases.sorted().map { base -> regionalTag(base).let { LanguageProfile(it, engines.defaultRoutesFor(it)) } }
        profiles.updateProfiles { list ->
            val have = list.mapTo(mutableSetOf()) { LanguageTags.base(it.language) }
            list + fresh.filter { LanguageTags.base(it.language) !in have }
        }
    }

    private suspend fun systemLanguages(): List<LanguageProfile> =
        (0 until LocaleList.getDefault().size()).map { LocaleList.getDefault()[it] }
            .filter { it.language.isNotEmpty() }
            .distinctBy { it.language }
            .take(2)
            .map { regionalTag(it.language) }
            .ifEmpty { listOf("en-US") }
            .map { tag -> LanguageProfile(tag, engines.defaultRoutesFor(tag)) }

    private fun bases(tags: Collection<String>): Set<String> = tags.mapTo(sortedSetOf(), LanguageTags::base)

    /** `de` → `de-DE`: the system's own region for that language if it has one, else the likely one. */
    private fun regionalTag(base: String): String {
        val system = LocaleList.getDefault()
        for (i in 0 until system.size()) {
            val l = system[i]
            if (l.language == base && l.country.isNotEmpty()) return "${l.language}-${l.country}"
        }
        val likely = runCatching { ULocale.addLikelySubtags(ULocale(base)).country }.getOrNull().orEmpty()
        return if (likely.isEmpty()) base else Locale(base, likely).toLanguageTag()
    }
}
