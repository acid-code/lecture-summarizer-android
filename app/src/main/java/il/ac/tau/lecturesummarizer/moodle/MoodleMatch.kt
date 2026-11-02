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

    /** Auto-pick only when one clear winner (number + suffix match, or unique number match). */
    fun confident(r: List<Ranked>): MoodleCourse? {
        val top = r.firstOrNull() ?: return null
        val second = r.getOrNull(1)?.score ?: 0
        return top.course.takeIf { top.score >= 60 && top.score - second >= 20 }
    }

    fun kindFromTitle(t: String) = when {
        t.startsWith("Recitation", true) || t.contains("תרגיל") || t.contains("תרגול") -> ClassKind.RECITATION
        t.startsWith("Lecture", true) || t.contains("שיעור") -> ClassKind.LECTURE
        else -> ClassKind.UNKNOWN
    }

    /** "Suggested" files: name mentions the lecture date (d.M / dd.MM) — soft hint only. */
    fun suggested(fileName: String, dayMonth: Pair<Int, Int>?): Boolean {
        val (d, m) = dayMonth ?: return false
        return Regex("""(?<!\d)0?$d[./\-]0?$m(?!\d)""").containsMatchIn(fileName)
    }

    /**
     * Does a Moodle file look like the slides for a Panopto recording ("Lecture 3" → "lecture3.pdf", "Lec_03 slides.pptx", "שיעור 3")?
     * Same number, not the opposite class kind, and (if the name has an extension) a PDF/PPT(X).
     */
    fun matchesRecording(fileName: String, recordingTitle: String?): Boolean {
        val n = recordingTitle?.let { Regex("""\d{1,2}""").find(it)?.value?.toInt() } ?: return false
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty() && ext.length <= 4 && ext !in setOf("pdf", "ppt", "pptx")) return false
        val recKind = kindFromTitle(recordingTitle); val fileKind = kindFromTitle(fileName)
        if (recKind != ClassKind.UNKNOWN && fileKind != ClassKind.UNKNOWN && recKind != fileKind) return false
        val nums = Regex("""\d+""").findAll(fileName.substringBeforeLast('.')).map { it.value }.filter { it.length <= 2 }.map { it.toInt() }
        return n in nums.toList()
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("""[\s\p{Punct}״׳"'–—]+"""), " ").trim()
}
