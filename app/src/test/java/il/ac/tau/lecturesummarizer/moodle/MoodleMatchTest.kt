package il.ac.tau.lecturesummarizer.moodle

import il.ac.tau.lecturesummarizer.calendar.ClassKind
import org.junit.Assert.*
import org.junit.Test

class MoodleMatchTest {
    private val lec = MoodleCourse(1, "גנטיקה - שיעור", "0455210101", "")
    private val rec = MoodleCourse(2, "גנטיקה - תרגיל", "0455210109", "")
    private val other = MoodleCourse(3, "Other", "0368215701", "")

    @Test fun lectureSuffix() {
        val r = MoodleMatch.rank(listOf(lec, rec, other), "04552101", ClassKind.LECTURE, null)
        assertEquals(lec, MoodleMatch.confident(r))
    }
    @Test fun recitationSuffix() {
        assertEquals(rec, MoodleMatch.confident(MoodleMatch.rank(listOf(lec, rec), "0455-2101", ClassKind.RECITATION, null)))
    }
    @Test fun ambiguousWhenKindUnknown() {
        assertNull(MoodleMatch.confident(MoodleMatch.rank(listOf(lec, rec), "04552101", ClassKind.UNKNOWN, null)))
    }
    @Test fun suggestedDate() {
        assertTrue(MoodleMatch.suggested("הרצאה 3 - 12.11.pdf", 12 to 11))
        assertFalse(MoodleMatch.suggested("lecture 112.11.pdf", 2 to 11))
    }
}
