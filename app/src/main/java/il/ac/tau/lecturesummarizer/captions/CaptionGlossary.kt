package il.ac.tau.lecturesummarizer.captions

import android.content.Context
import android.net.Uri
import il.ac.tau.lecturesummarizer.data.Lecture
import il.ac.tau.lecturesummarizer.slides.SlideText
import java.io.File
import java.util.Locale

/**
 * Fast phonetic / edit-distance restore of English slide terms spelled in Hebrew letters.
 * Must finish well under 50 ms for a typical line.
 */
class CaptionGlossary(private val terms: List<String>) {
    data class Entry(val latin: String, val heKey: String)

    private val entries: List<Entry> = terms
        .map { it.trim() }
        .filter { it.length >= 3 && it.any { c -> c.code < 128 && c.isLetter() } }
        .distinctBy { it.lowercase(Locale.ROOT) }
        .map { Entry(it, toHeKey(it)) }
        .filter { it.heKey.length >= 3 }

    fun apply(line: String): String {
        if (entries.isEmpty() || line.isBlank()) return line
        // At most two Hebrew words so "גרדיאנט דיסנט מעדכן" does not swallow the verb.
        val re = Regex("""[\u0590-\u05FF]+(?:\s+[\u0590-\u05FF]+)?""")
        return re.replace(line) { m ->
            val span = m.value
            if (HE_STOP.contains(span.replace(" ", ""))) return@replace span
            val key = compactHe(span)
            val best = entries.minByOrNull { editDistance(it.heKey, key) } ?: return@replace span
            val dist = editDistance(best.heKey, key)
            val maxDist = (best.heKey.length / 3).coerceAtLeast(2)
            if (dist <= maxDist && key.length >= 3) best.latin else span
        }
    }

    fun slideTermsForPrompt(limit: Int = 24): String =
        terms.take(limit).joinToString(", ")

    companion object {
        private val HE_STOP = setOf(
            "של", "את", "על", "עם", "זה", "זו", "זאת", "הוא", "היא", "הם", "הן",
            "אני", "אנחנו", "יש", "אין", "לא", "כן", "גם", "או", "אם", "כי", "מה",
            "מי", "איך", "למה", "אז", "רק", "כל", "עוד", "בין", "אבל", "כמו",
        )

        private val LAT_TO_HE = mapOf(
            'a' to "א", 'b' to "ב", 'c' to "ק", 'd' to "ד", 'e' to "א", 'f' to "פ",
            'g' to "ג", 'h' to "ה", 'i' to "י", 'j' to "ג", 'k' to "ק", 'l' to "ל",
            'm' to "מ", 'n' to "נ", 'o' to "ו", 'p' to "פ", 'q' to "ק", 'r' to "ר",
            's' to "ס", 't' to "ט", 'u' to "ו", 'v' to "ב", 'w' to "ו", 'x' to "קס",
            'y' to "י", 'z' to "ז",
        )

        fun toHeKey(latin: String): String = consonantsOnly(buildString {
            for (c in latin.lowercase(Locale.ROOT)) {
                when {
                    c.isWhitespace() || c == '-' || c == '_' -> Unit
                    c in LAT_TO_HE -> append(LAT_TO_HE[c])
                }
            }
        })

        fun compactHe(s: String): String = consonantsOnly(s.filter { it in '\u0590'..'\u05FF' })

        /** Drop Hebrew matres lectionis so גרדיאנט≈gradient. */
        private fun consonantsOnly(he: String): String =
            he.filter { it !in setOf('א', 'ה', 'ו', 'י') }

        fun editDistance(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            val m = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = m[0]
                m[0] = i
                for (j in 1..b.length) {
                    val tmp = m[j]
                    m[j] = if (a[i - 1] == b[j - 1]) prev else 1 + minOf(prev, m[j], m[j - 1])
                    prev = tmp
                }
            }
            return m[b.length]
        }

        fun fromLecture(context: Context, lecture: Lecture?): CaptionGlossary {
            if (lecture == null) return CaptionGlossary(emptyList())
            val uris = buildList {
                lecture.slidesUri?.let { add(it) }
                addAll(lecture.extraSlides)
            }
            val text = buildString {
                for (u in uris.take(6)) {
                    val file = resolveLocal(context, u) ?: continue
                    append(SlideText.read(context, file))
                    append('\n')
                    if (length > 80_000) break
                }
            }
            val terms = Regex("""[A-Za-z][A-Za-z0-9+.#-]{2,}(?:\s+[A-Za-z][A-Za-z0-9+.#-]{2,}){0,3}""")
                .findAll(text)
                .map { it.value }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .map { it.key }
                .take(80)
            return CaptionGlossary(terms)
        }

        private fun resolveLocal(context: Context, uriStr: String): File? {
            val uri = Uri.parse(uriStr)
            return when (uri.scheme) {
                "file" -> uri.path?.let { File(it) }?.takeIf { it.isFile }
                else -> {
                    // Prefer already-cached PDF under filesDir if path-like.
                    uri.path?.let { p -> File(p).takeIf { it.isFile } }
                }
            }
        }
    }
}
