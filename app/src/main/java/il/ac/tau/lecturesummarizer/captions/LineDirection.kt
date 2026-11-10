package il.ac.tau.lecturesummarizer.captions

import androidx.compose.ui.unit.LayoutDirection

object LineDirection {
    fun of(text: String, previous: LayoutDirection?, fallback: LayoutDirection): LayoutDirection {
        var he = 0
        var la = 0
        for (c in text) {
            when {
                c in '\u0590'..'\u05FF' -> he++
                c.isLetter() && c.code < 0x80 -> la++
            }
        }
        return when {
            he > la -> LayoutDirection.Rtl
            la > he -> LayoutDirection.Ltr
            else -> previous ?: fallback
        }
    }
}
