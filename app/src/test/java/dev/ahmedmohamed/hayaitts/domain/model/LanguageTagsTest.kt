package dev.ahmedmohamed.hayaitts.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageTagsTest {

    private val enDe = setOf("en", "de")

    @Test
    fun `regional tags match a base-language selection`() {
        assertTrue(LanguageTags.voiceIn(listOf("en-US"), enDe))
        assertTrue(LanguageTags.voiceIn(listOf("de_DE"), enDe))
        assertTrue(LanguageTags.voiceIn(listOf("en-GB"), setOf("en-US")))
    }

    @Test
    fun `bilingual voices count only for their main language`() {
        assertFalse(LanguageTags.voiceIn(listOf("fa", "en"), enDe))
        assertFalse(LanguageTags.voiceIn(listOf("zh-CN", "en-US"), enDe))
        assertTrue(LanguageTags.voiceIn(listOf("en", "zh"), enDe))
    }

    @Test
    fun `broadly multilingual voices count if they speak any selected language`() {
        assertTrue(LanguageTags.voiceIn(listOf("ar", "bg", "de-DE", "en-US"), enDe))
        assertFalse(LanguageTags.voiceIn(listOf("ar", "bg", "cs"), enDe))
    }

    @Test
    fun `empty selection means no restriction`() = assertTrue(LanguageTags.voiceIn(listOf("fa"), emptySet()))

    @Test
    fun `chips match any language by base`() {
        assertTrue(LanguageTags.speaksAny(listOf("fa", "en"), setOf("en")))
        assertEquals("en", LanguageTags.base("EN_us"))
    }
}

class ReconcileTest {
    private val katja = VoiceRoute("edge", "de-DE-KatjaNeural")

    @Test
    fun `allowed languages add missing profiles and drop removed ones, keeping order and routes`() {
        val en = LanguageProfile("en-US", listOf(VoiceRoute("edge", "en-US-AndrewNeural"), VoiceRoute.LocalAuto))
        val fr = LanguageProfile("fr-FR", listOf(VoiceRoute.LocalAuto))
        val out = ReadingProfileEdits.reconcile(listOf(en, fr), setOf("en", "de")) { LanguageProfile("$it-DE", listOf(katja)) }
        assertEquals(listOf(en, LanguageProfile("de-DE", listOf(katja))), out)
    }

    @Test
    fun `nothing changes when profiles already match`() {
        val en = LanguageProfile("en-US", listOf(VoiceRoute.LocalAuto))
        assertEquals(listOf(en), ReadingProfileEdits.reconcile(listOf(en), setOf("en")) { error("no new profile expected") })
    }
}
