package il.ac.tau.lecturesummarizer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorAnswerTest {
    private val d = "${'$'}"

    @Test fun paragraphsSplitOnBlankLines() {
        val blocks = parseTutorBlocks("שורה אחת\n\nשורה שתיים\nעם המשך")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is TutorBlock.Paragraph)
        assertTrue((blocks[1] as TutorBlock.Paragraph).text.contains("עם המשך"))
    }

    @Test fun detectsMathDelimiters() {
        assertTrue(hasTutorMath("חסם \\(\\varepsilon\\)"))
        assertTrue(hasTutorMath(d + d + "m=1" + d + d))
        assertFalse(hasTutorMath("בלי נוסחאות בכלל"))
    }

    @Test fun displayMathBecomesOwnBlock() {
        val raw = "לפני\n\n" + d + d + "\\frac{1}{2}" + d + d + "\n\nאחרי"
        val blocks = parseTutorBlocks(raw)
        assertTrue(blocks.any { it is TutorBlock.DisplayMath })
        assertEquals("\\frac{1}{2}", blocks.filterIsInstance<TutorBlock.DisplayMath>().single().tex)
        assertTrue(blocks.first() is TutorBlock.Paragraph)
        assertTrue(blocks.last() is TutorBlock.Paragraph)
    }

    @Test fun citeMarkerSurvivesParagraphParse() {
        val blocks = parseTutorBlocks("טענה חשובה [1].\n\nעוד משפט [2].")
        assertEquals(2, blocks.size)
        assertTrue((blocks[0] as TutorBlock.Paragraph).text.contains("[1]"))
        assertTrue((blocks[1] as TutorBlock.Paragraph).text.contains("[2]"))
    }

    @Test fun hebrewDominantDetectsRtl() {
        assertTrue(hebrewDominant("הסבר על VC dimension"))
        assertFalse(hebrewDominant("Explain VC dimension only"))
    }
}
