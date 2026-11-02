package il.ac.tau.lecturesummarizer.moodle

import org.junit.Assert.*
import org.junit.Test

class RecordingMatchTest {
    @Test fun matchesSameLectureSlides() {
        assertTrue(MoodleMatch.matchesRecording("lecture3.pdf", "Lecture 3"))
        assertTrue(MoodleMatch.matchesRecording("Lec_03 slides.pptx", "Lecture 3"))
        assertTrue(MoodleMatch.matchesRecording("Lecture 3", "Lecture 3")) // activity name, no extension
    }
    @Test fun rejectsOthers() {
        assertFalse(MoodleMatch.matchesRecording("lecture4.pdf", "Lecture 3"))
        assertFalse(MoodleMatch.matchesRecording("recitation3.pdf", "Lecture 3"))
        assertFalse(MoodleMatch.matchesRecording("lecture3.docx", "Lecture 3"))
        assertFalse(MoodleMatch.matchesRecording("lecture3.pdf", null))
        assertFalse(MoodleMatch.matchesRecording("lecture13.pdf", "Lecture 3"))
    }
}
