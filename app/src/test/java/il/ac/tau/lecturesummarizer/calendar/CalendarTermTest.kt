package il.ac.tau.lecturesummarizer.calendar
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
class CalendarTermTest {
 @Test fun autumnAndWinterShareOneWindow(){assertEquals(CalendarCourses.currentTerm(LocalDate.of(2026,11,8)),CalendarCourses.currentTerm(LocalDate.of(2027,2,1)))}
 @Test fun termNeverIncludesFullYear(){for(m in 1..12){val day=LocalDate.of(2026,m,15);val(s,e)=CalendarCourses.currentTerm(day);assertFalse(day<s);assertTrue(day<e);assertTrue(java.time.temporal.ChronoUnit.DAYS.between(s,e)<160)}}
}
