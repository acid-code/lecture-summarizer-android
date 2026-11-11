package il.ac.tau.lecturesummarizer.captions

/**
 * Choose between Hebrew-Zipformer and English-Zipformer hypotheses.
 * HE model alone often forces English into Hebrew letters or a bad translation.
 */
object AsrPick {
    fun choose(he: String, en: String): String {
        val h = he.trim()
        val e = en.trim()
        if (h.isEmpty()) return e
        if (e.isEmpty()) return h

        val heHe = countHe(h)
        val heLa = countLa(h)
        val enLa = countLa(e)
        val enHe = countHe(e)

        // English model produced Hebrew? Prefer HE.
        if (enHe > enLa) return h

        // Pure / mostly English utterance: HE wrote only Hebrew → take EN.
        if (enLa >= 3 && heLa == 0 && heHe >= 2 && looksLikeEnglish(e)) {
            if (looksLikeHebrishSpell(h) || heHe >= enLa) return e
        }

        // HE already mixed with Latin — keep HE (glossary will fix loans).
        if (heLa > 0 && heHe >= heLa) return h
        if (heLa > heHe) return h

        // Strong English, weak HE Latin.
        if (enLa >= 8 && heLa == 0 && looksLikeEnglish(e)) return e

        return h
    }

    private fun countHe(s: String) = s.count { it in '\u0590'..'\u05FF' }
    private fun countLa(s: String) = s.count { it.isLetter() && it.code < 128 }

    private fun looksLikeEnglish(s: String): Boolean {
        val words = s.split(Regex("""\s+""")).filter { it.any { c -> c.isLetter() } }
        if (words.isEmpty()) return false
        val latinWords = words.count { w -> w.any { it.isLetter() && it.code < 128 } && !w.any { it in '\u0590'..'\u05FF' } }
        return latinWords * 2 >= words.size
    }

    /** Letter-by-letter Hebrish or very short spaced Hebrew tokens. */
    private fun looksLikeHebrishSpell(s: String): Boolean {
        val tokens = s.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (tokens.size >= 4 && tokens.count { it.length <= 2 } >= tokens.size / 2) return true
        // Dense Hebrew with no Latin on what should be an English phrase.
        return countHe(s) >= 6 && countLa(s) == 0 && tokens.size <= 3
    }
}
