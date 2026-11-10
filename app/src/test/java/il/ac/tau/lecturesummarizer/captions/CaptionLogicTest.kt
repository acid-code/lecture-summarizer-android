package il.ac.tau.lecturesummarizer.captions

import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionLogicTest {
    @Test
    fun lineDirectionPrefersHebrew() {
        assertEquals(
            LayoutDirection.Rtl,
            LineDirection.of("האלגוריתם של למידה עמוקה מעדכן את המשקלים", null, LayoutDirection.Ltr),
        )
        assertEquals(
            LayoutDirection.Ltr,
            LineDirection.of("The gradient descent step updates the weights", null, LayoutDirection.Rtl),
        )
        assertEquals(
            LayoutDirection.Rtl,
            LineDirection.of("12", LayoutDirection.Rtl, LayoutDirection.Ltr),
        )
    }

    @Test
    fun glossaryRestoresHebrishTerm() {
        val g = CaptionGlossary(listOf("gradient descent", "backpropagation"))
        assertEquals("gradient descent", g.apply("גרדיאנט דיסנט"))
        val out = g.apply("האלגוריתם של גרדיאנט דיסנט מעדכן")
        assertTrue("got: $out", out.contains("gradient descent"))
    }

    @Test
    fun dictaRejectsTranslation() {
        assertNull(DictaLmEngine.accept("בסדר", "okay"))
        assertEquals("בסדר", DictaLmEngine.accept("בסדר", "בסדר"))
    }
}
