package il.ac.tau.lecturesummarizer.slides

import org.junit.Assert.*
import org.junit.Test

class SlideMatcherTest {
    private val decks = listOf(
        SlideMatcher.Deck(3, "PPT03.pptx", "biotechnology plasmid ligation restriction endonuclease cloning vector"),
        SlideMatcher.Deck(4, "PPT04.pptx", "biotechnology transcription promoter polymerase operon expression regulation"),
        SlideMatcher.Deck(5, "PPT05.pptx", "biotechnology antibody antigen immunoglobulin epitope vaccination"))
    @Test fun earlyRequiresExplicitUniqueLectureNumber() {
        assertEquals(3L, SlideMatcher.early(decks.map { it.id to it.name }, "Lecture 3"))
        assertNull(SlideMatcher.early(decks.map { it.id to it.name }, "28/10"))
        for (s in listOf("שיעור 28/10", "Lecture 02/11", "Lecture 28.10", "Lecture 02-11", "20261028", "0455223701")) assertNull(s, SlideMatcher.lectureNumber(s))
        assertNull(SlideMatcher.early(listOf(3L to "PPT03.pdf", 30L to "Lecture3.pptx"), "הרצאה 3"))
    }
    @Test fun biotechMappingRegressionUsesContentNotDate() {
        // Synthetic content fixtures: these are NOT assertions on the owner's real Moodle decks/transcripts.
        val examples = mapOf("28/10" to "plasmid ligation restriction endonuclease cloning vector", "02/11" to "transcription promoter polymerase operon expression regulation")
        assertEquals("PPT03.pptx", SlideMatcher.clearWinner(SlideMatcher.rank(decks, examples.getValue("28/10")))?.deck?.name)
        assertEquals("PPT04.pptx", SlideMatcher.clearWinner(SlideMatcher.rank(decks, examples.getValue("02/11")))?.deck?.name)
    }
    @Test fun nearEqualAndWeakNeverAutoAttach() {
        assertNull(SlideMatcher.clearWinner(SlideMatcher.rank(decks, "biotechnology lecture course slides")))
        assertNull(SlideMatcher.clearWinner(SlideMatcher.rank(decks, decks[0].text + " " + decks[1].text)))
        assertNull(SlideMatcher.clearWinner(SlideMatcher.rank(emptyList(), "plasmid")))
        assertNull(SlideMatcher.clearWinner(SlideMatcher.rank(decks, "plasmid ligation")))
    }
    @Test fun unicodeAndDuplicatesAreHandled() {
        assertEquals(setOf("אנזימים", "plasmid"), SlideMatcher.keywords("אנזימים PLASMID plasmid את עם הרצאה"))
        val a = SlideMatcher.rank(decks, decks[0].text).first().score
        val b = SlideMatcher.rank(decks, (decks[0].text + " ").repeat(100)).first().score
        assertEquals(a, b, 0.00001)
    }
}
