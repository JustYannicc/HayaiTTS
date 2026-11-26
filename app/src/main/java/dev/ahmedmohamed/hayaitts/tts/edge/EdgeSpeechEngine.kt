package dev.ahmedmohamed.hayaitts.tts.edge

import co.touchlab.kermit.Logger
import dev.ahmedmohamed.hayaitts.tts.engine.EngineAudio
import dev.ahmedmohamed.hayaitts.tts.engine.EngineFailure
import dev.ahmedmohamed.hayaitts.tts.engine.EngineKind
import dev.ahmedmohamed.hayaitts.tts.engine.EngineUnavailable
import dev.ahmedmohamed.hayaitts.tts.engine.EngineVoice
import dev.ahmedmohamed.hayaitts.tts.engine.SpeechEngine
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Microsoft Edge "Read Aloud" voices over the browser's WebSocket API.
 * MP3 out, decoded to 24 kHz float PCM by [decoder]. Every failure to reach or
 * be accepted by the service maps to [EngineUnavailable] quickly (4 s connect
 * budget) so the router can fall back and bench the engine.
 *
 * Latency: a handshake costs ~120 ms, so sockets are reused. [prewarm] opens
 * one ahead of time, and after each turn the socket goes back into a one-slot
 * idle pool for [IDLE_LIFETIME_MS]. A reused socket that doesn't answer within
 * [STALE_AFTER_MS] is replaced by a fresh one transparently.
 *
 * Thread-safe: concurrent calls each get their own socket; only the pool is shared.
 * Cancellation (via `isCancelled`) throws [CancellationException]. Meant to be
 * long-lived (one per process); [close] releases its threads and socket.
 */
