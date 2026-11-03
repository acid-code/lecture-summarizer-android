package il.ac.tau.lecturesummarizer.moodle

import org.junit.Assert.*
import org.junit.Test

class AutoPickTest {
    private fun f(id: Long, n: String) = MoodleFile(id, n, "", "resource") to n

    @Test fun uniqueNumberMatch() = assertEquals(2L, MoodleMatch.autoPick(listOf(f(1, "lecture2.pdf"), f(2, "lecture3.pdf")), "Lecture 3", null)?.cmid)
    @Test fun pdfWinsOverSameStemPptx() = assertEquals(5L, MoodleMatch.autoPick(listOf(f(4, "Lec_03.pptx"), f(5, "Lec_03.pdf")), "Lecture 3", null)?.cmid)
    @Test fun ambiguousIsNull() = assertNull(MoodleMatch.autoPick(listOf(f(1, "lecture3.pdf"), f(2, "lecture3 notes.pdf")), "Lecture 3", null))
    @Test fun oppositeKindIgnored() = assertEquals(1L, MoodleMatch.autoPick(listOf(f(1, "lecture3.pdf"), f(2, "recitation3.pdf")), "Lecture 3", null)?.cmid)
    @Test fun nonSlidesIgnored() = assertNull(MoodleMatch.autoPick(listOf(f(1, "lecture3.docx")), "Lecture 3", null))
    @Test fun dateFallback() = assertEquals(1L, MoodleMatch.autoPick(listOf(f(1, "slides 3.11.pdf")), "Recording", 3 to 11)?.cmid)
}
