package il.ac.tau.lecturesummarizer.ui
import il.ac.tau.lecturesummarizer.calendar.*
import org.junit.Test
import org.junit.Assert.*
class UpcomingClassesTest {
 private fun row(key:String,start:Long,end:Long)=CalendarRuleMatch(CalendarImportEvent(key,1,1,key,"",start,end,false,"Asia/Jerusalem"),CalendarImportRule("r","r",true,"*",targetCourse="Course"))
 @Test fun upcomingIsChronologicalDeduplicatedAndNeverIncludesEndedEvents(){
  assertEquals(listOf("now","next"),upcomingClasses(listOf(row("next",200,300),row("old",0,99),row("now",50,150),row("next",200,300)),100).map{it.event.key})
 }
 @Test fun calendarContentKeyIgnoresListIdentity(){
  val a=listOf(row("now",50,150),row("next",200,300))
  val b=listOf(row("now",50,150),row("next",200,300))
  assertEquals(calendarContentKey(a),calendarContentKey(b))
  assertNotEquals(calendarContentKey(a),calendarContentKey(listOf(row("now",50,151))))
 }
}
