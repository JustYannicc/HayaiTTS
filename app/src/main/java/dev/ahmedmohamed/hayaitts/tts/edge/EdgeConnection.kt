package dev.ahmedmohamed.hayaitts.tts.edge

import dev.ahmedmohamed.hayaitts.tts.engine.EngineFailure
import dev.ahmedmohamed.hayaitts.tts.engine.EngineUnavailable
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * One open WebSocket to the service: handshake done and `speech.config` sent,
 * ready for any number of sequential SSML turns. The server closes an idle
 * socket after 300 s; one socket serves repeated turns without re-sending
 * `speech.config`.
 */
internal class EdgeConnection private constructor(val endpointName: String) : WebSocketListener() {
    private val lock = Any()
    private val opened = CountDownLatch(1)
    private var openError: Exception? = null
    private var ws: WebSocket? = null
    private var sink: LinkedBlockingQueue<Event>? = null
    private var failure: Throwable? = null
    private var closeCode: Int? = null

    @Volatile var lastUsedNanos: Long = System.nanoTime()
        private set

    private sealed interface Event {
        class Text(val text: String) : Event
        class Binary(val bytes: ByteString) : Event
        class Closed(val code: Int, val reason: String) : Event
        class Failed(val error: Throwable) : Event
    }

    /** The server answered the upgrade with an HTTP error (403 = token or front-door node rejected, 429 = throttled). */
    class HandshakeRejected(val status: Int, val serverDate: String?) : Exception("HTTP $status")

    /** No handshake answer within the budget. */
    class ConnectTimedOut(val afterMs: Long) : Exception("connect timed out after $afterMs ms")

    /** Failed fast before connecting (DNS, refused, offline). */
    class Unreachable(message: String) : Exception(message)

    /** Turn failure; [stale] = nothing came back on the socket, safe to retry on a fresh one. */
    class TurnFailure(val stale: Boolean, val error: Exception) : Exception(error.message, error)

    /** Cheap local check; a half-open socket still passes (the first-frame deadline catches that). */
    val isAlive: Boolean get() = synchronized(lock) { failure == null && closeCode == null && ws != null }

    fun idleMillis(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastUsedNanos)

    fun close() {
        synchronized(lock) { ws }?.cancel()
    }

    // WebSocketListener (OkHttp reader thread)

    override fun onOpen(webSocket: WebSocket, response: Response) {
        synchronized(lock) { ws = webSocket }
        webSocket.send(EdgeProtocol.speechConfigMessage(EdgeProtocol.timestamp()))
        opened.countDown()
    }

