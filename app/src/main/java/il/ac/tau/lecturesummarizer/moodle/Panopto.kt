package il.ac.tau.lecturesummarizer.moodle

/** One timed caption cue (seconds). */
data class CaptionSegment(val t0: Double, val t1: Double, val text: String, val speaker: String? = null)

/** A recording listed in the course's Panopto side block. */
data class PanoptoRecording(val deliveryId: String, val title: String, val viewerUrl: String)

/** Pure parsing helpers (no Android deps → unit-testable). */
object Panopto {
    const val HOST = "https://tau.cloud.panopto.eu"
    const val BLOCK_TITLE = "הקלטות הקורס בשרת הוידאו Panopto"
    private val GUID = """[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"""

    const val NO_CAPTIONS = "Panopto has no captions for this recording yet. Record the lecture instead, or choose another recording."

    fun isLoginRedirect(location: String?): Boolean =
        location != null && (location.contains("/Auth/", true) || location.contains("Login", true))

    fun captionsUrl(deliveryId: String, language: Int = 0) =
        "$HOST/Panopto/Pages/Transcription/GenerateSRT.ashx?id=$deliveryId&language=$language"

    /**
     * Recordings from a Moodle course page. Scopes to the Panopto block (by its title) when present,
     * otherwise scans the whole page. Dedupes by delivery id, keeps page order.
     */
    fun extractRecordings(html: String): List<PanoptoRecording> {
        val start = html.indexOf(BLOCK_TITLE).takeIf { it >= 0 }
            ?: Regex("""block_panopto""").find(html)?.range?.first ?: 0
        val scope = html.substring(start)
        // Server-rendered block markup uses single-quoted hrefs (href='…Viewer.aspx?id=…'); browser DOM uses double.
        // Items beyond the first 3 sit in a hidden <div id='hiddenLecturesDiv'> behind "[תצוגה מלאה]" — same HTML, just parsed.
        val re = Regex("""<a\b[^>]*?href\s*=\s*(["'])([^"']*?/Panopto/Pages/Viewer\.aspx\?[^"']*?\bid=($GUID)[^"']*)\1[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return re.findAll(scope).map { m ->
            val title = MoodleClient.unescape(m.groupValues[4].replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim())
            PanoptoRecording(m.groupValues[3].lowercase(), title.ifBlank { "Recording" }, MoodleClient.unescape(m.groupValues[2]))
        }.filterNot { it.title.contains("תצוגה מלאה") }.distinctBy { it.deliveryId }.toList()
    }

    /** Block still shows its async placeholder ("מאחזר תוכן…" / loading_text) and no recording links yet. */
    fun isLoadingPlaceholder(html: String): Boolean =
        extractRecordings(html).isEmpty() && (html.contains("loading_text") || html.contains("מאחזר תוכן") || html.contains("block_panopto/asyncload"))

    /** "he" when any cue has Hebrew letters, else "en". */
    fun languageOf(segs: List<CaptionSegment>): String =
        if (segs.any { s -> s.text.any { it in '\u0590'..'\u05FF' } }) "he" else "en"

    private val TIME = Regex("""(\d+):(\d{2}):(\d{2})[,.](\d{1,3})""")
    private fun sec(m: MatchResult): Double {
        val (h, mi, s, ms) = m.destructured
        return h.toInt() * 3600 + mi.toInt() * 60 + s.toInt() + ms.padEnd(3, '0').toInt() / 1000.0
    }

    /** Parse SRT. Drops Panopto's "[Auto-generated transcript…]" line and splits a leading "Name: " speaker prefix. */
    fun parseSrt(srt: String): List<CaptionSegment> {
        val blocks = srt.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n\\s*\n"))
        return blocks.mapNotNull { b ->
            val lines = b.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val ti = lines.indexOfFirst { "-->" in it }.takeIf { it >= 0 } ?: return@mapNotNull null
            val times = TIME.findAll(lines[ti]).toList().takeIf { it.size >= 2 } ?: return@mapNotNull null
            val textLines = lines.drop(ti + 1).filterNot { it.startsWith("[Auto-generated transcript", ignoreCase = true) }
            if (textLines.isEmpty()) return@mapNotNull null
            var text = textLines.joinToString(" ")
            var speaker: String? = null
            Regex("""^([^:\[\]]{2,40}):\s+(.+)$""").find(text)?.let { speaker = it.groupValues[1].trim(); text = it.groupValues[2] }
            CaptionSegment(sec(times[0]), sec(times[1]), text.trim(), speaker)
        }
    }
}
