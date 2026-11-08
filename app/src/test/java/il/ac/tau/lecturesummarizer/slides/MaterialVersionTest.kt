package il.ac.tau.lecturesummarizer.slides
import org.junit.Assert.*
import org.junit.Test
class MaterialVersionTest {
 @Test fun differentDeckSetsHaveDifferentVersions(){assertNotEquals(SummaryFreshness.revision(listOf("deck1")),SummaryFreshness.revision(listOf("deck2")));assertNotEquals(SummaryFreshness.revision(emptyList()),SummaryFreshness.revision(listOf("deck1")))}
 @Test fun orderAndGenerationArePartOfVersion(){assertNotEquals(SummaryFreshness.revision(listOf("a","b")),SummaryFreshness.revision(listOf("b","a")));assertNotEquals(SummaryFreshness.revision(listOf("a","generation:1")),SummaryFreshness.revision(listOf("a","generation:2")))}
 @Test fun nonSlidesCannotEnterTheAutomaticCandidatePool(){assertTrue(SlideCandidates.isDeck("Lecture.PPTX"));assertTrue(SlideCandidates.isDeck("Lecture","https://site.edu/week3.pdf?download=1"));listOf("notes.docx","lecture.mp4","script.py","archive.zip").forEach{assertFalse(it,SlideCandidates.isDeck(it))}}
}
