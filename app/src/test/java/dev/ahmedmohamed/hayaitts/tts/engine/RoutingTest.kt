package dev.ahmedmohamed.hayaitts.tts.engine

import dev.ahmedmohamed.hayaitts.domain.model.LanguageProfile
import dev.ahmedmohamed.hayaitts.domain.model.VoiceRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingTest {

    private class FakeEngine(override val kind: EngineKind, var fail: Exception? = null) : SpeechEngine {
        var calls = 0
        override fun voices() = emptyList<EngineVoice>()
        override fun synthesize(voiceId: String, text: String, language: String, speed: Float, pitch: Float, isCancelled: () -> Boolean): EngineAudio {
            calls++
            fail?.let { throw it }
            return EngineAudio(FloatArray(10), if (kind == EngineKind.EDGE) 24000 else 22050)
        }
    }

    private val edgeRoute = VoiceRoute("edge", "de-DE-KatjaNeural")
    private val localRoute = VoiceRoute("local", "piper-de")

    @Test
    fun `first working route wins`() {
        val edge = FakeEngine(EngineKind.EDGE)
        val router = SpeechRouter(mapOf(EngineKind.EDGE to edge, EngineKind.LOCAL to FakeEngine(EngineKind.LOCAL)))
        assertEquals(edgeRoute, router.synthesize(listOf(edgeRoute, localRoute), "x", "de-DE", 1f, 1f) { false }.route)
    }

    @Test
    fun `unavailable engine is benched for ten minutes then retried`() {
        var clock = 0L
        val edge = FakeEngine(EngineKind.EDGE, EngineUnavailable("offline"))
        val local = FakeEngine(EngineKind.LOCAL)
        val router = SpeechRouter(mapOf(EngineKind.EDGE to edge, EngineKind.LOCAL to local), now = { clock })
        assertEquals(localRoute, router.synthesize(listOf(edgeRoute, localRoute), "x", "de", 1f, 1f) { false }.route)
        assertTrue(router.isBenched(EngineKind.EDGE))
        router.synthesize(listOf(edgeRoute, localRoute), "y", "de", 1f, 1f) { false }
        assertEquals("benched engine is skipped", 1, edge.calls)
        clock += 10 * 60 * 1000L + 1
        edge.fail = null
        assertEquals(edgeRoute, router.synthesize(listOf(edgeRoute, localRoute), "z", "de", 1f, 1f) { false }.route)
        assertFalse(router.isBenched(EngineKind.EDGE))
    }

    @Test
    fun `voice failure falls through without benching`() {
        val edge = FakeEngine(EngineKind.EDGE, EngineFailure("unknown voice"))
        val router = SpeechRouter(mapOf(EngineKind.EDGE to edge, EngineKind.LOCAL to FakeEngine(EngineKind.LOCAL)))
        assertEquals(localRoute, router.synthesize(listOf(edgeRoute, localRoute), "x", "de", 1f, 1f) { false }.route)
        assertFalse(router.isBenched(EngineKind.EDGE))
    }

    @Test
    fun `when everything is benched the chain is tried anyway`() {
        val edge = FakeEngine(EngineKind.EDGE, EngineUnavailable("offline"))
        val router = SpeechRouter(mapOf(EngineKind.EDGE to edge))
        runCatching { router.synthesize(listOf(edgeRoute), "x", "de", 1f, 1f) { false } }
        edge.fail = null
        assertEquals(edgeRoute, router.synthesize(listOf(edgeRoute), "x", "de", 1f, 1f) { false }.route)
    }

    @Test
    fun `planner resolves auto, honours the online switch and keeps a local last resort`() {
        val profiles = listOf(LanguageProfile("de-DE", listOf(edgeRoute, VoiceRoute.LocalAuto)))
        val online = RoutePlanner(profiles, onlineEnabled = true) { "piper-de" }
        assertEquals(listOf(edgeRoute, localRoute), online.routesFor("de-CH"))
        val offline = RoutePlanner(profiles, onlineEnabled = false) { "piper-de" }
        assertEquals(listOf(localRoute), offline.routesFor("de-DE"))
        val explicit = VoiceRoute("local", "kokoro#3")
        assertEquals(listOf(explicit, edgeRoute, localRoute), online.routesFor("de-DE", explicit))
        assertEquals("unknown language still gets a local voice", listOf(VoiceRoute("local", "piper-en")),
            RoutePlanner(profiles, true) { if (it.startsWith("en")) "piper-en" else null }.routesFor("en-US"))
    }

    @Test
    fun `section languages are closed-world with context inheritance`() {
        val detect: (String) -> Map<String, Float>? = { t ->
            when {
                t.contains("Hallo") -> mapOf("de" to 0.95f)
                t.contains("Bonjour") -> mapOf("fr" to 0.99f)
                else -> mapOf("en" to 0.9f)
            }
        }
        val sl = SectionLanguages(detect)
        val sections = listOf(
            "Hallo zusammen, das ist ein deutscher Satz mit genug Zeichen.",
            "OK.",
            "This is an English sentence that is long enough to move the context.",
            "Bonjour tout le monde, ceci est une phrase française assez longue.",
        )
        val out = sl.assign(sections, listOf("de-DE", "en-US"), "de-DE")
        assertEquals(listOf("de-DE", "de-DE", "en-US", "en-US"), out)
        assertEquals(List(4) { "de-DE" }, sl.assign(sections, listOf("de-DE"), "de-DE"))
    }

    @Test
    fun `resampler converts length by rate ratio`() {
        val input = FloatArray(24000) { (it % 100) / 100f }
        assertEquals(22050, Resampler.resample(input, 24000, 22050).size)
        assertTrue(Resampler.resample(input, 24000, 24000) === input)
    }
}
