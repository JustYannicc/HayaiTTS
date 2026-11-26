package dev.ahmedmohamed.hayaitts.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTitlesTest {

    private fun t(id: String, vararg langs: String) = VoiceTitles.display(id, "Speaker_0", langs.toList())

    @Test
    fun `anonymous speaker titles are rebuilt from the bundle id`() {
        assertEquals("Coqui CSS10", t("vits-coqui-de-css10", "de"))
        assertEquals("Coqui LJSpeech", t("vits-coqui-en-ljspeech", "en"))
        assertEquals("MMS", t("vits-mms-deu", "de-DE"))
        assertEquals("Khadijah", t("matcha-tts-fa_en-khadijah", "fa", "en"))
        assertEquals("Pocket", t("sherpa-onnx-pocket-tts-2026-01-26", "en"))
        assertEquals("Pocket (int8)", t("sherpa-onnx-pocket-tts-int8-2026-01-26", "en"))
        assertEquals("ZipVoice Emilia (distill, int8)", t("sherpa-onnx-zipvoice-distill-int8-zh-en-emilia", "zh", "en"))
        assertEquals("Supertonic (int8)", t("sherpa-onnx-supertonic-tts-int8-2026-03-06", "en"))
        assertEquals("MeloTTS", t("vits-melo-tts-en", "en"))
        assertEquals("VCTK", t("vits-vctk", "en"))
        assertEquals("Coqui Custom Female", t("vits-coqui-bn-custom_female", "bn"))
        assertEquals("Southern English Female", t("vits-piper-en_GB-southern_english_female_medium", "en-GB"))
        assertEquals("Fettah (int8)", t("vits-piper-tr_TR-fettah-medium-int8", "tr-TR"))
        assertEquals("LJSpeech", t("vits-icefall-en_US-ljspeech-medium", "en-US"))
    }

    @Test
    fun `short real names survive`() {
        assertEquals("Sweetbbak Amy", t("vits-piper-en_GB-sweetbbak-amy", "en-GB"))
        assertEquals("Coqui Mai", t("vits-coqui-uk-mai", "uk"))
        assertEquals("Fanchen Wnj", t("vits-zh-hf-fanchen-wnj", "zh"))
    }

    @Test
    fun `real titles are kept`() {
        assertEquals("Amy", VoiceTitles.display("vits-piper-en_US-amy-low", "Amy", listOf("en-US")))
        assertEquals("Supertonic (10 speakers)", VoiceTitles.display("x", "Supertonic (10 speakers)", listOf("en")))
    }
}
