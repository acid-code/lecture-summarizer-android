package il.ac.tau.lecturesummarizer.calendar
import org.junit.Assert.*
import org.junit.Test

class CalendarRulesTest {
 private val event=CalendarImportEvent("1:2:0",1,2,"Biotech Lecture 3","Prof X. Molecular methods",100,200,false,"Asia/Jerusalem",true)
 private val rule=CalendarImportRule("r","Biotech",enabled=true,title="Biotech",include=listOf("molecular","biology"),exclude=listOf("cancelled"),targetCourse="Biotech")
 @Test fun rulesAreOptInAndEmptyRulesAreRejected(){assertFalse(CalendarImportRule("r","New").enabled);assertFalse(CalendarImportRule("r","New",targetCourse="Course").valid);assertNull(CalendarRules.firstMatch(listOf(rule.copy(enabled=false)),event))}
 @Test fun excludeWinsAndKeywordsAreCaseInsensitive(){assertEquals(rule,CalendarRules.firstMatch(listOf(rule),event));assertNull(CalendarRules.firstMatch(listOf(rule),event.copy(snippet="Molecular methods CANCELLED")))}
 @Test fun onlyFirstMatchingRuleFires(){assertEquals("r",CalendarRules.firstMatch(listOf(rule,rule.copy(id="second")),event)?.id)}
 @Test fun accountCalendarScopeAndCourseConstraintsApply(){assertNull(CalendarRules.firstMatch(listOf(rule.copy(calendars=setOf(9))),event));assertEquals(rule.copy(calendars=setOf(1)),CalendarRules.firstMatch(listOf(rule.copy(calendars=setOf(1))),event));assertNull(CalendarRules.firstMatch(listOf(rule.copy(courseFilter="Algorithms")),event))}
 @Test fun recurringClassesAreExplicitlyConfigurable(){assertTrue(rule.copy(title="",include=emptyList(),recurringOnly=true).valid);assertNull(CalendarRules.firstMatch(listOf(rule.copy(recurringOnly=true)),event.copy(recurring=false)))}
 @Test fun boundedGlobSupportsLiteralAndWildcards(){assertTrue(CalendarRules.titleMatches("bio*lecture ?","Biotech Lecture 3 - morning"));assertTrue(CalendarRules.titleMatches("ביוטכנולוגיה","שיעור ביוטכנולוגיה"));assertFalse(CalendarRules.titleMatches("bio*lab","Biotech Lecture"));assertTrue(CalendarRules.titleMatches("[lab]","Study [lab]"))}
 @Test fun previewDedupesAnInstanceOrSameTitleTime(){assertEquals(1,CalendarRules.preview(listOf(rule),listOf(event,event.copy(key="same-time",calendarId=3))).size);assertEquals(2,CalendarRules.preview(listOf(rule),listOf(event,event.copy(key="another",start=300,end=400))).size)}
 @Test fun rescheduleChangesFingerprintWithoutChangingStableEventKey(){val old=CalendarRuleMatch(event,rule);val moved=CalendarRuleMatch(event.copy(start=300,end=400),rule);assertEquals(old.event.key,moved.event.key);assertNotEquals(old.fingerprint,moved.fingerprint)}
 @Test fun changingDestinationRequiresApprovalAgain(){assertNotEquals(CalendarRuleMatch(event,rule).fingerprint,CalendarRuleMatch(event,rule.copy(targetCourse="Other")).fingerprint)}
}
