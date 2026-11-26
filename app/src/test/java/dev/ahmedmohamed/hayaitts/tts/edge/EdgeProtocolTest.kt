package dev.ahmedmohamed.hayaitts.tts.edge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

class EdgeProtocolTest {

    // Token

    @Test
    fun `token - fixed clock - matches independently computed windows ticks hash`() {
        val now = Instant.parse("2026-11-26T12:34:56Z")
        // Independent path: java.time duration since the Windows epoch, floored to 5 minutes.
        val sinceWindowsEpoch = Duration.between(Instant.parse("1601-01-01T00:00:00Z"), now).seconds
        val ticks = (sinceWindowsEpoch / 300 * 300) * 10_000_000L
        assertEquals(134_401_698_000_000_000L, ticks)
        val expected = MessageDigest.getInstance("SHA-256")
            .digest("${ticks}6A5AA1D4EAFF4E9FB37E23D68491D6F4".toByteArray())
            .joinToString("") { String.format("%02x", it).uppercase() }
        // Also pinned to a value computed outside the JVM (Python hashlib).
        assertEquals("2F9B38C59849F9A23A3BB88CC17C07975146D6AB49DD44373839D34E5BA66F6E", expected)
        assertEquals(expected, EdgeEndpointV1().token(now))
    }

    @Test
    fun `token - same 5 minute window - stable, next window - changes`() {
        val a = EdgeToken.secMsGec(Instant.parse("2026-11-26T12:30:00Z").epochSecond, "X")
        val b = EdgeToken.secMsGec(Instant.parse("2026-11-26T12:34:59Z").epochSecond, "X")
        val c = EdgeToken.secMsGec(Instant.parse("2026-11-26T12:35:00Z").epochSecond, "X")
        assertEquals(a, b)
        assertTrue(a != c)
        assertTrue(a.matches(Regex("[0-9A-F]{64}")))
    }

    @Test
    fun `synthesis request - carries key, token, version and browser headers`() {
        val now = Instant.parse("2026-11-26T12:34:56Z")
        val ms = EdgeEndpointV1(EdgeEndpointV1.Host.MS_EDGE_SERVICES).synthesisRequest("cid", now)
        assertEquals("api.msedgeservices.com", ms.url.host)
        assertEquals(EdgeEndpointV1.TRUSTED_CLIENT_TOKEN, ms.url.queryParameter("Ocp-Apim-Subscription-Key"))
        assertEquals("2F9B38C59849F9A23A3BB88CC17C07975146D6AB49DD44373839D34E5BA66F6E", ms.url.queryParameter("Sec-MS-GEC"))
        assertEquals("1-143.0.3650.75", ms.url.queryParameter("Sec-MS-GEC-Version"))
        assertEquals("cid", ms.url.queryParameter("ConnectionId"))
        assertEquals("chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold", ms.header("Origin"))
        assertTrue(ms.header("User-Agent")!!.contains("Edg/143.0.0.0"))
        assertTrue(ms.header("Cookie")!!.matches(Regex("muid=[0-9A-F]{32};")))

        val bing = EdgeEndpointV1(EdgeEndpointV1.Host.BING).synthesisRequest("cid", now)
        assertEquals("speech.platform.bing.com", bing.url.host)
        assertEquals(EdgeEndpointV1.TRUSTED_CLIENT_TOKEN, bing.url.queryParameter("TrustedClientToken"))
    }

    @Test
    fun `clock - http date header - parses`() {
        assertEquals(Instant.parse("2026-11-26T12:34:56Z"), EdgeClock.parseHttpDate("Thu, 26 Nov 2026 12:34:56 GMT"))
        assertNull(EdgeClock.parseHttpDate("garbage"))
    }

    // SSML

    @Test
    fun `ssml - escapes markup and scrubs control characters`() {
        val ssml = EdgeProtocol.ssml("a < b & \"c\" 'd'\u000Be\u0001", "de-DE-KatjaNeural", "de-DE")
        assertTrue(ssml.contains(">a &lt; b &amp; &quot;c&quot; &apos;d&apos; e </prosody>"))
        assertTrue(ssml.contains("xml:lang='de-DE'"))
        assertTrue(ssml.contains("<voice name='Microsoft Server Speech Text to Speech Voice (de-DE, KatjaNeural)'>"))
        assertTrue(ssml.contains("<prosody pitch='+0%' rate='+0%' volume='+0%'>"))
        assertFalse(ssml.contains("\u000B"))
    }

    @Test
    fun `sanitize - keeps tab, newline and carriage return`() {
        assertEquals("a\tb\nc\rd e", EdgeProtocol.sanitize("a\tb\nc\rd\u000Ce"))
    }

