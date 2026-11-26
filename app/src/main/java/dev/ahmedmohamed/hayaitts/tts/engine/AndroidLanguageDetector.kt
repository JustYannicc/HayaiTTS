package dev.ahmedmohamed.hayaitts.tts.engine

import android.content.Context
import android.os.Build
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage

/** On-device language id via the system TextClassifier (API 29+); null below that. */
class AndroidLanguageDetector(private val context: Context) : (String) -> Map<String, Float>? {
    override fun invoke(text: String): Map<String, Float>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val classifier = context.getSystemService(TextClassificationManager::class.java)?.textClassifier ?: return null
        val result = classifier.detectLanguage(TextLanguage.Request.Builder(text).build())
        return (0 until result.localeHypothesisCount).associate { i ->
            val locale = result.getLocale(i)
            locale.language.lowercase() to result.getConfidenceScore(locale)
        }
    }
}
