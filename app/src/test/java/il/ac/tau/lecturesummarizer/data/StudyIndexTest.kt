package il.ac.tau.lecturesummarizer.data
import org.junit.Assert.*
import org.junit.Test
class StudyIndexTest{
 @Test fun queryUsesAllWordsAndIsCaseInsensitive(){assertTrue(StudyIndex.matches("Lecture: plasmid AND cloning","CLONING plasmid"));assertFalse(StudyIndex.matches("plasmid","plasmid antibody"))}
 @Test fun hebrewDiacriticsAndWhitespaceAreNormalized(){assertTrue(StudyIndex.matches("תָּאִים   וחלבונים","תאים וחלבונים"))}
 @Test fun emptyQueryDoesNotMatchEveryLecture(){assertFalse(StudyIndex.matches("anything","  "))}
}
