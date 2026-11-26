package dev.ahmedmohamed.hayaitts.tts.edge

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import dev.ahmedmohamed.hayaitts.tts.engine.EngineAudio
import java.nio.ByteOrder

/** Compressed audio → mono float PCM. Behind an interface so the protocol code stays JVM-testable. */
fun interface AudioDecoder {
    /** Throws on undecodable input. */
    fun decode(encoded: ByteArray): EngineAudio
}

/** MP3 (or anything MediaExtractor sniffs) → mono float PCM via the platform MediaCodec. */
class MediaCodecMp3Decoder : AudioDecoder {

    override fun decode(encoded: ByteArray): EngineAudio {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(ByteArrayDataSource(encoded))
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("no audio track in ${encoded.size} bytes")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                // 48 kbit/s MP3 ≈ 4 PCM samples per encoded byte at 24 kHz mono.
                return drain(extractor, codec, format, FloatBuilder(encoded.size * 4 + 4_096))
            } finally {
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun drain(extractor: MediaExtractor, codec: MediaCodec, input: MediaFormat, out: FloatBuilder): EngineAudio {
        var sampleRate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var idleRounds = 0
        while (true) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
                outIndex >= 0 -> {
                    idleRounds = 0
                    val buffer = codec.getOutputBuffer(outIndex)!!
                    buffer.position(info.offset).limit(info.offset + info.size)
                    buffer.order(ByteOrder.nativeOrder())
                    if (floatPcm) {
                        val fb = buffer.asFloatBuffer()
                        downmix(fb.remaining(), channels, out) { fb.get(it) }
                    } else {
                        val sb = buffer.asShortBuffer()
                        downmix(sb.remaining(), channels, out) { sb.get(it) / 32768f }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                // Guard against a codec that never signals EOS.
                inputDone && ++idleRounds > MAX_IDLE_ROUNDS -> break
            }
        }
        return EngineAudio(out.toArray(), sampleRate)
    }

    private inline fun downmix(count: Int, channels: Int, out: FloatBuilder, sample: (Int) -> Float) {
        val ch = channels.coerceAtLeast(1)
        out.ensure(count / ch)
        for (frame in 0 until count / ch) {
            var sum = 0f
            for (c in 0 until ch) sum += sample(frame * ch + c)
            out.add(sum / ch)
        }
    }

    private class FloatBuilder(initial: Int) {
        private var data = FloatArray(initial)
        private var size = 0
        fun ensure(extra: Int) {
            if (size + extra > data.size) data = data.copyOf(maxOf(data.size * 2, size + extra))
        }
        fun add(v: Float) { ensure(1); data[size++] = v }
        fun toArray(): FloatArray = if (size == data.size) data else data.copyOf(size)
    }

    private class ByteArrayDataSource(private val bytes: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= bytes.size) return -1
            val n = minOf(size.toLong(), bytes.size - position).toInt()
            System.arraycopy(bytes, position.toInt(), buffer, offset, n)
            return n
        }
        override fun getSize(): Long = bytes.size.toLong()
        override fun close() = Unit
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_IDLE_ROUNDS = 200
    }
}
