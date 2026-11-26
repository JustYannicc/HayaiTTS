package dev.ahmedmohamed.hayaitts.tts.edge

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Wire format of the Edge Read Aloud WebSocket. Pure functions, no I/O.
 *
 * Client → server: two text frames, `speech.config` then `ssml`.
 * Server → client: text frames `turn.start`, `response`, `audio.metadata`,
 * `turn.end`; binary frames carrying MP3 bytes, prefixed by a 2-byte
 * big-endian header length and that many bytes of `Key:Value\r\n` headers.
 */
internal object EdgeProtocol {
    const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"

    fun speechConfigMessage(timestamp: String): String =
        "X-Timestamp:$timestamp\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
            "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"true\"}," +
            "\"outputFormat\":\"$OUTPUT_FORMAT\"}}}}\r\n"

    fun ssmlMessage(requestId: String, timestamp: String, ssml: String): String =
        "X-RequestId:$requestId\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            // The trailing "Z" after a JS-style date is what Edge itself sends.
            "X-Timestamp:${timestamp}Z\r\n" +
            "Path:ssml\r\n\r\n" +
            ssml

    fun ssml(text: String, voiceId: String, language: String, speed: Float = 1f, pitch: Float = 1f): String =
        "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='${escapeXml(language)}'>" +
            "<voice name='${escapeXml(longVoiceName(voiceId))}'>" +
            "<prosody pitch='${pitchAttr(pitch)}' rate='${rateAttr(speed)}' volume='+0%'>" +
            escapeXml(sanitize(text)) +
            "</prosody></voice></speak>"

    /**
     * Speed multiplier → relative SSML rate (1.5 → `+50%`), clamped to 0.5–2×:
     * measured 2026-11-26, the service caps at +100% (+300% sounds like +100%).
     * Callers wanting faster have to stretch locally.
     */
    fun rateAttr(speed: Float): String = signedPercent(clampMultiplier(speed, 0.5f, 2f))

    /** Pitch multiplier → relative SSML pitch in percent (1.1 → `+10%`); clamped to ±50%. */
    fun pitchAttr(pitch: Float): String = signedPercent(clampMultiplier(pitch, 0.5f, 1.5f))

    private fun clampMultiplier(m: Float, lo: Float, hi: Float): Float =
        if (m.isNaN() || m <= 0f) 1f else m.coerceIn(lo, hi)

    private fun signedPercent(multiplier: Float): String {
        val pct = ((multiplier - 1f) * 100f).roundToInt()
        return if (pct >= 0) "+$pct%" else "$pct%"
    }

    private val JS_DATE: DateTimeFormatter = DateTimeFormatter
        .ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        .withZone(ZoneOffset.UTC)

    /** JavaScript `Date.toString()`-style timestamp in UTC. */
    fun timestamp(now: Instant = Instant.now()): String = JS_DATE.format(now)

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").lowercase()

    /**
     * `en-US-AriaNeural` → `Microsoft Server Speech Text to Speech Voice (en-US, AriaNeural)`.
     * Locales with a third part (`zh-CN-liaoning-XiaobeiNeural`) keep it in the
     * locale. Anything else passes through unchanged.
     */
    fun longVoiceName(shortName: String): String {
        val parts = shortName.split("-")
        if (parts.size < 3 || !parts.last().endsWith("Neural")) return shortName
        val (lang, region) = parts
        if (lang.length < 2 || lang != lang.lowercase() || region.length < 2 || region != region.uppercase()) {
            return shortName
        }
        val locale = parts.dropLast(1).joinToString("-")
        return "Microsoft Server Speech Text to Speech Voice ($locale, ${parts.last()})"
    }

    /** Escape for XML text and single-quoted attributes. */
    fun escapeXml(s: String): String = buildString(s.length) {
        for (ch in s) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '\'' -> append("&apos;")
                '"' -> append("&quot;")
                else -> append(ch)
            }
        }
    }

    /**
     * The service rejects some control characters (vertical tab is common in
     * OCR'd PDFs). Replace them with spaces; keep tab, LF and CR.
     */
    fun sanitize(s: String): String = buildString(s.length) {
        for (ch in s) {
            val v = ch.code
            append(if (v <= 8 || v == 11 || v == 12 || v in 14..31) ' ' else ch)
        }
    }

    /** True when there's at least one letter or digit to read. */
    fun isSpeakable(text: String): Boolean = text.any { it.isLetterOrDigit() }

    // Incoming

    class Frame(val headers: Map<String, String>, val body: ByteArray) {
        val path: String? get() = headers["Path"]
    }

    class MalformedFrame(message: String) : Exception(message)

    /** Text frame: headers, blank line, body. */
    fun parseTextFrame(text: String): Frame {
        val sep = text.indexOf("\r\n\r\n")
        if (sep < 0) throw MalformedFrame("text frame without header terminator")
        return Frame(parseHeaders(text.substring(0, sep)), text.substring(sep + 4).toByteArray(Charsets.UTF_8))
    }

    /** Binary frame: UInt16 BE header length, headers, then payload. */
    fun parseBinaryFrame(data: ByteArray): Frame {
        if (data.size < 2) throw MalformedFrame("binary frame shorter than 2 bytes")
        val headerLength = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        if (2 + headerLength > data.size) {
            throw MalformedFrame("binary header length $headerLength exceeds frame size ${data.size}")
        }
        val headers = parseHeaders(String(data, 2, headerLength, Charsets.UTF_8))
        return Frame(headers, data.copyOfRange(2 + headerLength, data.size))
    }

    /** MP3 payload of a binary frame, or null for anything that isn't audio. */
    fun audioPayload(frame: Frame): ByteArray? =
        frame.body.takeIf { frame.path == "audio" && frame.headers["Content-Type"] == "audio/mpeg" && it.isNotEmpty() }

    private fun parseHeaders(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in text.split("\r\n")) {
            val colon = line.indexOf(':')
            if (line.isEmpty() || colon < 0) continue
            out[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
        }
        return out
    }
}
