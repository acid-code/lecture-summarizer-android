package il.ac.tau.lecturesummarizer.autosync

import il.ac.tau.lecturesummarizer.calendar.ClassKind
import il.ac.tau.lecturesummarizer.moodle.PanoptoRecording
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Offline simulation of semester 2026-10-18 → 2026-11-03 (Asia/Jerusalem). Panopto titles follow what TAU actually shows
 * (block lists "Lecture 1–3"/"Recitation N"; DeliveryInfo SessionName "אלגוריתמים – שיעור 1 – חיפוש לרוחב") — no dates.
 * Compares old title-only matching (startMs = null) vs new title+session-date.
 */
class SemesterSimulationTest {
    private fun at(m: Int, d: Int, h: Int) = Calendar.getInstance(RecordingMatcher.TZ).apply { clear(); set(2026, m - 1, d, h, 10) }.timeInMillis
    data class Cls(val course: String, val kind: ClassKind, val begin: Long)

    @Test fun semester() {
        // course → (weekday, hour, kind, titleFmt). Calendar.SUNDAY=1.
        val spec = listOf(
            Triple("אלגוריתמים", ClassKind.LECTURE, Triple(Calendar.TUESDAY, 15, "אלגוריתמים – שיעור %d")),
            Triple("אלגוריתמים", ClassKind.RECITATION, Triple(Calendar.THURSDAY, 10, "Recitation %d")),
            Triple("קורס B", ClassKind.LECTURE, Triple(Calendar.SUNDAY, 12, "Lecture %d")),
            Triple("קורס B", ClassKind.RECITATION, Triple(Calendar.MONDAY, 14, "תרגול %d")),
            Triple("קורס C", ClassKind.LECTURE, Triple(Calendar.WEDNESDAY, 9, "שיעור %d - %s")), // dated title control
        )
        val now = at(11, 3, 20)
        val classes = mutableListOf<Cls>(); val recs = mutableMapOf<String, MutableList<PanoptoRecording>>()
        for ((course, kind, w) in spec) {
            val c = Calendar.getInstance(RecordingMatcher.TZ).apply { timeInMillis = at(10, 18, w.second) }
            while (c.get(Calendar.DAY_OF_WEEK) != w.first) c.add(Calendar.DAY_OF_MONTH, 1)
            var n = 1
            while (c.timeInMillis + 3_600_000 <= now) {
                val b = c.timeInMillis; classes += Cls(course, kind, b)
                val ymd = RecordingMatcher.ymdOf(b)
                // the most recent class of "קורס B" lecture: not uploaded yet (realistic 24–48h lag)
                if (!(course == "קורס B" && kind == ClassKind.LECTURE && now - b < 3 * 86_400_000L)) {
                    val t = if (w.third.contains("%s")) w.third.format(n, "%02d.%02d".format(ymd.d, ymd.m)) else w.third.format(n)
                    recs.getOrPut(course) { mutableListOf() } += PanoptoRecording("$course-$kind-$n", t, "u", b + 5 * 60_000)
                }
                n++; c.add(Calendar.DAY_OF_MONTH, 7)
            }
        }
        var oldHit = 0; var newHit = 0; var wrong = 0; val miss = mutableListOf<String>()
        for (k in classes) {
            val pool = recs[k.course].orEmpty()
            if (RecordingMatcher.best(pool.map { it.copy(startMs = null) }, k.begin, k.kind, null) != null) oldHit++
            val r = RecordingMatcher.best(pool, k.begin, k.kind, null)
            if (r != null) { newHit++; if (!RecordingMatcher.sameDay(r.startMs!!, k.begin)) wrong++ }
            else RecordingMatcher.ymdOf(k.begin).let { miss += "${k.course} ${k.kind} ${it.d}/${it.m}" }
        }
        println("SIM classes=${classes.size} old=$oldHit new=$newHit wrong=$wrong miss=$miss")
        assertTrue(newHit > oldHit && wrong == 0)
    }
}
