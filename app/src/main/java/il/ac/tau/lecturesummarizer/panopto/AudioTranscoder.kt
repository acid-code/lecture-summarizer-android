package il.ac.tau.lecturesummarizer.panopto

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder

/**
 * Streams decoded PCM from many slice files into ONE mono AAC-LC .m4a (MediaCodec + MediaMuxer, no ffmpeg).
 * AAC rather than Opus: the MediaCodec AAC encoder exists on every device (Opus encoder/OGG muxer only API 29+,
 * and the server/ASR path already expects audio/mp4). ~32 kbps mono ≈ 30 MB per 2 h lecture.
 *
 * Usage: [appendSlice] per ~10 min slice (a .ts or fMP4 file holding muxed A/V — video is skipped), then [finish].
 */
class AudioTranscoder(private val out: File, private val sampleRate: Int = 22050, private val bitRate: Int = 32_000) {
    private val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
        configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        start()
    }
    private var track = -1
    private var muxing = false
    private var samplesOut = 0L          // mono samples fed to the encoder (drives pts → continuous timeline)
    private val info = MediaCodec.BufferInfo()
    private var pending = ShortArray(0)  // resampled mono not yet queued
    val writtenSec get() = samplesOut.toDouble() / sampleRate

    /** Decode the audio track of [slice] and append it. Returns the slice's audio duration in seconds. */
    fun appendSlice(slice: File, progress: (Double) -> Unit = {}): Double {
        val ex = MediaExtractor(); ex.setDataSource(slice.path)
        val ti = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: run { ex.release(); error("No audio track in Panopto stream") }
        ex.selectTrack(ti)
        val fmt = ex.getTrackFormat(ti)
        val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        dec.configure(fmt, null, null, 0); dec.start()
        var inRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val startSamples = samplesOut
        var inEos = false; var outEos = false; var resamplePos = 0.0
        val di = MediaCodec.BufferInfo()
        try {
            while (!outEos) {
                if (!inEos) {
                    val i = dec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(dec.getInputBuffer(i)!!, 0)
                        if (n < 0) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true }
                        else { dec.queueInputBuffer(i, 0, n, ex.sampleTime.coerceAtLeast(0), 0); ex.advance() }
                    }
                }
                val o = dec.dequeueOutputBuffer(di, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> dec.outputFormat.let { f ->
                        inRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                    o >= 0 -> {
                        if (di.size > 0) {
                            val sb = dec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val frames = sb.remaining() / ch
                            val mono = ShortArray(frames)
                            for (f in 0 until frames) { var acc = 0; for (c in 0 until ch) acc += sb.get(f * ch + c); mono[f] = (acc / ch).toShort() }
                            // Linear resample inRate → sampleRate (keeps phase across buffers).
                            val step = inRate.toDouble() / sampleRate
                            val res = ShortArray((frames / step).toInt() + 2); var rn = 0
                            while (resamplePos < frames - 1 && rn < res.size) {
                                val k = resamplePos.toInt(); val fr = resamplePos - k
                                res[rn++] = (mono[k] * (1 - fr) + mono[k + 1] * fr).toInt().toShort(); resamplePos += step
                            }
                            resamplePos = (resamplePos - frames).coerceAtLeast(0.0)
                            feed(res.copyOf(rn))
                            progress((samplesOut - startSamples).toDouble() / sampleRate)
                        }
                        if (di.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEos = true
                        dec.releaseOutputBuffer(o, false)
                    }
                }
            }
        } finally { runCatching { dec.stop() }; dec.release(); ex.release() }
        return (samplesOut - startSamples).toDouble() / sampleRate
    }

    private fun feed(pcm: ShortArray) {
        pending = if (pending.isEmpty()) pcm else pending + pcm
        var off = 0
        while (pending.size - off > 0) {
            drain(false)
            val i = enc.dequeueInputBuffer(10_000)
            if (i < 0) continue
            val buf = enc.getInputBuffer(i)!!.order(ByteOrder.nativeOrder())
            val n = minOf(buf.remaining() / 2, pending.size - off, 1024)
            buf.asShortBuffer().put(pending, off, n)
            enc.queueInputBuffer(i, 0, n * 2, samplesOut * 1_000_000L / sampleRate, 0)
            samplesOut += n; off += n
        }
        pending = ShortArray(0)
        drain(false)
    }

    private fun drain(eos: Boolean) {
        while (true) {
            val o = enc.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            when {
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!eos) return
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(enc.outputFormat); muxer.start(); muxing = true }
                o >= 0 -> {
                    val b = enc.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxing) muxer.writeSampleData(track, b, info)
                    val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    enc.releaseOutputBuffer(o, false)
                    if (end) return
                }
            }
        }
    }

    /** Flush and close; returns total duration in seconds. */
    fun finish(): Double {
        var i: Int
        do { i = enc.dequeueInputBuffer(10_000); if (i < 0) drain(false) } while (i < 0)
        enc.queueInputBuffer(i, 0, 0, samplesOut * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(true)
        runCatching { enc.stop() }; enc.release()
        if (muxing) muxer.stop()
        muxer.release()
        return writtenSec
    }

    fun abort() { runCatching { enc.stop() }; runCatching { enc.release() }; runCatching { if (muxing) muxer.stop() }; runCatching { muxer.release() }; out.delete() }
}
