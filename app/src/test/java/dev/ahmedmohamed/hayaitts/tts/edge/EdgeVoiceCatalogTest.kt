package dev.ahmedmohamed.hayaitts.tts.edge

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.util.concurrent.Executor

class EdgeVoiceCatalogTest {

    @get:Rule val tmp = TemporaryFolder()

    private val sample = """
        [
          {"Name":"Microsoft Server Speech Text to Speech Voice (de-DE, KatjaNeural)","ShortName":"de-DE-KatjaNeural",
           "Gender":"Female","Locale":"de-DE","SuggestedCodec":"audio-24khz-48kbitrate-mono-mp3",
           "FriendlyName":"Microsoft Katja Online (Natural) - German (Germany)","Status":"GA",
           "VoiceTag":{"ContentCategories":["General"],"VoicePersonalities":["Friendly"]}},
          {"ShortName":"de-DE-SeraphinaMultilingualNeural","Gender":"Female","Locale":"de-DE"},
          {"ShortName":"de-DE-ChristophNeural","Gender":"Male","Locale":"de-DE"},
          {"ShortName":"de-CH-LeniNeural","Gender":"Female","Locale":"de-CH"},
          {"ShortName":"en-US-AvaMultilingualNeural","Gender":"Female","Locale":"en-US"},
          {"ShortName":"en-US-AriaNeural","Gender":"Female","Locale":"en-US"},
          {"ShortName":"fr-CA-SylvieNeural","Gender":"Female","Locale":"fr-CA"},
          {"ShortName":"zh-CN-liaoning-XiaobeiNeural","Gender":"Female","Locale":"zh-CN-liaoning"},
          {"ShortName":"","Locale":"xx-XX"}
        ]
    """.trimIndent()

    @Test
    fun `parse - reads service json, ignores unknown fields, drops blank entries`() {
        val voices = EdgeVoiceCatalog.parseVoiceList(sample)
        assertEquals(8, voices.size)
        val katja = voices.first()
        assertEquals(EdgeVoice("de-DE-KatjaNeural", "de-DE", "Female"), katja)
        assertEquals("Katja · Female", katja.displayName)
        assertEquals("Seraphina (Multilingual) · Female", voices[1].displayName)
        assertTrue(voices[1].isMultilingual)
        assertEquals("Xiaobei · Female", voices.last().displayName)
    }

    @Test
    fun `default voice - exact locale prefers hand-picked plain voice`() {
        val voices = EdgeVoiceCatalog.parseVoiceList(sample)
        assertEquals("de-DE-KatjaNeural", EdgeVoiceCatalog.defaultVoiceFor(voices, "de-DE"))
        assertEquals("de-CH-LeniNeural", EdgeVoiceCatalog.defaultVoiceFor(voices, "de_CH"))
        // en-US-AvaNeural isn't listed: fall back to the plain voice over the Multilingual one.
        assertEquals("en-US-AriaNeural", EdgeVoiceCatalog.defaultVoiceFor(voices, "en-US"))
    }

    @Test
    fun `default voice - missing region falls back to preferred region of base language`() {
        val voices = EdgeVoiceCatalog.parseVoiceList(sample)
        assertEquals("de-DE-KatjaNeural", EdgeVoiceCatalog.defaultVoiceFor(voices, "de-LI"))
        assertEquals("de-DE-KatjaNeural", EdgeVoiceCatalog.defaultVoiceFor(voices, "de"))
        assertEquals("fr-CA-SylvieNeural", EdgeVoiceCatalog.defaultVoiceFor(voices, "fr-FR"))
        assertNull(EdgeVoiceCatalog.defaultVoiceFor(voices, "ko-KR"))
    }

