package app.scribe

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Cuts an audio file into pieces small enough to upload.
 *
 * This copies encoded frames straight across with MediaExtractor and
 * MediaMuxer — nothing is decoded and nothing is re-encoded, so it costs a
 * few seconds and a small buffer no matter how long the recording is, and
 * loses no quality.
 *
 * The page used to do this by decoding the whole file with the Web Audio API,
 * which needs the entire recording as raw samples in memory at once: about
 * 700MB for an hour. That is why a long lecture came back as "could not be
 * decoded" rather than being split.
 */
object AudioSplitter {

    /** Groq refuses audio over 25MB; leave headroom for container overhead. */
    private const val PART_BUDGET = 22L * 1024 * 1024

    data class Part(val file: File, val startMs: Long)

    /** @return the pieces in order, or an empty list if it could not be done. */
    fun split(src: File): List<Part> {
        val parts = ArrayList<Part>()
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            extractor.setDataSource(src.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return emptyList()

            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val bufSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceIn(64 * 1024, 4 * 1024 * 1024)
            } else {
                512 * 1024
            }
            val buffer = ByteBuffer.allocate(bufSize)
            val info = MediaCodec.BufferInfo()

            var trackIndex = -1
            var written = 0L
            var partStartUs = 0L
            var n = 0

            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val timeUs = extractor.sampleTime

                val needNewPart = muxer == null || written + size > PART_BUDGET
                if (needNewPart) {
                    muxer?.let { runCatching { it.stop() }; runCatching { it.release() } }
                    partStartUs = timeUs
                    val file = File(src.parentFile, "${src.nameWithoutExtension}-p${n++}.m4a")
                    val mx = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    trackIndex = mx.addTrack(format)
                    mx.start()
                    muxer = mx
                    written = 0
                    parts.add(Part(file, partStartUs / 1000))
                }

                info.offset = 0
                info.size = size
                // Each piece restarts at zero; where it sat in the original is
                // carried separately, so the transcript still lines up.
                info.presentationTimeUs = timeUs - partStartUs
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                muxer!!.writeSampleData(trackIndex, buffer, info)
                written += size
                extractor.advance()
            }

            muxer?.let { runCatching { it.stop() }; runCatching { it.release() } }
            muxer = null
            return parts.filter { it.file.length() > 0 }
        } catch (t: Throwable) {
            muxer?.let { runCatching { it.stop() }; runCatching { it.release() } }
            parts.forEach { it.file.delete() }
            return emptyList()
        } finally {
            runCatching { extractor.release() }
        }
    }
}
