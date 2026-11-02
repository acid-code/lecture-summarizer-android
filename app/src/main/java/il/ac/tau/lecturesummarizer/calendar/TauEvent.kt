package il.ac.tau.lecturesummarizer.calendar

enum class ClassKind(val he: String) { LECTURE("שיעור"), RECITATION("תרגיל"), UNKNOWN("") }

/** Raw calendar instance (from CalendarContract.Instances). */
data class CalEvent(
    val title: String, val description: String?, val location: String?,
    val beginMs: Long, val endMs: Long, val calendarAccount: String?, val allDay: Boolean = false,
)

data class TauClass(
    val courseName: String, val kind: ClassKind, val courseNumber: String?, val lecturer: String?,
    val room: String?, val beginMs: Long, val endMs: Long, val fromTauCalendar: Boolean,
    /** True when the event didn't match the TAU (שיעור)/(תרגיל)/מספר קורס scheme — name is the cleaned event title. */
    val offScheme: Boolean = false,
)

object TauEventParser {
    private val kindSuffix = Regex("""\s*[(（]\s*(שיעור|תרגיל|תירגול|תרגול)\s*[)）]\s*$""")
    private val courseNo = Regex("""מספר\s*קורס\s*[:：]?\s*([0-9][0-9\-]{4,})""")
    private val lecturerRe = Regex("""(?:מרצה|מתרגל(?:ת)?)\s*[:：]\s*([^\n\r]+)""")

    fun parse(e: CalEvent, requireClassHint: Boolean = true): TauClass? {
        val t = e.title.trim()
        if (t.isEmpty() || e.allDay) return null
        val m = kindSuffix.find(t)
        val kind = when (m?.groupValues?.get(1)) {
            "שיעור" -> ClassKind.LECTURE
            null -> ClassKind.UNKNOWN
            else -> ClassKind.RECITATION
        }
        val desc = e.description.orEmpty()
        val num = courseNo.find(desc)?.groupValues?.get(1)?.replace("-", "")
        val tau = isTauAccount(e.calendarAccount)
        // Without a kind marker / course number / TAU calendar, it's probably not a class.
        if (requireClassHint && kind == ClassKind.UNKNOWN && num == null && !tau) return null
        val name = if (m != null) t.substring(0, m.range.first).trim() else t
        return TauClass(name, kind, num, lecturerRe.find(desc)?.groupValues?.get(1)?.trim(),
            e.location?.trim()?.ifEmpty { null }, e.beginMs, e.endMs, tau)
    }

    /** Strip common calendar noise from a free-form event title to get a course-ish name. */
    fun cleanTitle(raw: String): String {
        var t = raw
        t = t.replace(Regex("""https?://\S+"""), " ")
        t = t.replace(Regex("""[\[(（【][^\])）】]*[\])）】]"""), " ")              // (Zoom), [online], (שיעור 3)…
        t = t.replace(Regex("""(?i)\b(zoom|teams|meet|online|meeting|webinar|lecture|recitation|class)\b"""), " ")
        t = t.replace(Regex("""(?<!\S)(הרצאה|שיעור|תרגול|תירגול|תרגיל|זום|מקוון)(?!\S)"""), " ")
        t = t.replace(Regex("""[\p{So}\p{Cn}]"""), " ")                              // emoji
        t = t.replace(Regex("""\s*[-–—|:·•,]+\s*$|^\s*[-–—|:·•,]+\s*"""), " ")
        t = t.replace(Regex("""\s+"""), " ").trim().trim('-', '–', '—', '|', ':', '·', ',').trim()
        return t.ifEmpty { raw.trim() }
    }

    fun isTauAccount(a: String?) = a?.trim()?.lowercase()?.endsWith("@mail.tau.ac.il") == true

    /**
     * Pick candidates for "now". Primary: events overlapping now (class starts HH:10–HH:15 inside an HH:00 block).
     * Fallback only if nothing overlaps: events starting within [earlyMs].
     * Ranked: TAU calendar first, explicit kind first, latest start first.
     */
    fun candidates(events: List<CalEvent>, nowMs: Long, earlyMs: Long = 10 * 60_000L, preferTau: Boolean = true): List<TauClass> {
        val parsed = events.mapNotNull { parse(it, requireClassHint = preferTau) }.map { if (preferTau) it else it.copy(fromTauCalendar = false) }
        val overlap = parsed.filter { it.beginMs <= nowMs && nowMs < it.endMs }
        val pool = overlap.ifEmpty { parsed.filter { it.beginMs > nowMs && it.beginMs - nowMs <= earlyMs } }
        val ranked = pool.distinctBy { Triple(it.courseName, it.kind, it.beginMs) }
            .sortedWith(compareByDescending<TauClass> { it.fromTauCalendar }
                .thenByDescending { it.kind != ClassKind.UNKNOWN }.thenByDescending { it.beginMs })
        // If TAU-calendar matches exist, ignore personal-calendar noise.
        return if (ranked.any { it.fromTauCalendar }) ranked.filter { it.fromTauCalendar } else ranked
    }

    fun normalize(s: String) = s.lowercase().replace(Regex("""[\s\p{Punct}״׳"'–—]+"""), " ").trim()
}
