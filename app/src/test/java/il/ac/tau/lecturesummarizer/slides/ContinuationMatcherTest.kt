package il.ac.tau.lecturesummarizer.slides
import il.ac.tau.lecturesummarizer.data.*
import org.junit.Assert.*
import org.junit.Test
class ContinuationMatcherTest{
 private val a=listOf("alanine peptide glycine protein enzyme","keratin collagen elastin helix sheet","myosin actin troponin muscle contraction","tryptophan histidine tyrosine residue aromatic")
 private val b=listOf("chromosome homologous meiosis crossover linkage","centromere chromatid spindle segregation meiosis","recombination mapping distance locus marker","haplotype pedigree genotype allele mutation")
 private val decks=listOf(SlideMatcher.Deck(2,"Slides 2.pdf",a.joinToString("\u000c")),SlideMatcher.Deck(3,"Slides 3.pdf",b.joinToString("\u000c")),SlideMatcher.Deck(4,"Slides 4.pdf","unrelated spectrometry mass spectrometer isotope"))
 private fun lines(texts:List<String>)=texts.mapIndexed{i,t->TranscriptLine(i*60.0,(i+1)*60.0,t)}
 @Test fun reusedDeckIsNotConsumed(){val result=ContinuationMatcher.suggest(decks,lines(a+a),listOf(2));assertEquals(listOf(2L),result.suggestions.map{it.match.deck.id});assertTrue(result.suggestions.single().continued)}
 @Test fun endOfPreviousThenNextCanSuggestBoth(){val result=ContinuationMatcher.suggest(decks,lines(a.takeLast(2)+b.take(2)),listOf(2));assertEquals(listOf(2L,3L),result.suggestions.map{it.match.deck.id});assertTrue(result.suggestions.first().continued);assertNotNull(result.suggestions.first().range)}
 @Test fun nextWindowDoesNotRequireLectureNumberEqualsDeckNumber(){val result=ContinuationMatcher.suggest(decks,lines(b+b),listOf(2));assertEquals(3L,result.suggestions.single().match.deck.id);assertEquals(listOf(2L,3L),result.candidates.map{it.deck.id}.sorted())}
 @Test fun reverseOrderDoesNotInventContinuation(){val result=ContinuationMatcher.suggest(decks,lines(b.take(2)+a.takeLast(2)),listOf(2));assertEquals(1,result.suggestions.size)}
 @Test fun commonTermsAreNotEvidenceForTwoDecks(){val same=listOf(SlideMatcher.Deck(2,"Slides 2.pdf",a.joinToString(" ")),SlideMatcher.Deck(3,"Slides 3.pdf",a.joinToString(" ")));assertEquals(1,ContinuationMatcher.suggest(same,lines(a),listOf(2)).suggestions.size)}
 @Test fun unknownPagesNeverProduceMadeUpRange(){val d=SlideMatcher.Deck(9,"Overview.pdf",a.joinToString(" "));assertNull(ContinuationMatcher.suggest(listOf(d),lines(a)).suggestions.single().range)}
 @Test fun absentNextDoesNotJumpToLaterDeck(){assertEquals(listOf(2L),ContinuationMatcher.suggest(decks.filter{it.id!=3L},lines(a),listOf(2)).candidates.map{it.deck.id})}
 @Test fun filenameIndexIsNotCacheIdOrLectureDate(){assertEquals(2,ContinuationMatcher.deckNumber("87659_Lecture 2__27_Families.pptx"));assertEquals(3,ContinuationMatcher.deckNumber("Slides 3.pdf"));assertNull(ContinuationMatcher.deckNumber("206394_amino_acids2Proteins _26new.pdf"))}
 @Test fun rangesRoundTripAndSharedDeckIsPerLecture(){val uri="content://deck2";val first=Lecture("one","course","Lecture 3",1,slidesUri=uri,slideRanges=mapOf(uri to SlideRange(5,9)));val second=first.copy(id="two",extraSlides=listOf("content://deck3"),slideRanges=mapOf(uri to SlideRange(10,15)));assertEquals(SlideRange(5,9),lectureFromJson(first.toJson()).slideRanges[uri]);assertEquals(2,second.allSlides.size);assertEquals(SlideRange(5,9),first.slideRanges[uri])}
 @Test fun badRangesRejected(){assertThrows(IllegalArgumentException::class.java){SlideRange(0,2)};assertThrows(IllegalArgumentException::class.java){SlideRange(4,2)}}
 @Test fun identicalFriendlyNamesKeepBothDeckUploads(){val l=Lecture("l","c","Lecture",1,slidesUri="content://a",extraSlides=listOf("content://b"));val a=slideUploadName(l,"content://a"){"slides.pdf"};val b=slideUploadName(l,"content://b"){"slides.pdf"};assertNotEquals(a,b);assertEquals(a,slideUploadName(l,"content://a"){"slides.pdf"});assertEquals("slides.pdf",slideUploadName(l.copy(extraSlides=emptyList()),"content://a"){"slides.pdf"})}
 @Test fun explicitCourseSequenceSupportsUnnumberedDecks(){val named=listOf(decks[0].copy(name="Amino acids.pdf"),decks[1].copy(name="Proteins.pdf"));val result=ContinuationMatcher.suggest(named,lines(a.takeLast(2)+b.take(2)),listOf(2),sequenceOverrides=mapOf(2L to 2,3L to 3));assertEquals(listOf(2L,3L),result.suggestions.map{it.match.deck.id})}
}