class EdgeSpeechEngine(
    okHttp: OkHttpClient,
    cacheDir: File,
    private val decoder: AudioDecoder = MediaCodecMp3Decoder(),
    private val endpoints: List<EdgeEndpoint> = EdgeEndpointV1.ALL,
    private val connectTimeoutMs: Long = 4_000,
    private val handshakeAttempts: Int = 6,
) : SpeechEngine {

    override val kind: EngineKind = EngineKind.EDGE

    private val log = Logger.withTag("EdgeSpeechEngine")

    /** Warm-ups and idle expiry. Voice-list fetches get their own thread so they never delay a warm-up. */
    private val pool = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "edge-tts").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }
    private val catalogThread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "edge-tts-voices").apply { isDaemon = true }
    }

    /**
     * Fresh TCP connection per handshake: a pooled keep-alive connection after a
     * 403 would pin retries to the same rejecting front-door node. No cookies
     * (we send our own muid); our own watchdogs own the deadlines.
     */
    private val socketClient: OkHttpClient = okHttp.newBuilder()
        .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
        .cookieJar(CookieJar.NO_COOKIES)
        .retryOnConnectionFailure(false)
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .build()

    internal val catalog = EdgeVoiceCatalog(okHttp, File(cacheDir, "edge-voices.json"), catalogThread, endpoints)

    private val lock = Any()
    private var preferred = 0
    private var idle: EdgeConnection? = null
    private var idleExpiry: ScheduledFuture<*>? = null
    private var warming: CompletableFuture<EdgeConnection>? = null

    @Volatile private var voiceCache: Pair<List<EdgeVoice>, List<EngineVoice>>? = null

    /** Endpoint that served the last successful turn (diagnostics). */
    @Volatile var lastEndpoint: String? = null
        private set

    private enum class Source(val reused: Boolean) { FRESH(false), IDLE(true), WARMING(true), STALE_RETRY(false) }

    init {
        require(endpoints.isNotEmpty())
    }

    override fun voices(): List<EngineVoice> {
        val source = catalog.voices()
        voiceCache?.let { (from, mapped) -> if (from === source) return mapped }
        val mapped = source.map {
            EngineVoice(EngineKind.EDGE, it.shortName, it.displayName, it.locale, requiresNetwork = true)
        }
        voiceCache = source to mapped
        return mapped
    }

    /** Plain default voice for a BCP-47 tag (exact locale, else same language), or null. */
    fun defaultVoiceFor(languageTag: String): String? = EdgeVoiceCatalog.defaultVoiceFor(catalog.voices(), languageTag)

    /** Opens a socket now so the next [synthesize] skips the handshake. Cheap to call repeatedly; never throws. */
    override fun prewarm() {
        val warmup = CompletableFuture<EdgeConnection>()
        synchronized(lock) {
            idle?.let { if (isUsable(it)) return; takeIdleLocked()?.close() }
            if (warming != null) return
            warming = warmup
        }
        pool.execute {
            val connection = try {
                connect { false }
            } catch (e: Exception) {
                // Don't leave a failed warm-up for a later synthesize to trip over.
                synchronized(lock) { if (warming === warmup) warming = null }
                warmup.completeExceptionally(e)
                return@execute
            }
            // Hand over to the idle slot in one step, unless a synthesize already adopted the warm-up.
            val parked = synchronized(lock) {
                if (warming === warmup) { warming = null; parkLocked(connection) } else null
            }
            warmup.complete(connection)
            if (parked == false) connection.close()
        }
        catalog.voices() // kicks a list refresh if stale
    }

    override fun synthesize(
        voiceId: String,
        text: String,
        language: String,
        speed: Float,
        pitch: Float,
        isCancelled: () -> Boolean,
    ): EngineAudio {
        if (!EdgeProtocol.isSpeakable(text)) throw EngineFailure("Nothing speakable in text")
        val ssml = EdgeProtocol.ssml(text, voiceId, language, speed, pitch)
        val timeoutMs = turnTimeoutMs(text.length)
        val start = System.nanoTime()

        var (connection, source) = checkOut(isCancelled)
        val mp3 = try {
            try {
                connection.speak(ssml, timeoutMs, STALE_AFTER_MS.takeIf { source.reused }, isCancelled)
            } catch (stale: EdgeConnection.TurnFailure) {
                if (!stale.stale || !source.reused) throw stale
                // The kept socket died quietly (sleep, network change, server close). Nothing was
                // spoken yet, so start over on a fresh one.
                log.i { "reused socket stale (${stale.message}); reconnecting" }
                connection.close()
                connection = connect(isCancelled)
                source = Source.STALE_RETRY
                connection.speak(ssml, timeoutMs, null, isCancelled)
            }
        } catch (e: Exception) {
            connection.close()
            throw (e as? EdgeConnection.TurnFailure)?.error ?: e
        }
        lastEndpoint = connection.endpointName
        checkIn(connection)

        val audio = try {
            decoder.decode(mp3)
        } catch (e: Exception) {
            throw EngineFailure("Edge TTS audio could not be decoded (${mp3.size} bytes): ${e.message}", e)
        }
        if (audio.samples.isEmpty()) throw EngineFailure("Edge TTS audio decoded to nothing")
        val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        log.d { "synthesize ${text.length} chars [$voiceId] $source via ${connection.endpointName}: $ms ms" }
        return audio
    }

    /** Closes the pooled socket and stops background threads. The engine is unusable afterwards. */
    fun close() {
        val (c, w) = synchronized(lock) { takeIdleLocked() to warming.also { warming = null } }
        c?.close()
        w?.thenAccept(EdgeConnection::close)
        pool.shutdownNow()
        catalogThread.shutdownNow()
    }

    // Pool

    private fun isUsable(c: EdgeConnection) = c.isAlive && c.idleMillis() < IDLE_LIFETIME_MS

    private fun takeIdleLocked(): EdgeConnection? {
        idleExpiry?.cancel(false)
        idleExpiry = null
        return idle.also { idle = null }
    }

    /** An idle socket, else the warm-up in flight, else a fresh connection. */
    private fun checkOut(isCancelled: () -> Boolean): Pair<EdgeConnection, Source> {
        val warm = synchronized(lock) {
            takeIdleLocked()?.let { c -> if (isUsable(c)) return c to Source.IDLE else c.close() }
            warming.also { warming = null }
        } ?: return connect(isCancelled) to Source.FRESH
        // A warm-up that failed already did the endpoint rotation; retrying would double the latency.
        while (true) {
            if (isCancelled()) {
                warm.thenAccept(EdgeConnection::close)
                throw CancellationException("Edge TTS synthesis cancelled")
            }
            try {
                return warm.get(100, TimeUnit.MILLISECONDS) to Source.WARMING
            } catch (_: TimeoutException) {
                continue
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
    }

    /** Puts [connection] in the idle slot (keeping the fresher socket). Returns false if it should be closed. */
    private fun parkLocked(connection: EdgeConnection): Boolean {
        if (!connection.isAlive) return false
        takeIdleLocked()?.close()
        idle = connection
        idleExpiry = pool.schedule({
            synchronized(lock) { if (idle === connection) takeIdleLocked() else null }?.close()
        }, IDLE_LIFETIME_MS, TimeUnit.MILLISECONDS)
        return true
    }

    private fun checkIn(connection: EdgeConnection) {
        if (!synchronized(lock) { parkLocked(connection) }) connection.close()
    }

    /**
     * Handshake with endpoint rotation starting from the last good endpoint.
     * Rejections (403/429) and fast failures move on to the next; all attempts
     * share one [connectTimeoutMs] budget.
     */
    private fun connect(isCancelled: () -> Boolean): EdgeConnection {
        val first = synchronized(lock) { preferred }
        val start = System.nanoTime()
        val failures = mutableListOf<String>()
        for (i in 0 until maxOf(1, handshakeAttempts)) {
            if (isCancelled()) throw CancellationException("Edge TTS synthesis cancelled")
            val remaining = connectTimeoutMs - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            if (remaining <= 0) break
            val index = (first + i) % endpoints.size
            val endpoint = endpoints[index]
            try {
                val connection = EdgeConnection.open(socketClient, endpoint, remaining)
                synchronized(lock) { preferred = index }
                if (failures.isNotEmpty()) log.i { "connected via ${endpoint.name} after $failures" }
                return connection
            } catch (rejected: EdgeConnection.HandshakeRejected) {
                failures += "${endpoint.name}: HTTP ${rejected.status}"
                // A wrong device clock produces a stale token: re-anchor before the next try.
                rejected.serverDate?.let(EdgeClock::adjust)
                // 429 = handshake throttling. Measured 2026-11-26 on api.msedgeservices.com: roughly
                // 1 in 3 handshakes from one IP, no Retry-After, worse right after a 1007 close; a
                // short pause and another try usually gets through within the budget.
                if (rejected.status == 429) Thread.sleep(THROTTLE_BACKOFF_MS)
            } catch (unreachable: EdgeConnection.Unreachable) {
                failures += "${endpoint.name}: ${unreachable.message}"
            } catch (timedOut: EdgeConnection.ConnectTimedOut) {
                // It used the rest of the budget.
                failures += "${endpoint.name}: ${timedOut.message}"
                break
            }
        }
        throw EngineUnavailable("Edge TTS unavailable (${failures.joinToString(", ").ifEmpty { "connect timed out" }})")
    }

    companion object {
        /** How long an unused socket is kept (the server allows 300 s). */
        const val IDLE_LIFETIME_MS = 60_000L

        /** A reused socket must send its first frame (normally ~50 ms) within this. */
        const val STALE_AFTER_MS = 1_500L

        private const val THROTTLE_BACKOFF_MS = 300L

        /** Budget for one turn once connected; only trips when the service stalls. */
        fun turnTimeoutMs(textLength: Int): Long = 6_000L + textLength * 40L
    }
}
