package il.ac.tau.lecturesummarizer.moodle

import il.ac.tau.lecturesummarizer.calendar.ClassKind

/**
 * TAU calendar "מספר קורס" is 8 digits (e.g. 03682157); Moodle course numbers are 10 digits:
 * the 8-digit number + group suffix (…01 lecture, …09 recitation/תרגיל).
 */
object MoodleMatch {
    data class Ranked(val course: MoodleCourse, val score: Int)

    private fun digits(c: MoodleCourse) = Regex("""\d{8,10}""").findAll("${c.shortname} ${c.idnumber} ${c.fullname}".replace("-", ""))
        .map { it.value }.toList()

    fun rank(courses: List<MoodleCourse>, courseNumber: String?, kind: ClassKind, courseName: String?): List<Ranked> {
        val num = courseNumber?.filter(Char::isDigit)?.take(8)
        val want = when (kind) { ClassKind.LECTURE -> "01"; ClassKind.RECITATION -> "09"; else -> null }
        val name = courseName?.let(::norm).orEmpty()
        return courses.map { c ->
            var s = 0
            val ds = digits(c)
            if (num != null && num.length == 8 && ds.any { it.startsWith(num) }) {
                s += 60
                if (want != null && ds.any { it == num + want }) s += 30
                else if (want != null && ds.any { it.length == 10 && it.startsWith(num) && it.endsWith(if (want == "01") "09" else "01") }) s -= 10
            }
            if (name.isNotEmpty() && norm(c.fullname).contains(name)) s += 20
            Ranked(c, s)
        }.filter { it.score > 0 }.sortedByDescending { it.score }
    }

    /** Moodle courses (all groups) whose number starts with the calendar's 8-digit prefix, sorted by number. */
    fun siblings(courses: List<MoodleCourse>, courseNumber: String?): List<MoodleCourse> {
        val num = courseNumber?.filter(Char::isDigit)?.take(8)?.takeIf { it.length == 8 } ?: return emptyList()
        return courses.filter { c -> digits(c).any { it.startsWith(num) } }.sortedBy { c -> digits(c).firstOrNull { it.startsWith(num) && it.length == 10 } ?: "zz" }
    }

    /** Auto-pick only when one clear winner (number + suffix match, or unique number match). */
    fun confident(r: List<Ranked>): MoodleCourse? {
        val top = r.firstOrNull() ?: return null
        val second = r.getOrNull(1)?.score ?: 0
        return top.course.takeIf { top.score >= 60 && top.score - second >= 20 }
    }

    /**
     * Panopto/Moodle titles often put the kind mid-string ("Introduction to ML - Recitation 3",
     * "… Machine Learning Lecture"). startsWith-only used to mark those UNKNOWN and let lecture↔recitation mix.
     */
    fun kindFromTitle(t: String) = when {
        Regex("""(?i)\b(recitation|tutorial|tirgul)(?=[\s_\-#.:]*\d|\b)""").containsMatchIn(t) || t.contains("תרגיל") || t.contains("תרגול") -> ClassKind.RECITATION
        Regex("""(?i)\blecture(?=[\s_\-#.:]*\d|\b)|\blec(?=[\s_\-#.:]+\d)""").containsMatchIn(t) || t.contains("שיעור") || t.contains("הרצאה") -> ClassKind.LECTURE
        else -> ClassKind.UNKNOWN
    }

    /** "Suggested" files: name mentions the lecture date (d.M / dd.MM) — soft hint only. */
    fun suggested(fileName: String, dayMonth: Pair<Int, Int>?): Boolean {
        val (d, m) = dayMonth ?: return false
        return Regex("""(?<!\d)0?$d[./\-]0?$m(?!\d)""").containsMatchIn(fileName)
    }

    /** True when [fileName] is not the opposite of [want] (UNKNOWN never blocks). */
    fun kindCompatible(fileName: String, want: ClassKind?): Boolean {
        if (want == null || want == ClassKind.UNKNOWN) return true
        val fileKind = kindFromTitle(fileName)
        return fileKind == ClassKind.UNKNOWN || fileKind == want
    }

