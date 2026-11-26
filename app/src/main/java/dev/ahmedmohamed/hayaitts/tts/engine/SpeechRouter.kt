package dev.ahmedmohamed.hayaitts.tts.engine

import java.util.concurrent.CancellationException

import dev.ahmedmohamed.hayaitts.domain.model.VoiceRoute

/**
 * Tries a language's routes in order; the first engine that produces audio
 * wins. An engine that throws [EngineUnavailable] is benched for
 * [benchMillis] so later sections don't pay its timeout again (ported from
 * Narrateify's SpeechRouter). If every route is benched, the bench is ignored
 * once rather than failing silently. A success un-benches the engine.
 */
class SpeechRouter(
    private val engines: Map<EngineKind, SpeechEngine>,
    private val now: () -> Long = System::currentTimeMillis,
    private val benchMillis: Long = 10 * 60 * 1000L,
) {
    data class Routed(val audio: EngineAudio, val route: VoiceRoute)

    private val benchedUntil = HashMap<EngineKind, Long>()

    @Synchronized
    fun isBenched(kind: EngineKind): Boolean = (benchedUntil[kind] ?: 0L) > now()

    @Synchronized
    fun resetHealth() = benchedUntil.clear()

    @Synchronized
    private fun bench(kind: EngineKind) { benchedUntil[kind] = now() + benchMillis }

    @Synchronized
    private fun unbench(kind: EngineKind) { benchedUntil.remove(kind) }

    /**
     * [routes] must already have `auto` resolved to concrete voice ids.
     * Throws the last error when nothing worked.
     */
    fun synthesize(
        routes: List<VoiceRoute>,
        text: String,
        language: String,
        speed: Float,
        pitch: Float,
        isCancelled: () -> Boolean,
    ): Routed {
        val usable = routes.filter { EngineKind.fromId(it.engine)?.let(engines::containsKey) == true }
        if (usable.isEmpty()) throw EngineFailure("No usable route for $language")
        val healthy = usable.filterNot { isBenched(EngineKind.fromId(it.engine)!!) }
        var last: Exception? = null
        for (route in healthy.ifEmpty { usable }) {
            if (isCancelled()) throw CancellationException("Cancelled")
            val kind = EngineKind.fromId(route.engine)!!
            try {
                val audio = engines.getValue(kind).synthesize(route.voiceId, text, language, speed, pitch, isCancelled)
                unbench(kind)
                return Routed(audio, route)
            } catch (e: EngineUnavailable) {
                if (!isCancelled()) bench(kind)
                last = e
            } catch (e: EngineFailure) {
                last = e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An engine bug shouldn't skip the remaining routes.
                last = EngineFailure(e.message ?: e.javaClass.simpleName, e)
            }
        }
        throw last ?: EngineFailure("No route produced audio")
    }
}
