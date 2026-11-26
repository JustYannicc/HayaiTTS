package dev.ahmedmohamed.hayaitts.ui.reading

import dev.ahmedmohamed.hayaitts.core.result.AppError
import dev.ahmedmohamed.hayaitts.core.result.Outcome
import dev.ahmedmohamed.hayaitts.domain.model.LanguageProfile
import dev.ahmedmohamed.hayaitts.domain.model.VoiceRoute
import dev.ahmedmohamed.hayaitts.domain.repo.ReadingProfilesRepository
import dev.ahmedmohamed.hayaitts.domain.repo.RouteVoice
import dev.ahmedmohamed.hayaitts.domain.repo.SpeechEnginesGateway
import dev.ahmedmohamed.hayaitts.domain.repo.SpokenSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingViewModelTest {

    private class FakeRepo(initial: List<LanguageProfile>) : ReadingProfilesRepository {
        override val profiles = MutableStateFlow(initial)
        override suspend fun setProfiles(profiles: List<LanguageProfile>) { this.profiles.value = profiles }
        override val onlineEnginesEnabled = MutableStateFlow(true)
        override suspend fun setOnlineEnginesEnabled(enabled: Boolean) { onlineEnginesEnabled.value = enabled }
        override val seeded = MutableStateFlow(true)
        override suspend fun markSeeded() = Unit
    }

    private class FakeEngines(private val online: Boolean) : SpeechEnginesGateway {
        override suspend fun onlineAvailable() = online
        var benched = true
        var result: Outcome<SpokenSample>? = null
        var played: SpokenSample? = null
        override suspend fun voices() = listOf(
            RouteVoice(VoiceRoute("local", "piper-de"), "Thorsten", listOf("de-DE")),
            RouteVoice(VoiceRoute("local", "kokoro#3"), "Kokoro · Emma", listOf("en-US", "ja-JP")),
            RouteVoice(VoiceRoute("edge", "de-AT-JonasNeural"), "Jonas", listOf("de-AT")),
            RouteVoice(VoiceRoute("edge", "fr-FR-DeniseNeural"), "Denise", listOf("fr-FR")),
        )
        override suspend fun defaultRoutesFor(language: String) = listOf(VoiceRoute("edge", "$language-Default"), VoiceRoute.LocalAuto)
        override fun isOnlineBenched() = benched
        override fun resetHealth() { benched = false }
        override suspend fun synthesizeSample(profile: LanguageProfile) = result!!
        override suspend fun play(sample: SpokenSample) { played = sample }
        override fun stopPlayback() = Unit
    }

    private val de = LanguageProfile("de-DE", listOf(VoiceRoute("local", "piper-de"), VoiceRoute.LocalAuto))
    private val en = LanguageProfile("en-US", listOf(VoiceRoute.LocalAuto))

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun fixture(online: Boolean = true) = FakeRepo(listOf(de, en)).let { repo ->
        val engines = FakeEngines(online)
        Triple(repo, engines, ReadingViewModel(repo, engines))
    }

    @Test
    fun `edits - reorder remove add - persisted to repository`() = runTest(UnconfinedTestDispatcher()) {
        val (repo, _, vm) = fixture()
        vm.moveRoute("de-DE", 1, -1)
        assertEquals(listOf(VoiceRoute.LocalAuto, VoiceRoute("local", "piper-de")), repo.profiles.value[0].routes)
        vm.removeRoute("de-DE", 0)
        assertEquals(listOf(VoiceRoute("local", "piper-de")), repo.profiles.value[0].routes)
        vm.removeRoute("de-DE", 0)
        assertEquals(1, repo.profiles.value[0].routes.size)
        vm.addRoute("en-US", VoiceRoute("local", "kokoro#3"))
        assertEquals(VoiceRoute("local", "kokoro#3"), repo.profiles.value[1].routes.last())
        vm.moveLanguage("en-US", -1)
        assertEquals(listOf("en-US", "de-DE"), repo.profiles.value.map { it.language })
        vm.removeLanguage("de-DE")
        assertEquals(listOf("en-US"), repo.profiles.value.map { it.language })
    }

    @Test
    fun `addLanguage - uses engine defaults`() = runTest(UnconfinedTestDispatcher()) {
        val (repo, _, vm) = fixture()
        vm.addLanguage("fr-FR")
        assertEquals(LanguageProfile("fr-FR", listOf(VoiceRoute("edge", "fr-FR-Default"), VoiceRoute.LocalAuto)), repo.profiles.value.last())
    }

    @Test
    fun `addableLanguages - excludes existing, hides edge-only languages when edge missing`() = runTest(UnconfinedTestDispatcher()) {
        val (_, _, vm) = fixture(online = true)
        backgroundScope.launch { vm.uiState.collect {} }
        assertEquals(listOf("ja-JP", "de-AT", "fr-FR"), vm.uiState.value.addableLanguages)
        val (_, _, offline) = fixture(online = false)
        backgroundScope.launch { offline.uiState.collect {} }
        assertEquals(listOf("ja-JP"), offline.uiState.value.addableLanguages)
    }

    @Test
    fun `voicesFor - matches base language across regions`() = runTest(UnconfinedTestDispatcher()) {
        val (_, _, vm) = fixture()
        backgroundScope.launch { vm.uiState.collect {} }
        assertEquals(listOf("Jonas"), vm.uiState.value.voicesFor("de-DE", "edge").map { it.displayName })
        assertEquals(listOf("Thorsten"), vm.uiState.value.voicesFor("de-DE", "local").map { it.displayName })
    }

    @Test
    fun `retryOnline - clears bench`() = runTest(UnconfinedTestDispatcher()) {
        val (_, engines, vm) = fixture()
        backgroundScope.launch { vm.uiState.collect {} }
        assertTrue(vm.uiState.value.onlineBenched)
        vm.retryOnline()
        assertFalse(engines.benched)
        assertFalse(vm.uiState.value.onlineBenched)
    }

    @Test
    fun `test - success - records route that spoke and plays it`() = runTest(UnconfinedTestDispatcher()) {
        val (_, engines, vm) = fixture()
        backgroundScope.launch { vm.uiState.collect {} }
        val sample = SpokenSample(VoiceRoute("local", "piper-de"), "Thorsten", FloatArray(4), 22050)
        engines.result = Outcome.Success(sample)
        vm.test("de-DE")
        assertEquals(ReadingViewModel.TestState.Spoke(sample.route, "Thorsten"), vm.uiState.value.tests["de-DE"])
        assertEquals(sample, engines.played)
    }

    @Test
    fun `test - failure - records reason`() = runTest(UnconfinedTestDispatcher()) {
        val (_, engines, vm) = fixture()
        backgroundScope.launch { vm.uiState.collect {} }
        engines.result = Outcome.Failure(AppError.Network, IllegalStateException("offline"))
        vm.test("en-US")
        assertEquals(ReadingViewModel.TestState.Failed("offline"), vm.uiState.value.tests["en-US"])
    }
}
