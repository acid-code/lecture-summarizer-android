package il.ac.tau.lecturesummarizer.moodle

import il.ac.tau.lecturesummarizer.calendar.ClassKind
import org.junit.Assert.*
import org.junit.Test

class AutoPickTest {
    private fun f(id: Long, n: String) = MoodleFile(id, n, "", "resource") to n

    @Test fun uniqueNumberMatch() = assertEquals(2L, MoodleMatch.autoPick(listOf(f(1, "lecture2.pdf"), f(2, "lecture3.pdf")), "Lecture 3", null)?.cmid)
    @Test fun pdfWinsOverSameStemPptx() = assertEquals(5L, MoodleMatch.autoPick(listOf(f(4, "Lec_03.pptx"), f(5, "Lec_03.pdf")), "Lecture 3", null)?.cmid)
    @Test fun notesLoseToPlainWhenAmbiguous() = assertEquals(1L, MoodleMatch.autoPick(listOf(f(1, "lecture3.pdf"), f(2, "lecture3 notes.pdf")), "Lecture 3", null)?.cmid)
    @Test fun oppositeKindIgnored() = assertEquals(1L, MoodleMatch.autoPick(listOf(f(1, "lecture3.pdf"), f(2, "recitation3.pdf")), "Lecture 3", null)?.cmid)
    @Test fun nonSlidesIgnored() = assertNull(MoodleMatch.autoPick(listOf(f(1, "lecture3.docx")), "Lecture 3", null))
    @Test fun dateFallback() = assertEquals(1L, MoodleMatch.autoPick(listOf(f(1, "slides 3.11.pdf")), "Recording", 3 to 11)?.cmid)

    private val neuro = "0455223701 - מבוא לנוירוביולוגיה תרגול 1"
    @Test fun courseIdPrefixedTutorialPicked() = assertEquals(7L, MoodleMatch.autoPick(listOf(
        f(6, "Lecture 1 - Introduction.pdf"), f(7, "Tutorial 1 - Organization of the Nervous System.pptx"), f(8, "Tutorial 2 - Neurons.pptx")), neuro, null)?.cmid)
    @Test fun courseIdPrefixedAmbiguousNull() = assertNull(MoodleMatch.autoPick(listOf(
        f(7, "Tutorial 1 - Organization.pptx"), f(9, "Tutorial 1 - extra.pdf")), neuro, null))
    @Test fun lectureIndexIgnoresCourseId() {
        assertEquals(1, MoodleMatch.lectureIndex(neuro)); assertEquals(3, MoodleMatch.lectureIndex("Lec_03 slides"))
        assertNull(MoodleMatch.lectureIndex("0455223701 recording"))
    }
    @Test fun tutorialIsRecitation() = assertEquals(ClassKind.RECITATION, MoodleMatch.kindFromTitle("Tutorial 1 - x.pptx"))

    @Test fun panoptoMidTitleKinds() {
        assertEquals(ClassKind.LECTURE, MoodleMatch.kindFromTitle("Introduction to Machine Learning Lecture"))
        assertEquals(ClassKind.RECITATION, MoodleMatch.kindFromTitle("Introduction to Machine Learning - Recitation 3 (Yoni - 2)"))
    }

    @Test fun lecturePreferKindIgnoresRecitationHandout() = assertEquals(
        4L,
        MoodleMatch.autoPick(
            listOf(f(3, "Recitation 3 (handout).pdf"), f(4, "lecture4.pdf")),
            "Lecture 4",
            null,
            ClassKind.LECTURE,
        )?.cmid,
    )

    @Test fun dateFallbackRespectsPreferKind() = assertNull(
        MoodleMatch.autoPick(
            listOf(f(1, "Recitation 3.11.pdf"), f(2, "tutorial 3.11.pdf")),
            "Recording",
            3 to 11,
            ClassKind.LECTURE,
        ),
    )

    @Test fun calendarDateIsNotLectureIndex() {
        assertNull(MoodleMatch.lectureIndex("שיעור 03/11"))
        assertNull(MoodleMatch.lectureIndex("תרגיל 21/10"))
        assertNull(MoodleMatch.lectureIndex("Lecture - 1.11.2026"))
        assertEquals(3, MoodleMatch.lectureIndex("Lecture 3"))
        assertEquals(1, MoodleMatch.lectureIndex("תרגול 1"))
    }

    @Test fun commonWordsPickRecitationTopic() = assertEquals(
        2L,
        MoodleMatch.autoPick(
            listOf(f(1, "תרגול 1 - הורשה מנדלית.pdf"), f(2, "תרגול 2 - תאחיזה למין וסוגי דם.pdf")),
            "גנטיקה - תרגול 2 - תאחיזה למין וסוגי דם",
            null,
            ClassKind.RECITATION,
        )?.cmid,
    )

    @Test fun soleKindCompatibleDeckIsPicked() = assertEquals(
        9L,
        MoodleMatch.autoPick(
            listOf(f(9, "amino_acids2Proteins _26new.pdf"), f(1, "Tirgul1 student.pptx")),
            "שיעור 25/10",
            25 to 10,
            ClassKind.LECTURE,
        )?.cmid,
    )

    @Test fun lecture4BeatsNotesByCommonWords() = assertEquals(
        4L,
        MoodleMatch.autoPick(
            listOf(f(3, "Recitation 3 (handout).pdf"), f(4, "lecture4.pdf"), f(5, "lecture4_notes.pdf")),
            "Introduction to Machine Learning Lecture",
            8 to 11,
            ClassKind.LECTURE,
        )?.cmid,
    )
}