    @Test
    fun `preferred defaults - missing hand pick is added for covered locales only`() {
        val live = listOf(EdgeVoice("de-DE-TanjaNeural", "de-DE", "Female"), EdgeVoice("de-DE-ChristophNeural", "de-DE", "Male"))
        val merged = EdgeVoiceCatalog.withPreferredDefaults(live)
        assertEquals(live + EdgeVoice("de-DE-KatjaNeural", "de-DE", "Female"), merged)
        assertEquals("de-DE-KatjaNeural", EdgeVoiceCatalog.defaultVoiceFor(merged, "de-DE"))
        assertEquals(merged, EdgeVoiceCatalog.withPreferredDefaults(merged))
    }

    @Test
    fun `default voice - without hand pick prefers plain over multilingual`() {
        val voices = listOf(
            EdgeVoice("pl-PL-AMultilingualNeural", "pl-PL", "Female"),
            EdgeVoice("pl-PL-ZofiaNeural", "pl-PL", "Female"),
        )
        assertEquals("pl-PL-ZofiaNeural", EdgeVoiceCatalog.defaultVoiceFor(voices, "pl-PL"))
    }

    @Test
    fun `fallback - covers required locales with plain voices and their defaults`() {
        val required = listOf("en-US", "en-GB", "de-DE", "de-CH", "fr-FR", "es-ES", "it-IT", "pt-BR", "nl-NL", "ja-JP", "zh-CN")
        for (locale in required) {
            val default = EdgeVoiceCatalog.defaultVoiceFor(EdgeVoiceCatalog.FALLBACK, locale)
            assertEquals(EdgeVoiceCatalog.PREFERRED_DEFAULTS[locale], default)
        }
        assertTrue(EdgeVoiceCatalog.FALLBACK.none { it.isMultilingual })
        assertEquals("de-DE-KatjaNeural", EdgeVoiceCatalog.defaultVoiceFor(EdgeVoiceCatalog.FALLBACK, "de-DE"))
    }

    private class UnreachableEndpoint : EdgeEndpoint {
        override val name = "test"
        override fun synthesisRequest(connectionId: String, now: Instant): Request = error("unused")
        override fun voiceListRequest(now: Instant): Request = Request.Builder().url("http://127.0.0.1:1/voices").build()
    }

    @Test
    fun `catalog - no cache and network down - serves fallback without blocking and backs off`() {
        val queued = mutableListOf<Runnable>()
        var now = 1_000L
        val catalog = EdgeVoiceCatalog(
            OkHttpClient(), tmp.root.resolve("v.json"), Executor { queued += it }, listOf(UnreachableEndpoint()), { now },
        )
        assertEquals(EdgeVoiceCatalog.FALLBACK, catalog.voices())
        assertEquals(1, queued.size) // refresh scheduled, not run inline
        queued.removeAt(0).run()
        assertEquals(EdgeVoiceCatalog.FALLBACK, catalog.voices())
        assertEquals(0, queued.size) // backing off after the failure
        now += EdgeVoiceCatalog.RETRY_AFTER_MS
        catalog.voices()
        assertEquals(1, queued.size)
    }

    @Test
    fun `catalog - fresh disk cache - served without refresh`() {
        val file = tmp.root.resolve("v.json")
        file.writeText("""{"fetchedAt":1000,"voices":[{"ShortName":"de-DE-KatjaNeural","Locale":"de-DE","Gender":"Female"}]}""")
        val queued = mutableListOf<Runnable>()
        val catalog = EdgeVoiceCatalog(OkHttpClient(), file, Executor { queued += it }, listOf(UnreachableEndpoint()), { 2_000L })
        assertEquals(listOf(EdgeVoice("de-DE-KatjaNeural", "de-DE", "Female")), catalog.voices())
        assertEquals(0, queued.size)

        val expired = EdgeVoiceCatalog(
            OkHttpClient(), file, Executor { queued += it }, listOf(UnreachableEndpoint()),
            { 1_000L + EdgeVoiceCatalog.CACHE_TTL_MS },
        )
        assertEquals(1, expired.voices().size) // stale cache still served
        assertEquals(1, queued.size)
    }
}
