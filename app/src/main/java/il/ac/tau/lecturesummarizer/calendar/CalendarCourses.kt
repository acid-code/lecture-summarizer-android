package il.ac.tau.lecturesummarizer.calendar

import il.ac.tau.lecturesummarizer.LectureApp
import java.time.*

/** Read-only preview. A coarse academic-term window is displayed explicitly before local import. */
object CalendarCourses {
    data class Course(val name:String,val number:String?)
    fun currentTerm(day:LocalDate):Pair<LocalDate,LocalDate> = when(day.monthValue) {
        1,2 -> LocalDate.of(day.year-1,10,1) to LocalDate.of(day.year,3,1)
        in 3..7 -> LocalDate.of(day.year,3,1) to LocalDate.of(day.year,8,1)
        8,9 -> LocalDate.of(day.year,8,1) to LocalDate.of(day.year,10,1)
        else -> LocalDate.of(day.year,10,1) to LocalDate.of(day.year+1,3,1)
    }
    fun preview(app:LectureApp,ids:Set<Long>,now:Long=System.currentTimeMillis()):List<Course> {
        require(ids.isNotEmpty())
        val zone=ZoneId.of("Asia/Jerusalem");val (start,end)=currentTerm(Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
        return CalendarResolver.pastClasses(app,ids,start.atStartOfDay(zone).toInstant().toEpochMilli(),end.atStartOfDay(zone).toInstant().toEpochMilli())
            .distinctBy{it.courseNumber ?: TauEventParser.normalize(it.courseName)}.take(60).map{Course(it.courseName,it.courseNumber)}
    }
    fun add(app:LectureApp,courses:List<Course>)=courses.forEach{app.repo.findOrCreateCourse(it.name,it.number)}
}
