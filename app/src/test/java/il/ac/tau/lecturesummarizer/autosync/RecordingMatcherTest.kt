package il.ac.tau.lecturesummarizer.autosync

import il.ac.tau.lecturesummarizer.calendar.ClassKind
import il.ac.tau.lecturesummarizer.moodle.PanoptoRecording
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class RecordingMatcherTest {
    private fun at(y: Int, m: Int, d: Int) = Calendar.getInstance(RecordingMatcher.TZ).apply { clear(); set(y, m - 1, d, 10, 10) }.timeInMillis
    private fun r(t: String) = PanoptoRecording(t.hashCode().toString(), t, "u")

    @Test fun matchesDateAndKind() {
        val recs = listOf(r("שיעור 3 - 27/10/2026"), r("תרגיל 3 - 03/11/2026"), r("שיעור 4 - 03/11/2026"))
        assertEquals("שיעור 4 - 03/11/2026", RecordingMatcher.best(recs, at(2026, 11, 3), ClassKind.LECTURE, null)?.title)
        assertEquals("תרגיל 3 - 03/11/2026", RecordingMatcher.best(recs, at(2026, 11, 3), ClassKind.RECITATION, null)?.title)
    }
    @Test fun lecturerBreaksTie() {
        val recs = listOf(r("תרגיל 03.11.26 כהן"), r("תרגיל 03.11.26 לוי"))
        assertEquals("תרגיל 03.11.26 לוי", RecordingMatcher.best(recs, at(2026, 11, 3), ClassKind.RECITATION, "ד\"ר דנה לוי")?.title)
    }
    @Test fun isoAndNoDate() {
        assertNotNull(RecordingMatcher.best(listOf(r("Lecture 2026-11-03")), at(2026, 11, 3), ClassKind.LECTURE, null))
        assertNull(RecordingMatcher.best(listOf(r("Lecture 5")), at(2026, 11, 3), ClassKind.LECTURE, null))
        assertNull(RecordingMatcher.best(listOf(r("Lecture 3/11/2025")), at(2026, 11, 3), ClassKind.LECTURE, null))
    }
}
