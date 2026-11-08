package il.ac.tau.lecturesummarizer.ui
import il.ac.tau.lecturesummarizer.calendar.*
import org.junit.Test
import org.junit.Assert.*
class UpcomingClassesTest {
 @Test fun upcomingIsChronologicalDeduplicatedAndNeverIncludesEndedEvents(){
  fun row(key:String,start:Long,end:Long)=CalendarRuleMatch(CalendarImportEvent(key,1,1,key,"",start,end,false,"Asia/Jerusalem"),CalendarImportRule("r","r",true,"*",targetCourse="Course"))
  assertEquals(listOf("now","next"),upcomingClasses(listOf(row("next",200,300),row("old",0,99),row("now",50,150),row("next",200,300)),100).map{it.event.key})
 }
}
