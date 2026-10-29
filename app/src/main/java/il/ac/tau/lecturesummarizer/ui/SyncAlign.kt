package il.ac.tau.lecturesummarizer.ui

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import il.ac.tau.lecturesummarizer.data.TranscriptLine
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10

/**
 * Transcript re-timing against the local audio.
 *
 * Why: server ASR (Gemini) returns sentence timestamps that are *estimated* by the model inside each ~6 min clip.
 * They start right at the clip start but drift (stretch/compress) as the clip goes on, so the highlight ends up a
 * sentence or more ahead/behind and gets worse until the next clip boundary. The player clock itself is correct.
 *
 * Fix: within each clip (exact, known boundaries) distribute the sentences over the *voiced* audio in proportion to
 * their text length (speech rate is roughly constant), then snap each start to the nearest speech onset after a
 * pause. Errors stay local instead of accumulating.
 */
object SyncAlign {
    const val FRAME_MS = 50

    /** Per-frame voiced flags from per-frame dB energy (adaptive noise floor). */
    fun voicedFromDb(db: FloatArray): BooleanArray {
        if (db.isEmpty()) return BooleanArray(0)
        val sorted = db.sorted()
        val floor = sorted[(sorted.size * 0.15).toInt().coerceIn(0, sorted.size - 1)]
        val peak = sorted[(sorted.size * 0.95).toInt().coerceIn(0, sorted.size - 1)]
        val thr = floor + maxOf(4f, (peak - floor) * 0.3f)
        val v = BooleanArray(db.size) { db[it] > thr }
        // Fill short dips (<200 ms) inside words.
        var i = 0
        while (i < v.size) {
            if (!v[i]) { var j = i; while (j < v.size && !v[j]) j++
                if (i > 0 && j < v.size && j - i < 4) for (k in i until j) v[k] = true
                i = j } else i++
        }
        return v
    }

    /** Clip boundaries (sec) the server used: each part offset + k*windowSec inside it. */
    fun anchors(partOffsetsSec: List<Double>, partLensSec: List<Double>, windowSec: Double = 360.0): List<Double> {
        val out = sortedSetOf<Double>()
        partOffsetsSec.forEachIndexed { i, off ->
            var w = 0.0; val len = partLensSec[i]
            while (w < len - 1) { out += off + w; w += windowSec }
            out += off + len
        }
        return out.toList()
    }

    fun realign(lines: List<TranscriptLine>, voiced: BooleanArray, anchorsSec: List<Double>, frameMs: Int = FRAME_MS): List<TranscriptLine> {
        if (lines.size < 2 || voiced.isEmpty()) return lines
        val fs = frameMs / 1000.0
        val nF = voiced.size
        val cum = IntArray(nF + 1); for (i in 0 until nF) cum[i + 1] = cum[i] + if (voiced[i]) 1 else 0
        fun fr(t: Double) = (t / fs).toInt().coerceIn(0, nF)
        val out = lines.toMutableList()
        val bounds = (listOf(0.0) + anchorsSec + listOf(nF * fs)).distinct().sorted()
        for (b in 0 until bounds.size - 1) {
            val ws = bounds[b]; val we = bounds[b + 1]
            val idx = lines.indices.filter { lines[it].t0 >= ws - 0.5 && lines[it].t0 < we - 0.5 }
            if (idx.size < 2) continue
            // Split further at long silences in the ASR output (>8 s gaps are real pauses/untranscribed parts).
            val groups = mutableListOf(mutableListOf(idx[0]))
            for (k in 1 until idx.size) { val g = lines[idx[k]].t0 - lines[idx[k - 1]].t1
                if (g > 8) groups += mutableListOf(idx[k]) else groups.last() += idx[k] }
            groups.forEachIndexed { gi, g ->
                if (g.size < 2) return@forEachIndexed
                val start = maxOf(ws, lines[g.first()].t0 - 0.5)
                val hardEnd = if (gi + 1 < groups.size) lines[groups[gi + 1].first()].t0 else we
                val fA = fr(start); var fB = fr(hardEnd)
                while (fB > fA && !voiced[fB - 1]) fB--          // trailing silence isn't speech
                val vTot = cum[fB] - cum[fA]
                if (vTot < 20 || fB - fA < 40) return@forEachIndexed
                val chars = g.map { maxOf(1, lines[it].text.count { c -> !c.isWhitespace() }) }
                val totC = chars.sum().toDouble()
                var acc = 0
                val newT0 = DoubleArray(g.size)
                for (k in g.indices) {
                    val target = cum[fA] + (acc / totC * vTot).toInt()
                    // frame where cumulative voiced reaches target
                    var lo = fA; var hi = fB
                    while (lo < hi) { val m = (lo + hi) / 2; if (cum[m + 1] <= target) lo = m + 1 else hi = m }
                    var f = lo
                    // Snap to nearest speech onset (voiced after unvoiced) within ±1.5 s.
                    if (k > 0) { var best = -1; var bd = 31
                        for (d in -30..30) { val x = f + d; if (x in (fA + 1) until fB && voiced[x] && !voiced[x - 1] && abs(d) < bd) { bd = abs(d); best = x } }
                        if (best >= 0) f = best }
                    newT0[k] = if (k == 0) lines[g[0]].t0 else f * fs
                    acc += chars[k]
                }
                for (k in 1 until g.size) if (newT0[k] <= newT0[k - 1]) newT0[k] = newT0[k - 1] + 0.05
                for (k in g.indices) {
                    val t1 = if (k + 1 < g.size) newT0[k + 1] else maxOf(newT0[k] + 0.5, fB * fs)
                    out[g[k]] = lines[g[k]].copy(t0 = newT0[k], t1 = t1)
                }
            }
        }
        return out
    }

    /** Decode files into one 50 ms dB envelope on the global timeline (missing ranges = silence). */
    fun envelope(files: List<File>, offsetsMs: List<Long>, totalMs: Long): FloatArray {
        val n = (totalMs / FRAME_MS).toInt() + 1
        val db = FloatArray(n) { -100f }
        files.forEachIndexed { fi, f -> runCatching { decodeInto(f, offsetsMs[fi], db) } }
        return db
    }

    private fun decodeInto(f: File, offMs: Long, db: FloatArray) {
        val ex = MediaExtractor(); ex.setDataSource(f.absolutePath)
        val ti = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
        ex.selectTrack(ti)
        val fmt = ex.getTrackFormat(ti)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val info = MediaCodec.BufferInfo()
        var inDone = false; var sampleIdx = 0L
        var sumSq = 0.0; var cnt = 0; var frame = -1
        fun flush() { if (frame in db.indices && cnt > 0) db[frame] = (10 * log10(sumSq / cnt + 1e-9)).toFloat(); sumSq = 0.0; cnt = 0 }
        try {
            while (true) {
                if (!inDone) { val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) { val buf = codec.getInputBuffer(i)!!; val sz = ex.readSampleData(buf, 0)
                        if (sz < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(i, 0, sz, ex.sampleTime, 0); ex.advance() } } }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { val nf = codec.outputFormat
                    rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                else if (o >= 0) {
                    val sb = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val total = info.size / 2
                    var k = 0
                    while (k + ch <= total) {
                        val s = sb.get(info.offset / 2 + k) / 32768.0; k += ch
                        val g = ((offMs + sampleIdx * 1000 / rate) / FRAME_MS).toInt()
                        if (g != frame) { flush(); frame = g }
                        sumSq += s * s; cnt++; sampleIdx++
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            flush()
        } finally { codec.stop(); codec.release(); ex.release() }
    }
}