    /**
     * Does a Moodle file look like the slides for a Panopto recording ("Lecture 3" → "lecture3.pdf", "Lec_03 slides.pptx", "שיעור 3")?
     * Same number, not the opposite class kind, and (if the name has an extension) a PDF/PPT(X).
     */
    fun matchesRecording(fileName: String, recordingTitle: String?, preferKind: ClassKind? = null): Boolean {
        val n = recordingTitle?.let(::lectureIndex) ?: return false
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty() && ext.length <= 4 && ext !in setOf("pdf", "ppt", "pptx")) return false
        val recKind = preferKind?.takeIf { it != ClassKind.UNKNOWN } ?: kindFromTitle(recordingTitle)
        val fileKind = kindFromTitle(fileName)
        if (recKind != ClassKind.UNKNOWN && fileKind != ClassKind.UNKNOWN && recKind != fileKind) return false
        val dirs = fileName.split(" / ").dropLast(1)
        dirs.asReversed().firstNotNullOfOrNull { d -> KEYED.find(d)?.groupValues?.get(1)?.toInt() }?.let { return it == n }
        val nums = Regex("""\d+""").findAll(fileName.substringBeforeLast('.')).map { it.value }.filter { it.length <= 2 }.map { it.toInt() }
        return n in nums.toList()
    }

    /**
     * Auto-attach pick for sync/repair.
     * 1) Unique number/date stem (kind-filtered)
     * 2) Else break ties / fill gaps with shared title↔filename words (and light penalties for notes/answers)
     * 3) Else the sole remaining kind-compatible deck in the pool (Biochem single-PDF courses)
     */
    fun autoPick(files: List<Pair<MoodleFile, String>>, recordingTitle: String?, dayMonth: Pair<Int, Int>?, preferKind: ClassKind? = null): MoodleFile? {
        val want = preferKind?.takeIf { it != ClassKind.UNKNOWN } ?: recordingTitle?.let(::kindFromTitle)
        val ok = files.filter { (_, n) -> n.substringAfterLast('.', "").lowercase() in setOf("pdf", "ppt", "pptx") }
            .filter { (_, n) -> kindCompatible(n, want) }
        if (ok.isEmpty()) return null
        val hasNum = recordingTitle?.let(::lectureIndex) != null
        val primary = if (hasNum) ok.filter { (_, n) -> matchesRecording(n, recordingTitle, want) }
        else ok.filter { (_, n) -> suggested(n, dayMonth) }
        uniqueStem(primary)?.let { return it }
        byCommonWords(if (primary.isNotEmpty()) primary else ok, recordingTitle)?.let { return it }
        return uniqueStem(ok)
    }

    private fun uniqueStem(cands: List<Pair<MoodleFile, String>>): MoodleFile? {
        if (cands.isEmpty()) return null
        val byStem = cands.groupBy { (_, n) -> norm(n.substringBeforeLast('.')) }
        if (byStem.size != 1) return null
        val group = byStem.values.single()
        return (group.firstOrNull { (_, n) -> n.endsWith(".pdf", true) } ?: group.first()).first
    }

    /** Prefer the stem whose filename shares the most content words with the recording title. */
    internal fun byCommonWords(cands: List<Pair<MoodleFile, String>>, recordingTitle: String?): MoodleFile? {
        if (cands.isEmpty()) return null
        val titleTok = tokens(recordingTitle.orEmpty())
        data class Scored(val file: MoodleFile, val name: String, val stem: String, val score: Int)
        val scored = cands.map { (f, n) ->
            val stem = norm(n.substringBeforeLast('.'))
            val fileTok = tokens(n)
            var s = (titleTok intersect fileTok).size
            // Prefer the main deck when a notes/answers sibling also shares the lecture number.
            if (Regex("""(?i)(^|[_\s\-])(notes?|answers?|handout|solutions?)([_\s\-.]|$)""").containsMatchIn(n) &&
                titleTok.none { it in setOf("note", "notes", "answer", "answers", "handout", "solution", "solutions") }) s -= 1
            Scored(f, n, stem, s)
        }
        val bestPerStem = scored.groupBy { it.stem }.mapValues { (_, g) -> g.maxBy { it.score } }.values.sortedByDescending { it.score }
        val top = bestPerStem.firstOrNull() ?: return null
        val second = bestPerStem.getOrNull(1)?.score ?: Int.MIN_VALUE
        // Shared words (or a notes penalty) win even when several stems matched the same lecture number.
        if (top.score > second) return top.file
        if (bestPerStem.size == 1) return top.file
        return null
    }

    internal fun tokens(s: String): Set<String> {
        val stop = setOf(
            "the", "and", "for", "with", "from", "to", "of", "in", "on", "a", "an", "at", "by",
            "introduction", "intro", "machine", "learning", "general", "class", "course",
            "pdf", "pptx", "ppt", "הקלטה", "בלבד", "של", "את", "על", "עם", "או",
        )
        // Split lecture4 / PPT02 so "lecture"+"4" can match a title that only says "Lecture".
        val split = norm(s)
            .replace(Regex("""([\p{L}])(\d)"""), "$1 $2")
            .replace(Regex("""(\d)([\p{L}])"""), "$1 $2")
        return Regex("""[\p{L}\p{N}]{2,}|\d+""").findAll(split).map { it.value }
            .filter { it !in stop && !it.matches(Regex("""\d{6,}""")) }
            .toSet()
    }

    /**
     * Lecture/recitation index from a recording title. Ignores calendar dates so
     * "שיעור 03/11" and "Lecture - 1.11.2026" do not become index 3 / 1.
     */
    fun lectureIndex(title: String): Int? {
        KEYED.find(title)?.let { m ->
            val after = title.substring(m.range.last + 1)
            // "שיעור 03/11", "Lecture - 1.11.2026" — the digit is the day, not the lecture number.
            if (Regex("""^[./\-]\d{1,2}""").containsMatchIn(after)) return null
            return m.groupValues[1].toInt()
        }
        val noDates = title.replace(DATE, " ")
        return Regex("""(?<!\d)\d{1,2}(?!\d)""").find(noDates)?.value?.toInt()
    }

    private val KEYED = Regex("""(?i)(?:תרגול|תרגיל|שיעור|הרצאה|tutorial|recitation|lecture|lec)[\s_\-#.:]*0*(\d{1,2})(?!\d)""")
    private val DATE = Regex("""(?i)\b\d{1,2}[./\-]\d{1,2}(?:[./\-]\d{2,4})?\b""")

    private fun norm(s: String) = s.lowercase().replace(Regex("""[\s\p{Punct}״׳"'–—]+"""), " ").trim()
}
