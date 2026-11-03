package il.ac.tau.lecturesummarizer.autosync

import il.ac.tau.lecturesummarizer.calendar.ClassKind
import il.ac.tau.lecturesummarizer.moodle.MoodleMatch
import il.ac.tau.lecturesummarizer.moodle.PanoptoRecording
import java.util.Calendar
import java.util.TimeZone

/**
 * Pure matching for auto-sync: which Panopto recording belongs to a past calendar class.
 * Signals: same Asia/Jerusalem calendar day via the date in the recording title OR the Panopto session start
 * ([PanoptoRecording.startMs], from DeliveryInfo) — one of them is required; class kind (not the opposite one), lecturer name tokens from the
 * calendar event notes (מרצה:/מתרגל:) as a tie-breaker.
 */
object RecordingMatcher {
    val TZ: TimeZone = TimeZone.getTimeZone("Asia/Jerusalem")

    data class Ymd(val y: Int?, val m: Int, val d: Int)

    private val dmy = Regex("""(?<!\d)(\d{1,2})[./\-](\d{1,2})(?:[./\-](\d{2,4}))?(?!\d)""")
    private val ymd = Regex("""(?<!\d)(20\d{2})[./\-](\d{1,2})[./\-](\d{1,2})(?!\d)""")

    /** All dates mentioned in a title (d/m[/y] and yyyy-mm-dd). */
    fun datesIn(title: String): List<Ymd> {
        val out = mutableListOf<Ymd>()
        val iso = ymd.findAll(title).toList()
        iso.forEach { out += Ymd(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
        var rest = title
        iso.forEach { rest = rest.replace(it.value, " ") }
        dmy.findAll(rest).forEach { m ->
            val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
            val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it }
            if (d in 1..31 && mo in 1..12) out += Ymd(y, mo, d)
        }
        return out.filter { it.m in 1..12 && it.d in 1..31 }
    }

    fun ymdOf(ms: Long): Ymd = Calendar.getInstance(TZ).apply { timeInMillis = ms }
        .let { Ymd(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH)) }

    fun sameDay(a: Long, b: Long) = ymdOf(a) == ymdOf(b)

    private fun nameTokens(s: String?) = s.orEmpty().replace(Regex("""(ד"ר|דר'|פרופ'?|prof\.?|dr\.?|מר|גב')""", RegexOption.IGNORE_CASE), " ")
        .split(Regex("""[\s,;/]+""")).map { it.trim().lowercase() }.filter { it.length >= 2 }

    /** Score a recording for a class; null = not a candidate. */
    fun score(rec: PanoptoRecording, beginMs: Long, kind: ClassKind, lecturer: String?): Int? {
        val want = ymdOf(beginMs)
        val dates = datesIn(rec.title)
        val titleHit = dates.any { it.m == want.m && it.d == want.d && (it.y == null || it.y == want.y) }
        val sessionHit = rec.startMs?.let { sameDay(it, beginMs) } ?: false
        val rk = MoodleMatch.kindFromTitle(rec.title)
        if (kind != ClassKind.UNKNOWN && rk != ClassKind.UNKNOWN && rk != kind) return null
        var s = when {
            titleHit && sessionHit -> 60
            titleHit -> if (rec.startMs != null) 40 else 50   // title says this day but Panopto session is another day (re-upload?) — weaker
            sessionHit -> if (dates.isEmpty()) 48 else 35     // "Lecture 6" style titles; a title naming another date is suspicious
            else -> return null
        }
        if (rk == kind && kind != ClassKind.UNKNOWN) s += 20
        val t = rec.title.lowercase()
        if (nameTokens(lecturer).any { t.contains(it) }) s += 25
        return s
    }

    /** Title has no date for this class's day → session start is worth fetching (DeliveryInfo) to decide. */
    fun needsSessionDate(rec: PanoptoRecording, beginMs: Long): Boolean {
        if (rec.startMs != null) return false
        val want = ymdOf(beginMs)
        return datesIn(rec.title).none { it.m == want.m && it.d == want.d && (it.y == null || it.y == want.y) }
    }

    /** Best recording or null. With several same-day hits and no clear winner, prefer the lecturer match; else first. */
    fun best(recs: List<PanoptoRecording>, beginMs: Long, kind: ClassKind, lecturer: String?): PanoptoRecording? =
        recs.mapNotNull { r -> score(r, beginMs, kind, lecturer)?.let { r to it } }.maxByOrNull { it.second }?.first
}
