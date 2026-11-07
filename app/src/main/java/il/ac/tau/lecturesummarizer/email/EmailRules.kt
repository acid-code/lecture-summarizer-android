package il.ac.tau.lecturesummarizer.email

import java.util.Locale

/** Provider-independent rules; the mail transport is deliberately not enabled in this build. */
data class EmailRule(
    val id: String, val name: String, val enabled: Boolean = true,
    val senders: List<String> = emptyList(), val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(), val course: String = "",
)
data class EmailCandidate(val emailId: String, val sender: String, val title: String, val snippet: String,
    val course: String, val startMs: Long?, val endMs: Long?, val emailUrl: String)
data class EmailMatch(val candidate: EmailCandidate, val rule: EmailRule)
object EmailRules {
    private fun norm(s: String) = s.lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")
    fun firstMatch(rules: List<EmailRule>, candidate: EmailCandidate): EmailRule? {
        val body = norm(candidate.title + " " + candidate.snippet)
        val sender = norm(candidate.sender.substringAfter('<').substringBefore('>'))
        return rules.firstOrNull { r ->
            r.enabled && (r.senders.isEmpty() || r.senders.any { raw ->
                val s = norm(raw); s.isNotEmpty() && if (s.startsWith('@')) sender.endsWith(s) else sender == s
            }) && r.exclude.none { it.isNotBlank() && norm(it) in body } &&
                (r.include.isEmpty() || r.include.any { it.isNotBlank() && norm(it) in body }) &&
                (r.course.isBlank() || norm(r.course) == norm(candidate.course))
        }
    }
    /** Same email OR same normalized title/time is one candidate, regardless of rule. */
    fun preview(rules: List<EmailRule>, candidates: List<EmailCandidate>): List<EmailMatch> {
        val mail = mutableSetOf<String>(); val timed = mutableSetOf<Pair<String, Long>>()
        return candidates.mapNotNull { c ->
            val rule = firstMatch(rules, c) ?: return@mapNotNull null
            val key = c.startMs?.let { norm(c.title) to it }
            if (c.emailId in mail || key != null && key in timed) null
            else { mail += c.emailId; if (key != null) timed += key; EmailMatch(c, rule) }
        }
    }
}
