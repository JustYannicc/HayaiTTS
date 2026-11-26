package dev.ahmedmohamed.hayaitts.tts

/** One short sentence per language, for the framework's sample text and in-app voice tests. */
object SampleTexts {

    /** Sample for a BCP-47 tag's base language; English when there is none. */
    fun forLanguage(bcp47: String): String =
        SAMPLES[bcp47.substringBefore('-').lowercase()] ?: SAMPLES.getValue("en")

    private val SAMPLES = mapOf(
        "en" to "This is an example of speech synthesis with HayaiTTS.",
        "de" to "Dies ist ein Beispiel für Sprachsynthese mit HayaiTTS.",
        "fr" to "Ceci est un exemple de synthèse vocale avec HayaiTTS.",
        "es" to "Este es un ejemplo de síntesis de voz con HayaiTTS.",
        "it" to "Questo è un esempio di sintesi vocale con HayaiTTS.",
        "pt" to "Este é um exemplo de síntese de voz com o HayaiTTS.",
        "nl" to "Dit is een voorbeeld van spraaksynthese met HayaiTTS.",
        "ru" to "Это пример синтеза речи с помощью HayaiTTS.",
        "zh" to "这是使用 HayaiTTS 进行语音合成的示例。",
        "ja" to "これは HayaiTTS による音声合成の例です。",
        "ko" to "HayaiTTS를 사용한 음성 합성의 예입니다.",
        "ar" to "هذا مثال على تحويل النص إلى كلام باستخدام HayaiTTS.",
    )
}
