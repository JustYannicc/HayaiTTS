package dev.ahmedmohamed.hayaitts.data.routing

import android.content.Context
import dev.ahmedmohamed.hayaitts.core.dispatchers.DispatcherProvider
import dev.ahmedmohamed.hayaitts.core.result.AppError
import dev.ahmedmohamed.hayaitts.core.result.Outcome
import dev.ahmedmohamed.hayaitts.core.result.runCatchingOutcome
import dev.ahmedmohamed.hayaitts.data.preview.VoicePreviewPlayer
import dev.ahmedmohamed.hayaitts.domain.model.LanguageProfile
import dev.ahmedmohamed.hayaitts.domain.model.VoiceRoute
import dev.ahmedmohamed.hayaitts.domain.repo.RouteVoice
import dev.ahmedmohamed.hayaitts.domain.repo.SpeechEnginesGateway
import dev.ahmedmohamed.hayaitts.domain.repo.SpokenSample
import dev.ahmedmohamed.hayaitts.tts.SampleTexts
import dev.ahmedmohamed.hayaitts.tts.TtsLocales
import dev.ahmedmohamed.hayaitts.tts.engine.EngineKind
import dev.ahmedmohamed.hayaitts.tts.engine.EngineUnavailable
import dev.ahmedmohamed.hayaitts.tts.engine.Engines
import dev.ahmedmohamed.hayaitts.tts.engine.ReadingDefaults
import dev.ahmedmohamed.hayaitts.tts.engine.RoutePlanner
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** [SpeechEnginesGateway] over the process-wide [Engines] and the shared preview player. */
class SpeechEnginesGatewayImpl(
    private val context: Context,
    private val player: VoicePreviewPlayer,
    private val dispatchers: DispatcherProvider,
) : SpeechEnginesGateway {

    private val engines: Engines get() = Engines.get(context)

    override val onlineAvailable: Boolean get() = engines.edge != null

    override suspend fun voices(): List<RouteVoice> = withContext(dispatchers.default) {
        val local = engines.local.installed().flatMap { v ->
            val languages = v.languages.map { TtsLocales.toBcp47(it) }.filter { it.isNotBlank() }.distinct()
            if (v.speakers.size > 1) {
                v.speakers.map { s -> RouteVoice(VoiceRoute(VoiceRoute.ENGINE_LOCAL, "${v.voiceId}#${s.id}"), "${v.title} · ${s.name}", languages) }
            } else {
                listOf(RouteVoice(VoiceRoute(VoiceRoute.ENGINE_LOCAL, v.voiceId), v.title, languages))
            }
        }
        val online = engines.edge?.engine?.voices().orEmpty().map {
            RouteVoice(VoiceRoute(VoiceRoute.ENGINE_EDGE, it.id), it.displayName, listOf(it.locale))
        }
        local + online
    }

    override fun defaultRoutesFor(language: String): List<VoiceRoute> =
        ReadingDefaults.defaultsFor(listOf(language), engines.edge).first().routes

    override fun isOnlineBenched(): Boolean = engines.edge != null && engines.router.isBenched(EngineKind.EDGE)

    override fun resetHealth() = engines.router.resetHealth()

    override suspend fun synthesizeSample(profile: LanguageProfile, onlineEnabled: Boolean): Outcome<SpokenSample> =
        withContext(dispatchers.default) {
            val ctx = currentCoroutineContext()
            runCatchingOutcome(classify = ::classify) {
                val local = engines.local
                val planner = RoutePlanner(listOf(profile), onlineEnabled && engines.edge != null) { lang -> local.bestVoiceFor(lang)?.voiceId }
                val routes = planner.routesFor(profile.language)
                val text = SampleTexts.forLanguage(profile.language)
                val routed = engines.router.synthesize(routes, text, profile.language, 1f, 1f) { !ctx.isActive }
                val name = voices().firstOrNull { it.route == routed.route }?.displayName ?: routed.route.voiceId
                SpokenSample(routed.route, name, routed.audio.samples, routed.audio.sampleRate)
            }
        }

    override suspend fun play(sample: SpokenSample) = player.playSamples(sample.samples, sample.sampleRate)

    override fun stopPlayback() = player.stop()

    private fun classify(t: Throwable): AppError = when (t) {
        is EngineUnavailable -> AppError.Network
        else -> AppError.Unknown(t.message ?: t.javaClass.simpleName)
    }
}
