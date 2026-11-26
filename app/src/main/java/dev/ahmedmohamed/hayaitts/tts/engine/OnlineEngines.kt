package dev.ahmedmohamed.hayaitts.tts.engine

import android.content.Context
import co.touchlab.kermit.Logger
import dev.ahmedmohamed.hayaitts.tts.edge.EdgeSpeechEngine
import okhttp3.OkHttpClient
import org.koin.core.context.GlobalContext
import java.io.File

/** Factory for online engines. One instance per process (see [Engines]). */
internal object OnlineEngines {
    fun create(context: Context): OnlineEngine? = runCatching {
        val http = GlobalContext.getOrNull()?.getOrNull<OkHttpClient>() ?: OkHttpClient()
        val edge = EdgeSpeechEngine(http, File(context.cacheDir, "edge").apply { mkdirs() })
        object : OnlineEngine {
            override val engine: SpeechEngine = edge
            override fun defaultVoiceFor(languageTag: String): String? = edge.defaultVoiceFor(languageTag)
        }
    }.onFailure { Logger.withTag("OnlineEngines").e(it) { "Edge engine unavailable" } }.getOrNull()
}
