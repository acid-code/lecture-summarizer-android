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
    private fun rs(t: String, ms: Long?) = PanoptoRecording(t.hashCode().toString() + ms, t, "u", ms)

    @Test fun sessionDateUnlocksUndatedTitles() {
        val day = at(2026, 11, 3)
        val recs = listOf(rs("Lecture 5", at(2026, 10, 27)), rs("Lecture 6", at(2026, 11, 3) + 3_600_000), rs("תרגול 6", at(2026, 11, 3)))
        assertEquals("Lecture 6", RecordingMatcher.best(recs, day, ClassKind.LECTURE, null)?.title)
        assertEquals("תרגול 6", RecordingMatcher.best(recs, day, ClassKind.RECITATION, null)?.title)
        assertNull(RecordingMatcher.best(listOf(rs("Lecture 5", at(2026, 10, 27))), day, ClassKind.LECTURE, null))
        assertTrue(RecordingMatcher.needsSessionDate(r("Lecture 6"), day))
        assertFalse(RecordingMatcher.needsSessionDate(r("Lecture 03/11/2026"), day))
    }
    @Test fun sessionDayIsJerusalemDay() {
        // 23:30 UTC on Nov 2 = 01:30 Nov 3 in Jerusalem
        val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, 10, 2, 23, 30) }.timeInMillis
        assertNotNull(RecordingMatcher.best(listOf(rs("Lecture 6", utc)), at(2026, 11, 3), ClassKind.LECTURE, null))
    }
    @Test fun titleAndSessionAgreeingBeatsEither() {
        val day = at(2026, 11, 3)
        val both = rs("Lecture 03/11/2026", day); val titleOnly = r("Lecture 3.11.26"); val sessOnly = rs("Lecture 9", day)
        assertEquals(both.title, RecordingMatcher.best(listOf(sessOnly, titleOnly, both), day, ClassKind.LECTURE, null)?.title)
        assertEquals(titleOnly.title, RecordingMatcher.best(listOf(sessOnly, titleOnly), day, ClassKind.LECTURE, null)?.title)
    }
    @Test fun lecturerStillBreaksSessionTie() {
        val day = at(2026, 11, 3)
        val recs = listOf(rs("תרגול כהן", day), rs("תרגול לוי", day))
        assertEquals("תרגול לוי", RecordingMatcher.best(recs, day, ClassKind.RECITATION, "דנה לוי")?.title)
    }
}
