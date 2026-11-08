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
    @Test fun verboseUnrelatedNotesDoNotWinByVocabularySize(){
        val topic="plasmid ligation restriction cloning vector endonuclease"
        val noise=(1..600).joinToString(" "){"background"+('a'+it%26)+('a'+it/26%26)}
        val pool=listOf(SlideMatcher.Deck(1,"topic",topic),SlideMatcher.Deck(2,"appendix",topic+" "+noise))
        assertEquals(1L,SlideMatcher.rank(pool,topic+" "+noise.split(' ').take(100).joinToString(" ")).first().deck.id)
    }
    @Test fun datedFutureCopyIsPenalizedButUploadIdsAndOrderAreNot(){
        val day=java.time.LocalDate.of(2026,11,2)
        val pool=listOf(SlideMatcher.Deck(999,"new upload",decks[0].text,day.minusDays(1)),SlideMatcher.Deck(1,"future lesson",decks[0].text,day.plusDays(1)))
        assertEquals(999L,SlideMatcher.rank(pool,decks[0].text,day).first().deck.id)
        assertEquals(SlideMatcher.rank(pool,decks[0].text,day),SlideMatcher.rank(pool.reversed(),decks[0].text,day))
        assertNull(SlideMatcher.explicitDate("PPT05 109739 תשפז"))
        assertEquals(day,SlideMatcher.explicitDate("Lecture 02.11.2026.pdf"))
    }
    @Test fun bilingualEvidenceCannotBeDominatedByOneVerboseScript(){
        val anchors="plasmid ligation cloning vector promoter enzyme"
        val hebrew="תאים חלבון אנזימים גנטיקה חיידקים ניסוי"
        val many=(1..200).joinToString(" "){"מילה"+('א'+it%22)+('א'+it/22)}
        val pool=listOf(SlideMatcher.Deck(1,"specific",anchors+" "+hebrew),SlideMatcher.Deck(2,"notes",anchors+" unrelated appendix vocabulary bacteria molecule proteins antibody antigen "+hebrew+" "+many))
        assertEquals(1L,SlideMatcher.rank(pool,anchors+" "+hebrew+" "+many).first().deck.id)
    }
}
