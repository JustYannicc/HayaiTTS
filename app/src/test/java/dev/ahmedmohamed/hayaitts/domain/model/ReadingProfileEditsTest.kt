package dev.ahmedmohamed.hayaitts.domain.model

import dev.ahmedmohamed.hayaitts.domain.model.ReadingProfileEdits.addLanguage
import dev.ahmedmohamed.hayaitts.domain.model.ReadingProfileEdits.addRoute
import dev.ahmedmohamed.hayaitts.domain.model.ReadingProfileEdits.moveLanguage
import dev.ahmedmohamed.hayaitts.domain.model.ReadingProfileEdits.moveRoute
import dev.ahmedmohamed.hayaitts.domain.model.ReadingProfileEdits.removeLanguage
import dev.ahmedmohamed.hayaitts.domain.model.ReadingProfileEdits.removeRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ReadingProfileEditsTest {

    private val edge = VoiceRoute(VoiceRoute.ENGINE_EDGE, "de-DE-KatjaNeural")
    private val piper = VoiceRoute(VoiceRoute.ENGINE_LOCAL, "piper-de")
    private val de = LanguageProfile("de-DE", listOf(edge, VoiceRoute.LocalAuto, piper))
    private val en = LanguageProfile("en-US", listOf(VoiceRoute.LocalAuto))
    private val profiles = listOf(de, en)

    @Test
    fun `addLanguage - new tag - appended with given routes`() {
        val out = addLanguage(profiles, "fr-FR", listOf(VoiceRoute.LocalAuto))
        assertEquals(listOf("de-DE", "en-US", "fr-FR"), out.map { it.language })
        assertEquals(listOf(VoiceRoute.LocalAuto), out.last().routes)
    }

    @Test
    fun `addLanguage - tag already present in other case - unchanged`() {
        assertSame(profiles, addLanguage(profiles, "EN-us", listOf(VoiceRoute.LocalAuto)))
    }

    @Test
    fun `addLanguage - no routes - falls back to local auto`() {
        assertEquals(listOf(VoiceRoute.LocalAuto), addLanguage(emptyList(), "ja-JP", emptyList()).single().routes)
    }

    @Test
    fun `removeLanguage - removes only that language`() {
        assertEquals(listOf(en), removeLanguage(profiles, "de-DE"))
    }

    @Test
    fun `moveLanguage - down then clamped at end`() {
        val down = moveLanguage(profiles, "de-DE", 1)
        assertEquals(listOf("en-US", "de-DE"), down.map { it.language })
        assertSame(down, moveLanguage(down, "de-DE", 1))
    }

    @Test
    fun `moveRoute - up swaps with previous route`() {
        val out = moveRoute(profiles, "de-DE", 2, -1)
        assertEquals(listOf(edge, piper, VoiceRoute.LocalAuto), out.first().routes)
        assertEquals(en, out.last())
    }

    @Test
    fun `moveRoute - first route up - unchanged`() {
        assertEquals(de.routes, moveRoute(profiles, "de-DE", 0, -1).first().routes)
    }

    @Test
    fun `removeRoute - drops the index`() {
        assertEquals(listOf(edge, piper), removeRoute(profiles, "de-DE", 1).first().routes)
    }

    @Test
    fun `removeRoute - last remaining route - kept`() {
        assertEquals(en.routes, removeRoute(profiles, "en-US", 0).last().routes)
    }

    @Test
    fun `addRoute - appends once, duplicate ignored`() {
        val once = addRoute(profiles, "en-US", piper)
        assertEquals(listOf(VoiceRoute.LocalAuto, piper), once.last().routes)
        assertEquals(once, addRoute(once, "en-US", piper))
    }
}
