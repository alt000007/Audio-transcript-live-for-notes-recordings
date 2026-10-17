package app.scribe

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

/**
 * Decodes any audio file Android can read into mono float samples, a block at
 * a time, so a four-hour recording never has to fit in memory.
 */
object AudioDecoder {

    class Info(val durationMs: Long)

    /** Reads only the container, without decoding: is there audio, and how long. */
    fun probe(src: File): Info? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(src.absolutePath)
            val t = audioTrack(ex) ?: return null
            val f = ex.getTrackFormat(t)
            Info(if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) / 1000 else 0)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { ex.release() }
        }
    }

    private fun audioTrack(ex: MediaExtractor): Int? = (0 until ex.trackCount).firstOrNull {
        ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
    }

    /**
     * Decodes [src] from [fromMs] onward. [onPcm] receives mono samples at the
     * file's own rate, which it is told alongside.
     *
     * @throws IllegalStateException when the file cannot be decoded at all.
     */
    fun decode(src: File, fromMs: Long, cancelled: () -> Boolean, onPcm: (FloatArray, Int, Int) -> Unit) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(src.absolutePath)
            val track = audioTrack(ex) ?: throw IllegalStateException("No audio track in this file.")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IllegalStateException("Unknown audio format.")
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            if (fromMs > 0) ex.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var mono = FloatArray(16384)
            val skipUs = fromMs * 1000

            while (true) {
                if (cancelled()) return
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    o >= 0 -> {
                        val out = codec.getOutputBuffer(o)
                        if (out != null && info.size > 0) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            out.order(ByteOrder.nativeOrder())
                            val bytesPer = if (isFloat) 4 else 2
                            val frames = info.size / bytesPer / channels
                            // After a seek the decoder starts at the previous
                            // sync frame; drop what lies before the resume point.
                            var skipFrames = 0
                            if (info.presentationTimeUs < skipUs) {
                                skipFrames = (((skipUs - info.presentationTimeUs) * rate) / 1_000_000L).toInt().coerceAtMost(frames)
                            }
                            if (mono.size < frames) mono = FloatArray(frames)
                            var n = 0
                            if (isFloat) {
                                val fb = out.asFloatBuffer()
                                for (fr in 0 until frames) {
                                    var acc = 0f
                                    for (c in 0 until channels) acc += fb.get()
                                    if (fr >= skipFrames) mono[n++] = acc / channels
                                }
                            } else {
                                val sb = out.asShortBuffer()
                                for (fr in 0 until frames) {
                                    var acc = 0
                                    for (c in 0 until channels) acc += sb.get()
                                    if (fr >= skipFrames) mono[n++] = acc / (32768f * channels)
                                }
                            }
                            if (n > 0) onPcm(mono, n, rate)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
        }
    }
}

/**
 * Turns a copied recording into pieces ready to transcribe.
 *
 * The audio is decoded, mixed to mono, resampled to 16 kHz — what Whisper
 * works at internally anyway, so nothing it would use is lost — long silences
 * are shortened, and each piece is encoded as compact AAC. A two-hour lecture
 * recorded at 128 kbps stereo goes from about 115 MB to about 25 MB, which
 * uploads several times faster on a phone connection.
 *
 * Pieces are written to disk and handed over one at a time, so the first is
 * transcribing while the rest are still being prepared, and preparation that
 * is interrupted resumes after the last piece it saved.
 *
 * If the phone cannot decode a file, it is sent as it came, cut with the
 * frame-copying splitter when too large — exactly what the app did before.
 */
class AudioPreparer(private val trimSilence: () -> Boolean) : Preparer {

    override fun prepare(job: Job, store: JobStore, onChunk: (Chunk) -> Unit, cancelled: () -> Boolean) {
        val src = store.source(job)
        if (!src.exists()) throw IllegalStateException("The copied file is missing. Share it again.")

        if (job.durationMs == 0L) {
            AudioDecoder.probe(src)?.let { synchronized(store) { job.durationMs = it.durationMs; store.save(job) } }
        }

        if (job.mode != "raw") {
            try {
                prepareDecoded(job, store, src, onChunk, cancelled)
                return
            } catch (e: Exception) {
                if (cancelled()) return
                // Pieces already made from decoded audio stay valid. Only a
                // file that could not be decoded from the start falls back.
                if (job.chunks.isNotEmpty()) throw e
                Diag.log("prepare", "decode failed for ${job.name}: ${e.message}; sending as-is")
                synchronized(store) { job.mode = "raw"; store.save(job) }
            }
        }
        prepareRaw(job, store, src, onChunk)
    }

    private fun prepareDecoded(job: Job, store: JobStore, src: File, onChunk: (Chunk) -> Unit, cancelled: () -> Boolean) {
        synchronized(store) { if (job.mode != "trim") { job.mode = "trim"; store.save(job) } }
        val dir = store.chunkDir(job.id).apply { mkdirs() }
        var next = job.chunks.maxOfOrNull { it.index + 1 } ?: 0
        var resampler: Resampler? = null
        var rateSeen = 0

        val chunker = Chunker(job.preparedToMs, trimSilence()) { pcm, len, startMs, endMs, map ->
            val index = next++
            val name = "chunks/c$index.m4a"
            val file = File(dir, "c$index.m4a")
            val tmp = File(dir, "c$index.m4a.tmp")
            if (!AacEncoder.encode(pcm, len, Chunker.RATE, tmp, BIT_RATE)) {
                tmp.delete()
                throw IllegalStateException("Could not encode part ${index + 1}.")
            }
            tmp.renameTo(file)
            synchronized(store) {
                job.preparedToMs = endMs
                job.keptMs += len * 1000L / Chunker.RATE
            }
            onChunk(Chunk(index, name, startMs, endMs, map))
        }

        AudioDecoder.decode(src, job.preparedToMs, cancelled) { samples, n, rate ->
            if (resampler == null || rate != rateSeen) {
                resampler = Resampler(rate, Chunker.RATE)
                rateSeen = rate
            }
            resampler!!.push(samples, n) { out, m -> chunker.push(out, m) }
        }
        if (cancelled()) return
        chunker.finish()
        synchronized(store) {
            if (job.durationMs == 0L) job.durationMs = job.preparedToMs
            store.save(job)
        }
    }

    private fun prepareRaw(job: Job, store: JobStore, src: File, onChunk: (Chunk) -> Unit) {
        if (job.chunks.isNotEmpty()) return
        if (src.length() <= UPLOAD_LIMIT) {
            onChunk(Chunk(0, src.name, 0, job.durationMs, longArrayOf(0, 0)))
            return
        }
        val parts = AudioSplitter.split(src)
        if (parts.isEmpty()) throw IllegalStateException("This recording could not be read or split into uploadable pieces.")
        parts.forEachIndexed { i, p ->
            val end = parts.getOrNull(i + 1)?.startMs ?: job.durationMs
            onChunk(Chunk(i, p.file.name, p.startMs, end, longArrayOf(0, p.startMs)))
        }
    }

    companion object {
        /** Groq refuses audio over 25 MB. */
        const val UPLOAD_LIMIT = 24L * 1024 * 1024
        /** A little above the recorder's 24 kbps: these are whole lectures, worth the margin. */
        const val BIT_RATE = 32_000
    }
}