    @Test
    fun `prosody - multipliers map to signed percentages and clamp`() {
        assertEquals("+50%", EdgeProtocol.rateAttr(1.5f))
        assertEquals("-25%", EdgeProtocol.rateAttr(0.75f))
        assertEquals("+0%", EdgeProtocol.rateAttr(Float.NaN))
        assertEquals("+100%", EdgeProtocol.rateAttr(10f))
        assertEquals("-50%", EdgeProtocol.rateAttr(0.1f))
        assertEquals("+10%", EdgeProtocol.pitchAttr(1.1f))
        assertEquals("-50%", EdgeProtocol.pitchAttr(0.1f))
    }

    @Test
    fun `long voice name - expands neural short names only`() {
        assertEquals(
            "Microsoft Server Speech Text to Speech Voice (zh-CN-liaoning, XiaobeiNeural)",
            EdgeProtocol.longVoiceName("zh-CN-liaoning-XiaobeiNeural"),
        )
        assertEquals("custom", EdgeProtocol.longVoiceName("custom"))
        assertEquals("EN-us-AriaNeural", EdgeProtocol.longVoiceName("EN-us-AriaNeural"))
    }

    @Test
    fun `messages - speech config and ssml framing`() {
        val config = EdgeProtocol.speechConfigMessage("TS")
        assertTrue(config.startsWith("X-Timestamp:TS\r\n"))
        assertTrue(config.contains("Path:speech.config\r\n\r\n{"))
        assertTrue(config.contains("\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\""))
        val msg = EdgeProtocol.ssmlMessage("rid", "TS", "<speak/>")
        assertEquals("X-RequestId:rid\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:TSZ\r\nPath:ssml\r\n\r\n<speak/>", msg)
        assertEquals(
            "Thu Nov 26 2026 12:34:56 GMT+0000 (Coordinated Universal Time)",
            EdgeProtocol.timestamp(Instant.parse("2026-11-26T12:34:56Z")),
        )
    }

    @Test
    fun `speakable - needs a letter or digit`() {
        assertFalse(EdgeProtocol.isSpeakable("  ...!? "))
        assertTrue(EdgeProtocol.isSpeakable("3"))
        assertTrue(EdgeProtocol.isSpeakable("。你"))
    }

    // Frames

    private fun binaryFrame(headers: String, payload: ByteArray): ByteArray {
        val h = headers.toByteArray()
        return byteArrayOf((h.size shr 8).toByte(), (h.size and 0xFF).toByte()) + h + payload
    }

    @Test
    fun `binary frame - big endian header length, audio payload extracted`() {
        val headers = "X-RequestId:abc\r\nContent-Type:audio/mpeg\r\nX-StreamId:1\r\nPath:audio\r\n" + "x".repeat(300)
        val payload = byteArrayOf(0xFF.toByte(), 0xF3.toByte(), 1, 2, 3)
        val frame = EdgeProtocol.parseBinaryFrame(binaryFrame(headers, payload))
        assertEquals("audio", frame.path)
        assertEquals("audio/mpeg", frame.headers["Content-Type"])
        assertArrayEquals(payload, EdgeProtocol.audioPayload(frame))
    }

    @Test
    fun `binary frame - non audio or empty payload yields no audio`() {
        val empty = EdgeProtocol.parseBinaryFrame(binaryFrame("Content-Type:audio/mpeg\r\nPath:audio\r\n", ByteArray(0)))
        assertNull(EdgeProtocol.audioPayload(empty))
        val other = EdgeProtocol.parseBinaryFrame(binaryFrame("Path:response\r\n", byteArrayOf(1)))
        assertNull(EdgeProtocol.audioPayload(other))
    }

    @Test(expected = EdgeProtocol.MalformedFrame::class)
    fun `binary frame - header length beyond frame - throws`() {
        EdgeProtocol.parseBinaryFrame(byteArrayOf(0x01, 0x00, 'a'.code.toByte()))
    }

    @Test(expected = EdgeProtocol.MalformedFrame::class)
    fun `binary frame - shorter than two bytes - throws`() {
        EdgeProtocol.parseBinaryFrame(byteArrayOf(0x01))
    }

    @Test
    fun `text frame - headers and body split on blank line`() {
        val frame = EdgeProtocol.parseTextFrame("X-RequestId:abc\r\nPath:turn.end\r\n\r\n{}")
        assertEquals("turn.end", frame.path)
        assertEquals("{}", String(frame.body))
    }

    @Test
    fun `turn timeout - grows with text length`() {
        assertEquals(6_000L, EdgeSpeechEngine.turnTimeoutMs(0))
        assertEquals(10_000L, EdgeSpeechEngine.turnTimeoutMs(100))
    }
}
