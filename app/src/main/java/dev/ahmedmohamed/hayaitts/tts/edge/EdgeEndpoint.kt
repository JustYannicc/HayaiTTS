package dev.ahmedmohamed.hayaitts.tts.edge

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.security.MessageDigest
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Where the Edge "Read Aloud" service lives and how requests get authorized.
 * Everything that changes between auth schemes sits behind this, so a future
 * v2 token scheme is a new implementation.
 */
interface EdgeEndpoint {
    /** Short label for logs/errors ("v1@bing"). */
    val name: String

    /** WebSocket handshake request for one synthesis connection. */
    fun synthesisRequest(connectionId: String, now: Instant): Request

    /** Plain HTTPS GET returning the voice list JSON. */
    fun voiceListRequest(now: Instant): Request
}

/**
 * v1 token scheme (SHA-256 of rounded Windows ticks + client token, version
 * `1-<chromium>`), on either host:
 * - [Host.MS_EDGE_SERVICES]: the host current Edge uses; still accepts v1 tokens.
 * - [Host.BING]: the classic `speech.platform.bing.com` endpoint. Some European
 *   front-door nodes reject it with 403, so it's the fallback.
 */
class EdgeEndpointV1(val host: Host = Host.MS_EDGE_SERVICES) : EdgeEndpoint {

    enum class Host(
        val label: String,
        val synthesisBase: String,
        val voiceListBase: String,
        val synthesisKeyParam: String,
        val voiceKeyParam: String,
    ) {
        MS_EDGE_SERVICES(
            "msEdgeServices",
            "https://api.msedgeservices.com/tts/cognitiveservices/websocket/v1",
            "https://api.msedgeservices.com/tts/cognitiveservices/voices/list",
            "Ocp-Apim-Subscription-Key",
            "Ocp-Apim-Subscription-Key",
        ),
        BING(
            "bing",
            "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1",
            "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/voices/list",
            "TrustedClientToken",
            "trustedclienttoken",
        ),
    }

    override val name: String get() = "v1@${host.label}"

    internal fun token(now: Instant): String = EdgeToken.secMsGec(now.epochSecond, TRUSTED_CLIENT_TOKEN)

    override fun synthesisRequest(connectionId: String, now: Instant): Request {
        // OkHttp builds URLs as https; newWebSocket upgrades them (wss is the same port and TLS).
        val url = authorizedUrl(host.synthesisBase, host.synthesisKeyParam, now)
            .addQueryParameter("ConnectionId", connectionId)
            .build()
        return browserHeaders(Request.Builder().url(url))
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", ORIGIN)
            .header("Accept-Encoding", "gzip, deflate, br, zstd")
            .build()
    }

    override fun voiceListRequest(now: Instant): Request {
        // No Accept-Encoding: OkHttp then negotiates gzip and decompresses itself.
        return browserHeaders(Request.Builder().url(authorizedUrl(host.voiceListBase, host.voiceKeyParam, now).build()))
            .header("Accept", "*/*")
            .header("Sec-CH-UA-Mobile", "?0")
            .header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Dest", "empty")
            .build()
    }

    private fun authorizedUrl(base: String, keyParam: String, now: Instant) = base.toHttpUrl().newBuilder()
        .addQueryParameter(keyParam, TRUSTED_CLIENT_TOKEN)
        .addQueryParameter("Sec-MS-GEC", token(now))
        .addQueryParameter("Sec-MS-GEC-Version", SEC_MS_GEC_VERSION)

    private fun browserHeaders(builder: Request.Builder) = builder
        .header("User-Agent", USER_AGENT)
        .header("Accept-Language", "en-US,en;q=0.9")
        .header("Cookie", "muid=${EdgeToken.muid()};")

    companion object {
        const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
        const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
        const val SEC_MS_GEC_VERSION = "1-$CHROMIUM_FULL_VERSION"
        private val CHROMIUM_MAJOR = CHROMIUM_FULL_VERSION.substringBefore('.')
        val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$CHROMIUM_MAJOR.0.0.0 Safari/537.36 Edg/$CHROMIUM_MAJOR.0.0.0"

        /** Default order: new host first, classic host as fallback. */
        val ALL: List<EdgeEndpoint> = listOf(EdgeEndpointV1(Host.MS_EDGE_SERVICES), EdgeEndpointV1(Host.BING))
    }
}

internal object EdgeToken {
    /** Seconds between 1601-01-01 (Windows file time epoch) and 1970-01-01. */
    const val WINDOWS_EPOCH_OFFSET = 11_644_473_600L

    /**
     * v1 Sec-MS-GEC: Windows file time rounded down to 5 minutes, in 100-ns
     * ticks, concatenated with the client token, SHA-256, uppercase hex.
     */
    fun secMsGec(unixSeconds: Long, clientToken: String): String {
        var seconds = unixSeconds + WINDOWS_EPOCH_OFFSET
        seconds -= seconds % 300
        val ticks = seconds * 10_000_000L
        val digest = MessageDigest.getInstance("SHA-256").digest("$ticks$clientToken".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }

    /** Random 32-hex-char MUID cookie value, like the browser sends. */
    fun muid(): String = UUID.randomUUID().toString().replace("-", "").uppercase()
}

/**
 * Process-wide correction for a wrong device clock, learned from the server's
 * `Date` header when a handshake is rejected (a stale token looks like a 403).
 */
internal object EdgeClock {
    @Volatile private var skewMillis = 0L

    fun now(): Instant = Instant.now().plusMillis(skewMillis)

    /** Re-anchor to the server's clock. Returns the server time, or null if unparseable. */
    fun adjust(serverDateHeader: String): Instant? {
        val server = parseHttpDate(serverDateHeader) ?: return null
        skewMillis = server.toEpochMilli() - System.currentTimeMillis()
        return server
    }

    fun parseHttpDate(value: String): Instant? =
        runCatching { ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
}
