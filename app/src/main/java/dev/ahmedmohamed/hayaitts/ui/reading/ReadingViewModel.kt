package dev.ahmedmohamed.hayaitts.ui.reading

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ahmedmohamed.hayaitts.core.result.Outcome
import dev.ahmedmohamed.hayaitts.domain.model.LanguageTags
import dev.ahmedmohamed.hayaitts.domain.model.LanguageProfile
import dev.ahmedmohamed.hayaitts.domain.model.ReadingProfileEdits
import dev.ahmedmohamed.hayaitts.domain.model.VoiceRoute
import dev.ahmedmohamed.hayaitts.domain.repo.ReadingProfilesRepository
import dev.ahmedmohamed.hayaitts.domain.repo.RouteVoice
import dev.ahmedmohamed.hayaitts.domain.repo.SpeechEnginesGateway
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the Reading settings screen: the user's languages, each one's
 * ordered voice routes, the online master switch, Edge health, and a
 * per-language Test. Edits are pure ([ReadingProfileEdits]) and applied
 * to the stored list under a lock so rapid taps never lose an edit.
 */
class ReadingViewModel(
    private val repo: ReadingProfilesRepository,
    private val engines: SpeechEnginesGateway,
) : ViewModel() {

    sealed interface TestState {
        data object Running : TestState
        data class Spoke(val route: VoiceRoute, val voiceName: String) : TestState
        data class Failed(val reason: String) : TestState
    }

    data class UiState(
        val profiles: List<LanguageProfile> = emptyList(),
        val onlineAvailable: Boolean = false,
        val onlineEnabled: Boolean = true,
        val onlineBenched: Boolean = false,
        val voices: List<RouteVoice> = emptyList(),
        val tests: Map<String, TestState> = emptyMap(),
    ) {
        /** Languages that can be added: any voice speaks them and they aren't in the list yet. */
        val addableLanguages: List<String>
            get() {
                val taken = profiles.map { it.language.lowercase() }.toSet()
                return voices
                    .filter { onlineAvailable || it.route.engine == VoiceRoute.ENGINE_LOCAL }
                    .flatMap { it.languages }
                    .distinctBy { it.lowercase() }
                    .filter { it.lowercase() !in taken }
            }

        /** Voices of [engine] that speak [language]'s base language. */
        fun voicesFor(language: String, engine: String): List<RouteVoice> {
            val bases = setOf(LanguageTags.base(language))
            return voices.filter { v -> v.route.engine == engine && LanguageTags.speaksAny(v.languages, bases) }
        }

        fun voiceName(route: VoiceRoute): String? = voices.firstOrNull { it.route == route }?.displayName
    }

    private val local = MutableStateFlow(UiState())
    private val testJobs = HashMap<String, Job>()

    val uiState: StateFlow<UiState> = combine(local, repo.profiles, repo.onlineEnginesEnabled) { s, profiles, online ->
        s.copy(profiles = profiles, onlineEnabled = online)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        refresh()
    }

    /** Reloads voices (new installs, Edge's list arriving) and Edge health. Call on resume. */
    fun refresh() {
        viewModelScope.launch {
            val online = engines.onlineAvailable()
            val voices = engines.voices()
            local.update { it.copy(onlineAvailable = online, voices = voices, onlineBenched = engines.isOnlineBenched()) }
        }
    }

    fun setOnlineEnabled(enabled: Boolean) {
        viewModelScope.launch { repo.setOnlineEnginesEnabled(enabled) }
    }

    fun retryOnline() {
        engines.resetHealth()
        local.update { it.copy(onlineBenched = engines.isOnlineBenched()) }
    }

    fun addLanguage(language: String) {
        viewModelScope.launch {
            val routes = engines.defaultRoutesFor(language)
            repo.updateProfiles { ReadingProfileEdits.addLanguage(it, language, routes) }
        }
    }

    fun removeLanguage(language: String) {
        stopTest(language)
        edit { ReadingProfileEdits.removeLanguage(it, language) }
    }

    fun moveLanguage(language: String, delta: Int) = edit { ReadingProfileEdits.moveLanguage(it, language, delta) }

    fun addRoute(language: String, route: VoiceRoute) = edit { ReadingProfileEdits.addRoute(it, language, route) }

    fun removeRoute(language: String, index: Int) = edit { ReadingProfileEdits.removeRoute(it, language, index) }

    fun moveRoute(language: String, index: Int, delta: Int) = edit { ReadingProfileEdits.moveRoute(it, language, index, delta) }

    /** Speaks a sample through [language]'s routes, as the TTS service would, and records which route spoke. */
    fun test(language: String) {
        stopTest(language)
        setTest(language, TestState.Running)
        val job = viewModelScope.launch {
            val profile = repo.profiles.first().firstOrNull { it.language == language }
            if (profile == null) {
                stopTest(language)
                return@launch
            }
            when (val out = engines.synthesizeSample(profile)) {
                is Outcome.Success -> {
                    setTest(language, TestState.Spoke(out.value.route, out.value.voiceName))
                    local.update { it.copy(onlineBenched = engines.isOnlineBenched()) }
                    engines.play(out.value)
                }
                is Outcome.Failure -> {
                    setTest(language, TestState.Failed(out.cause?.message ?: out.error.toString()))
                    local.update { it.copy(onlineBenched = engines.isOnlineBenched()) }
                }
            }
        }
        testJobs[language] = job
        job.invokeOnCompletion { if (testJobs[language] === job) testJobs.remove(language) }
    }

    private fun stopTest(language: String) {
        // Only a test that's still running owns the shared player; a finished
        // one must not stop another language's playback.
        testJobs.remove(language)?.takeIf { it.isActive }?.let {
            it.cancel()
            engines.stopPlayback()
        }
        local.update { it.copy(tests = it.tests - language) }
    }

    private fun setTest(language: String, state: TestState) = local.update { it.copy(tests = it.tests + (language to state)) }

    /** Applies [change] atomically to the stored list, so the settings sync can't race it. */
    private fun edit(change: (List<LanguageProfile>) -> List<LanguageProfile>) {
        viewModelScope.launch { repo.updateProfiles(change) }
    }

    override fun onCleared() {
        engines.stopPlayback()
    }
}
