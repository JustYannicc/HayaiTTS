package dev.ahmedmohamed.hayaitts.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/**
 * `ACTION_CHECK_TTS_DATA` handler. System Settings (and some apps) start this
 * before enabling the engine's sample/Play button; without it Settings logs
 * "Failed to check TTS data, no activity found" and stays silent.
 *
 * Reports the locales of installed voices in the ISO3 `lang-COUNTRY` form the
 * framework expects. No installed voices → FAIL, so callers can send the user
 * to install one.
 */
class CheckVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread {
            // Same language set onIsLanguageAvailable() answers for.
            val tags = runCatching { HayaiTtsService.availableLanguageTags(applicationContext) }
                .getOrDefault(emptyList())
                .mapNotNull { TtsLocales.toIso3(TtsLocales.toBcp47(it)) }
                .distinct()
            val data = Intent()
                .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, ArrayList(tags))
                .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, ArrayList())
            runOnUiThread {
                setResult(
                    if (tags.isEmpty()) TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL else TextToSpeech.Engine.CHECK_VOICE_DATA_PASS,
                    data,
                )
                finish()
            }
        }.start()
    }
}

/** `ACTION_GET_SAMPLE_TEXT` handler: a short sentence in the requested language. */
class GetSampleTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = SampleTexts.forLanguage(TtsLocales.toBcp47(intent?.getStringExtra("language")))
        setResult(TextToSpeech.LANG_AVAILABLE, Intent().putExtra("sampleText", text))
        finish()
    }
}
