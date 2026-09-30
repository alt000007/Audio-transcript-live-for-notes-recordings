package app.scribe

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes one segment of 16 kHz mono PCM into a standalone .m4a file.
 *
 * AAC rather than WAV because the segments are uploaded: raw 16 kHz PCM costs
 * about 115 MB an hour, where AAC at 24 kbps costs roughly 11 MB — the same
 * order as the web build, and the difference between usable and unusable on
 * mobile data.
 *
 * Each file is muxed on its own so it is independently decodable, which is
 * what a transcription API needs.
 */
object AacEncoder {

    private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val BIT_RATE = 24_000
    private const val TIMEOUT_US = 10_000L

    /** @return true when [out] holds a complete file. */
    fun encode(pcm: ShortArray, length: Int, sampleRate: Int, out: File): Boolean {
        if (length <= 0) return false

        val format = MediaFormat.createAudioFormat(MIME, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            codec = MediaCodec.createEncoderByType(MIME)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            var track = -1
            val info = MediaCodec.BufferInfo()
            var offset = 0          // in samples
            var inputDone = false

            while (true) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex) ?: continue
                        buf.clear()
                        val samples = minOf((buf.capacity() / 2), length - offset)
                        if (samples <= 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0,
                                offset.toLong() * 1_000_000L / sampleRate,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            buf.order(ByteOrder.nativeOrder())
                            for (i in 0 until samples) buf.putShort(pcm[offset + i])
                            codec.queueInputBuffer(
                                inIndex, 0, samples * 2,
                                offset.toLong() * 1_000_000L / sampleRate,
                                0,
                            )
                            offset += samples
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) return false
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputDone && info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    else -> if (outIndex >= 0) {
                        val encoded: ByteBuffer? = codec.getOutputBuffer(outIndex)
                        // Codec config bytes belong in the track format, not the stream.
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (encoded != null && info.size > 0 && !isConfig && muxerStarted) {
                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            muxer.writeSampleData(track, encoded, info)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            return muxerStarted && out.length() > 0
        } catch (t: Throwable) {
            return false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }
}
