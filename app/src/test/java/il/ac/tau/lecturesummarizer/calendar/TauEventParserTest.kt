package il.ac.tau.lecturesummarizer.calendar

import org.junit.Assert.*
import org.junit.Test

class TauEventParserTest {
    private val acct = "asafchasid@mail.tau.ac.il"
    private val h = 3600_000L
    private val t0 = 1_000_000_000_000L
    private fun ev(title: String, b: Long, e: Long, a: String? = acct) =
        CalEvent(title, "מרצה: ד\"ר כהן\nמספר קורס: 04552526", "בניין שרמן 001", b, e, a)

    @Test fun lecture() {
        val c = TauEventParser.parse(ev("גנטיקה כללית (שיעור)", t0, t0 + 2 * h))!!
        assertEquals("גנטיקה כללית", c.courseName); assertEquals(ClassKind.LECTURE, c.kind)
        assertEquals("04552526", c.courseNumber); assertEquals("ד\"ר כהן", c.lecturer); assertTrue(c.fromTauCalendar)
    }
    @Test fun recitation() {
        val c = TauEventParser.parse(ev("מבוא לנוירוביולוגיה (תרגיל)", t0, t0 + h))!!
        assertEquals("מבוא לנוירוביולוגיה", c.courseName); assertEquals(ClassKind.RECITATION, c.kind)
    }
    @Test fun overlapPreferredOverSoon() {
        val list = listOf(ev("גנטיקה כללית (שיעור)", t0, t0 + 2 * h), ev("מבוא לנוירוביולוגיה (תרגיל)", t0 + 2 * h, t0 + 3 * h))
        val c = TauEventParser.candidates(list, t0 + 2 * h - 5 * 60_000)
        assertEquals(1, c.size); assertEquals(ClassKind.LECTURE, c[0].kind)
    }
    @Test fun earlyFallback() {
        val c = TauEventParser.candidates(listOf(ev("גנטיקה כללית (שיעור)", t0, t0 + h)), t0 - 5 * 60_000)
        assertEquals(1, c.size)
        assertTrue(TauEventParser.candidates(listOf(ev("גנטיקה כללית (שיעור)", t0, t0 + h)), t0 - 30 * 60_000).isEmpty())
    }
    @Test fun personalNoiseIgnored() {
        val c = TauEventParser.candidates(listOf(ev("ארוחת צהריים", t0, t0 + h, "me@gmail.com"), ev("גנטיקה כללית (שיעור)", t0, t0 + h)), t0 + 10 * 60_000)
        assertEquals(listOf("גנטיקה כללית"), c.map { it.courseName })
    }
}
