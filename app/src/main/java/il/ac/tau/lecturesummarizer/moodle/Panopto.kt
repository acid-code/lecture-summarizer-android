package il.ac.tau.lecturesummarizer.moodle

/** One timed caption cue (seconds). */
data class CaptionSegment(val t0: Double, val t1: Double, val text: String, val speaker: String? = null)

/** A recording listed in the course's Panopto side block. */
data class PanoptoRecording(val deliveryId: String, val title: String, val viewerUrl: String)

/** Pure parsing helpers (no Android deps → unit-testable). */
object Panopto {
    /** Lecture title from a Panopto recording title: trimmed, whitespace collapsed, course-name prefix kept as-is ("Lecture 3", "תרגול 1"). */
    fun lectureTitle(recTitle: String): String = recTitle.replace(Regex("""\s+"""), " ").trim().ifBlank { "Panopto recording" }
    const val HOST = "https://tau.cloud.panopto.eu"

    /** True when the WebView already holds a Panopto forms-auth session (Viewer loaded = signed in). */
    fun hasSessionCookie(): Boolean {
        val c = android.webkit.CookieManager.getInstance().getCookie(HOST) ?: return false
        return c.split(';').any { val n = it.substringBefore('=').trim(); n.equals(".ASPXAUTH", true) }
    }
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
    fun isLoadingPlaceholder(html: String): Boolean {
        if (extractRecordings(html).isNotEmpty()) return false
        // Only look inside the Panopto block: "loading_text" etc. also appear in unrelated blocks on full course pages,
        // which made courses without a Panopto block (e.g. a recitation course) fail with "didn't finish loading" + Retry.
        val start = listOf(html.indexOf(BLOCK_TITLE), html.indexOf("block_panopto")).filter { it >= 0 }.minOrNull()
        val scope = when {
            start != null -> html.substring(start, minOf(html.length, start + 4000))
            html.length < 4000 -> html // bare WS fragment
            else -> return false        // full page with no Panopto block at all → nothing is loading
        }
        return scope.contains("loading_text") || scope.contains("מאחזר תוכן") || scope.contains("block_panopto/asyncload")
    }

    /** block_panopto answer that is a real (possibly empty) list, not an error/placeholder — no point retrying. */
    fun isFinalBlockAnswer(html: String): Boolean = html.isNotBlank() && !isLoadingPlaceholder(html)

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

/** Tiny thread-safe TTL cache (injectable clock for tests). */
class TtlCache<K, V>(private val ttlMs: Long, private val now: () -> Long = System::currentTimeMillis) {
    private val m = java.util.concurrent.ConcurrentHashMap<K, Pair<Long, V>>()
    fun get(k: K): V? = m[k]?.let { (exp, v) -> if (now() < exp) v else { m.remove(k); null } }
    fun put(k: K, v: V, ttl: Long? = null) { m[k] = (now() + (ttl ?: ttlMs)) to v }
    fun clear() = m.clear()
}
