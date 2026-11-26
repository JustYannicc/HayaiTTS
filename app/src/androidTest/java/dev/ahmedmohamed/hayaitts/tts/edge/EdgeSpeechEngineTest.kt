package dev.ahmedmohamed.hayaitts.tts.edge

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ahmedmohamed.hayaitts.tts.engine.EngineAudio
import dev.ahmedmohamed.hayaitts.tts.engine.EngineFailure
import dev.ahmedmohamed.hayaitts.tts.engine.EngineUnavailable
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Real network: talks to the live Edge Read Aloud service. */
@RunWith(AndroidJUnit4::class)
class EdgeSpeechEngineTest {

    private val engine get() = shared
    private val cacheDir get() = sharedCacheDir

    /** Seconds of audio louder than ~-46 dBFS in 10 ms windows. */
    private fun voicedSeconds(audio: EngineAudio): Double {
        val window = audio.sampleRate / 100
        var voiced = 0
        for (w in 0 until audio.samples.size / window) {
            var peak = 0f
            for (i in w * window until (w + 1) * window) peak = maxOf(peak, abs(audio.samples[i]))
            if (peak > 0.005f) voiced++
        }
        return voiced / 100.0
    }

    private fun speak(voice: String, text: String, speed: Float = 1f, pitch: Float = 1f): EngineAudio {
        val start = System.nanoTime()
        val audio = engine.synthesize(voice, text, voice.split('-').take(2).joinToString("-"), speed, pitch) { false }
        val ms = (System.nanoTime() - start) / 1_000_000
        Log.i(TAG, "$voice speed=$speed pitch=$pitch: ${audio.samples.size} samples @ ${audio.sampleRate} Hz, " +
            "voiced ${voicedSeconds(audio)} s, $ms ms via ${engine.lastEndpoint}")
        return audio
    }

    /**
     * The service throttles quick reconnects from one IP (HTTP 429 on the handshake). That's the
     * service, not the engine (which correctly reports EngineUnavailable), so allow a few pauses.
     */
    private fun speakAllowingThrottle(voice: String, text: String): EngineAudio {
        repeat(3) {
            try {
                return speak(voice, text)
            } catch (e: EngineUnavailable) {
                if ("429" !in e.message.orEmpty()) throw e
                Log.i(TAG, "reconnect throttled, retrying: ${e.message}")
                Thread.sleep(3_000)
            }
        }
        return speak(voice, text)
    }

    @Test
    fun synthesizesGermanWithKatja() {
        val audio = speak("de-DE-KatjaNeural", "Hallo, das ist ein Test.")
        assertEquals(24_000, audio.sampleRate)
        assertTrue("audio too short: ${voicedSeconds(audio)} s voiced", voicedSeconds(audio) > 0.5)
        assertTrue(audio.samples.all { it in -1f..1f })

        // Second turn reuses the pooled socket.
        val again = speak("de-DE-KatjaNeural", "Und noch ein zweiter Satz.")
        assertTrue(voicedSeconds(again) > 0.5)
    }

    @Test
    fun prosodyChangesDuration() {
        val text = "Dies ist ein etwas längerer Satz, um die Sprechgeschwindigkeit zu messen."
        val normal = speak("de-DE-KatjaNeural", text).samples.size
        val fast = speak("de-DE-KatjaNeural", text, speed = 2f, pitch = 1.2f).samples.size
        val slow = speak("de-DE-KatjaNeural", text, speed = 0.5f, pitch = 0.8f).samples.size
        assertTrue("fast $fast vs normal $normal", fast < normal * 0.75)
        assertTrue("slow $slow vs normal $normal", slow > normal * 1.4)
        // Extremes are clamped into the range the service honours (2× max).
        val extreme = speak("de-DE-KatjaNeural", text, speed = 10f, pitch = 3f).samples.size
        assertTrue("extreme $extreme vs fast $fast", extreme in (fast * 0.9).toInt()..(fast * 1.1).toInt())
    }

    @Test
    fun prewarmedSocketIsAdopted() {
        val fresh = EdgeSpeechEngine(OkHttpClient(), sharedCacheDir)
        try {
            fresh.prewarm()
            fresh.prewarm() // no-op while warming
            val audio = fresh.synthesize("de-DE-KatjaNeural", "Vorgewärmt.", "de-DE", 1f, 1f) { false }
            assertTrue(voicedSeconds(audio) > 0.3)
        } finally {
            fresh.close()
        }
    }

    @Test
    fun concurrentCallsBothSucceed() {
        val results = arrayOfNulls<Any>(2)
        val threads = listOf("de-DE-KatjaNeural", "de-DE-TanjaNeural").mapIndexed { i, voice ->
            Thread { results[i] = runCatching { speakAllowingThrottle(voice, "Gleichzeitig gesprochen, Nummer $i.") }.getOrElse { it } }
        }
        threads.forEach(Thread::start)
        threads.forEach { it.join(30_000) }
        results.forEach { assertTrue("got $it", it is EngineAudio && voicedSeconds(it) > 0.5) }
    }

    @Test
    fun unknownVoiceIsAVoiceFailure() {
        try {
            speak("de-DE-DoesNotExistNeural", "Hallo.")
            fail("expected EngineFailure")
        } catch (e: EngineFailure) {
            Log.i(TAG, "unknown voice -> ${e.message}")
        }
        // The engine still works afterwards.
        val after = speakAllowingThrottle("de-DE-KatjaNeural", "Wieder da.")
        assertTrue(voicedSeconds(after) > 0.3)
    }

    @Test(expected = EngineFailure::class)
    fun unspeakableTextIsAVoiceFailure() {
        speak("de-DE-KatjaNeural", " ... ")
    }

    @Test
    fun voicesEventuallyIncludeGermanFromTheNetwork() {
        assertTrue(engine.voices().any { it.locale == "de-DE" }) // fallback, immediately
        val fetched = engine.catalog.refreshNow()
        assertTrue("voice list fetch failed on every endpoint", fetched != null)
        val german = engine.voices().filter { it.locale == "de-DE" }
        Log.i(TAG, "network voices: ${fetched!!.size}; de-DE: ${german.map { it.id }}")
        assertTrue(german.isNotEmpty())
        assertTrue(File(cacheDir, "edge-voices.json").length() > 0)
        assertTrue(german.any { it.id == "de-DE-KatjaNeural" })
        Log.i(TAG, "default de-DE: ${engine.defaultVoiceFor("de-DE")}, en-US: ${engine.defaultVoiceFor("en-US")}")
    }

    private companion object {
        const val TAG = "EdgeSpeechEngineTest"

        // One engine for the class: fewer handshakes, fewer HTTP 429s from the service.
        val sharedCacheDir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "edge-test")
            .apply { deleteRecursively(); mkdirs() }
        val shared = EdgeSpeechEngine(OkHttpClient(), sharedCacheDir)
    }
}
