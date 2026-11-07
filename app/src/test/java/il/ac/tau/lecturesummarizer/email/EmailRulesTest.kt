package il.ac.tau.lecturesummarizer.email

import org.junit.Assert.*
import org.junit.Test

class EmailRulesTest {
    private val candidate = EmailCandidate("m1", "Teacher <teacher@tau.ac.il>", "Biotech exam", "Exam date confirmed", "Biotech", 100, 200, "https://mail.google.com/mail/#all/m1")
    @Test fun excludesWinAndSenderIsNotASubstring() {
        val rule = EmailRule("r1", "exam", senders = listOf("@tau.ac.il"), include = listOf("EXAM"), exclude = listOf("cancelled"), course = "Biotech")
        assertEquals(rule, EmailRules.firstMatch(listOf(rule), candidate))
        assertNull(EmailRules.firstMatch(listOf(rule), candidate.copy(snippet = "exam cancelled")))
        assertNull(EmailRules.firstMatch(listOf(rule), candidate.copy(sender = "x@tau.ac.il.attacker.com")))
        assertNull(EmailRules.firstMatch(listOf(rule), candidate.copy(course = "Algorithms")))
    }
    @Test fun disabledRulesAreSkippedAndFirstWins() {
        val off = EmailRule("off", "off", enabled = false)
        val one = EmailRule("one", "one")
        assertEquals(one, EmailRules.firstMatch(listOf(off, one, EmailRule("two", "two")), candidate))
    }
    @Test fun previewDedupesSameMailOrSameTitleAndTime() {
        val rule = EmailRule("one", "one")
        assertEquals(1, EmailRules.preview(listOf(rule), listOf(candidate, candidate.copy(title = "updated"), candidate.copy(emailId = "m2"))).size)
        assertEquals(2, EmailRules.preview(listOf(rule), listOf(candidate, candidate.copy(emailId = "m3", startMs = 300))).size)
    }
    @Test fun missingDatesAreRetainedForReviewNeverInvented() {
        val result = EmailRules.preview(listOf(EmailRule("r", "r")), listOf(candidate.copy(startMs = null, endMs = null)))
        assertNull(result.single().candidate.startMs)
    }
}