    override fun onMessage(webSocket: WebSocket, text: String) = deliver(Event.Text(text))

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) = deliver(Event.Binary(bytes))

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        deliver(Event.Closed(code, reason))
        webSocket.close(1000, null)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = deliver(Event.Closed(code, reason))

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        if (opened.count > 0) {
            openError = if (response != null && response.code != 101) {
                HandshakeRejected(response.code, response.header("Date"))
            } else {
                Unreachable(t.message ?: t.javaClass.simpleName)
            }
            opened.countDown()
            return
        }
        deliver(Event.Failed(t))
    }

    private fun deliver(event: Event) {
        val target = synchronized(lock) {
            when (event) {
                is Event.Closed -> if (closeCode == null) closeCode = event.code
                is Event.Failed -> if (failure == null) failure = event.error
                else -> Unit
            }
            sink
        }
        target?.offer(event)
    }

    // Turn

    /**
     * Speak one SSML document and collect its MP3 bytes.
     * [firstFrameTimeoutMs] (reused sockets only): how long to wait for the
     * server's first reply (normally ~50 ms) before calling the socket stale.
     */
    fun speak(ssml: String, timeoutMs: Long, firstFrameTimeoutMs: Long?, isCancelled: () -> Boolean): ByteArray {
        val events = LinkedBlockingQueue<Event>()
        val (socket, dead) = synchronized(lock) {
            if (failure == null && closeCode == null) sink = events
            ws to (failure ?: closeCode?.let { IllegalStateException("closed with $it") })
        }
        if (dead != null || socket == null) {
            throw TurnFailure(true, EngineUnavailable("Edge TTS connection lost: ${dead?.message}", dead))
        }
        try {
            return runTurn(socket, events, ssml, timeoutMs, firstFrameTimeoutMs, isCancelled)
        } finally {
            synchronized(lock) { sink = null }
            lastUsedNanos = System.nanoTime()
        }
    }

    private fun runTurn(
        socket: WebSocket,
        events: LinkedBlockingQueue<Event>,
        ssml: String,
        timeoutMs: Long,
        firstFrameTimeoutMs: Long?,
        isCancelled: () -> Boolean,
    ): ByteArray {
        val start = System.nanoTime()
        val audio = ByteArrayOutputStream(INITIAL_AUDIO_BYTES)
        var gotFrame = false
        if (!socket.send(EdgeProtocol.ssmlMessage(EdgeProtocol.newId(), EdgeProtocol.timestamp(), ssml))) {
            throw TurnFailure(true, EngineUnavailable("Edge TTS connection lost before sending"))
        }
        while (true) {
            if (isCancelled()) {
                socket.cancel()
                throw CancellationException("Edge TTS synthesis cancelled")
            }
            val firstFrameDeadline = firstFrameTimeoutMs?.takeIf { !gotFrame && it < timeoutMs }
            val left = (firstFrameDeadline ?: timeoutMs) - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            if (left <= 0) {
                socket.cancel()
                if (firstFrameDeadline != null) {
                    throw TurnFailure(true, EngineUnavailable("Edge TTS didn't respond within $firstFrameDeadline ms"))
                }
                throw TurnFailure(false, EngineUnavailable("Edge TTS timed out after ${timeoutMs / 1000} s"))
            }
            when (val event = events.poll(minOf(left, POLL_MS), TimeUnit.MILLISECONDS) ?: continue) {
                is Event.Text -> {
                    gotFrame = true
                    if (parse { EdgeProtocol.parseTextFrame(event.text) }.path == "turn.end") break
                }
                is Event.Binary -> {
                    gotFrame = true
                    val frame = parse { EdgeProtocol.parseBinaryFrame(event.bytes.toByteArray()) }
                    EdgeProtocol.audioPayload(frame)?.let(audio::write)
                }
                // The server rejected this request (1007 = unknown voice / bad SSML, 1008 = policy):
                // a fresh socket fails the same way. Any other close before a reply means the
                // kept socket was closed under us (idle limit), so a fresh one is worth a try.
                is Event.Closed -> throw TurnFailure(
                    !gotFrame && event.code !in REQUEST_REJECTED_CODES,
                    EngineFailure("Edge TTS closed the connection: code ${event.code} ${event.reason}".trim()),
                )
                is Event.Failed -> {
                    val error = EngineUnavailable("Edge TTS connection lost: ${event.error.message}", event.error)
                    throw TurnFailure(!gotFrame, error)
                }
            }
        }
        if (audio.size() == 0) throw TurnFailure(false, EngineFailure("Edge TTS returned no audio"))
        return audio.toByteArray()
    }

    private inline fun parse(block: () -> EdgeProtocol.Frame): EdgeProtocol.Frame = try {
        block()
    } catch (e: EdgeProtocol.MalformedFrame) {
        throw TurnFailure(false, EngineFailure("Edge TTS sent an unexpected frame: ${e.message}", e))
    }

    companion object {
        private const val POLL_MS = 100L
        private const val INITIAL_AUDIO_BYTES = 64 * 1024
        private val REQUEST_REJECTED_CODES = setOf(1007, 1008)

        /**
         * Handshake and send `speech.config`. Throws [HandshakeRejected],
         * [Unreachable] or [ConnectTimedOut].
         */
        fun open(client: OkHttpClient, endpoint: EdgeEndpoint, timeoutMs: Long): EdgeConnection {
            val connection = EdgeConnection(endpoint.name)
            val socket = client.newWebSocket(endpoint.synthesisRequest(EdgeProtocol.newId(), EdgeClock.now()), connection)
            if (!connection.opened.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                socket.cancel()
                throw ConnectTimedOut(timeoutMs)
            }
            connection.openError?.let { throw it }
            return connection
        }
    }
}
