package il.ac.tau.lecturesummarizer.ui

import il.ac.tau.lecturesummarizer.data.TranscriptLine
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SyncAlignTest {
    /** 20 sentences of equal length, each 8 s of speech + 0.6 s pause. ASR times drift by +8% (stretched). */
    @Test fun driftingTimestampsAreCorrected() {
        val n = 20; val speech = 8.0; val pause = 0.6
        val trueT0 = (0 until n).map { it * (speech + pause) }
        val total = n * (speech + pause)
        val fs = 0.05
        val voiced = BooleanArray((total / fs).toInt()) { f -> val t = f * fs; (t % (speech + pause)) < speech }
        val asr = trueT0.mapIndexed { i, t -> TranscriptLine(t * 0.92, t * 0.92 + speech, "x".repeat(80)) }
        val out = SyncAlign.realign(asr, voiced, listOf(total))
        val worstBefore = asr.indices.maxOf { abs(asr[it].t0 - trueT0[it]) }
        val worstAfter = out.indices.maxOf { abs(out[it].t0 - trueT0[it]) }
        println("worst before=$worstBefore after=$worstAfter")
        assertTrue(worstBefore > 10)
        assertTrue(worstAfter < 1.0)
    }
}
