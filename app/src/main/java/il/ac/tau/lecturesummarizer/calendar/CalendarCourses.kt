package il.ac.tau.lecturesummarizer.calendar

import il.ac.tau.lecturesummarizer.LectureApp

/** Device-synced events only; no calendar writes or network access. */
object CalendarCourses {
    fun import(app: LectureApp, now: Long = System.currentTimeMillis()) {
        val span = 180L * 24 * 3600_000
        CalendarResolver.pastClasses(app, app.settings.calendarIds, now - span, now + span)
            .distinctBy { it.courseNumber ?: TauEventParser.normalize(it.courseName) }
            .take(60).forEach { app.repo.findOrCreateCourse(it.courseName, it.courseNumber) }
    }
}
