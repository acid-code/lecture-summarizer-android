package il.ac.tau.lecturesummarizer.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Local source of truth: courses + lectures stored as one JSON file in app-private storage.
 * Writes are atomic (temp file + rename) so a crash mid-write never corrupts state.
 */
class Repository(private val context: Context) {
    private val file = File(context.filesDir, "db.json")
    private val _courses = MutableStateFlow<List<Course>>(emptyList())
    private val _lectures = MutableStateFlow<List<Lecture>>(emptyList())
    val courses: StateFlow<List<Course>> = _courses.asStateFlow()
    val lectures: StateFlow<List<Lecture>> = _lectures.asStateFlow()

    init { load() }

    fun recordingsDir(lectureId: String) = File(context.filesDir, "recordings/$lectureId").apply { mkdirs() }
    fun pdfDir() = File(context.filesDir, "pdfs").apply { mkdirs() }

    fun lecture(id: String) = _lectures.value.firstOrNull { it.id == id }
    fun course(id: String) = _courses.value.firstOrNull { it.id == id }

    @Synchronized fun addCourse(name: String, emoji: String? = null, color: Long? = null, pattern: String? = null): Course {
        val c = Course(UUID.randomUUID().toString(), name, emoji = emoji, color = color, pattern = pattern)
        _courses.value = _courses.value + c; save(); return c
    }

    @Synchronized fun addLecture(courseId: String, title: String): Lecture {
        CourseActivity(context).touch(courseId)
        val l = Lecture(UUID.randomUUID().toString(), courseId, title, System.currentTimeMillis())
        _lectures.value = _lectures.value + l; save(); return l
    }

    /** Match a calendar class to an existing notebook by course number, then normalized name; create if missing. */
    @Synchronized fun findOrCreateCourse(name: String, courseNumber: String?): Course {
        val norm = il.ac.tau.lecturesummarizer.calendar.TauEventParser::normalize
        val hit = _courses.value.firstOrNull { courseNumber != null && it.courseNumber == courseNumber }
            ?: _courses.value.firstOrNull { norm(it.name) == norm(name) }
            ?: _courses.value.firstOrNull { norm(it.name).contains(norm(name)) || norm(name).contains(norm(it.name)) && norm(it.name).length >= 4 }
        if (hit != null) {
            if (hit.courseNumber == null && courseNumber != null) updateCourse(hit.id) { it.copy(courseNumber = courseNumber) }
            return course(hit.id)!!
        }
        val c = Course(UUID.randomUUID().toString(), name, courseNumber = courseNumber)
        _courses.value = _courses.value + c; save(); return c
    }

    /** Reassign a lecture to another notebook (wrong calendar guess). Remote copy keeps its old course until re-upload. */
    @Synchronized fun moveLecture(lectureId: String, courseId: String) = updateLecture(lectureId) { it.copy(courseId = courseId) }

    @Synchronized fun updateCourse(id: String, f: (Course) -> Course) {
        _courses.value = _courses.value.map { if (it.id == id) f(it) else it }; save();CourseActivity(context).touch(id)
    }

    /** Set by the app: called when a lecture's summary just became ready (unread badge). */
    var onSummaryReady: ((String) -> Unit)? = null

    @Synchronized fun updateLecture(id: String, f: (Lecture) -> Lecture) {
        val old=lecture(id)
        var ready = false
        _lectures.value = _lectures.value.map { if (it.id == id) f(it).also { n -> ready = UnreadStore.justFinished(it, n) } else it }; save()
        val changed=lecture(id)
        if(changed!=null && old!=changed){CourseActivity(context).touch(changed.courseId);if(old?.courseId!=changed.courseId)old?.let{CourseActivity(context).touch(it.courseId)}}
        if (ready) onSummaryReady?.invoke(id)
    }

    @Synchronized fun deleteLecture(id: String) {
        lecture(id)?.let{CourseActivity(context).touch(it.courseId)}
        recordingsDir(id).deleteRecursively()
        File(pdfDir(), "$id.pdf").delete()
        SummaryDensity.entries.forEach{File(pdfDir(),"$id-${it.api}.pdf").delete()}
        File(context.filesDir,"panopto/$id.srt").delete();StudyIndex(context).remove(id)
        _lectures.value = _lectures.value.filterNot { it.id == id }; save()
    }

    /** Remove a notebook and all its lectures (recordings + PDFs) from the phone. */
    @Synchronized fun deleteCourse(id: String) {
        _lectures.value.filter { it.courseId == id }.forEach { recordingsDir(it.id).deleteRecursively(); File(pdfDir(), "${it.id}.pdf").delete();val lid=it.id;SummaryDensity.entries.forEach{d->File(pdfDir(),"$lid-${d.api}.pdf").delete()};File(context.filesDir,"panopto/$lid.srt").delete();StudyIndex(context).remove(lid) }
        _lectures.value = _lectures.value.filterNot { it.courseId == id }
        _courses.value = _courses.value.filterNot { it.id == id }; save();CourseActivity(context).remove(id)
    }

    @Synchronized private fun load() {
        if (!file.exists()) return
        runCatching {
            val o = JSONObject(file.readText())
            val cs = o.getJSONArray("courses"); val ls = o.getJSONArray("lectures")
            _courses.value = (0 until cs.length()).map { courseFromJson(cs.getJSONObject(it)) }
            _lectures.value = (0 until ls.length()).map { lectureFromJson(ls.getJSONObject(it)) }
        }
    }

    @Synchronized private fun save() {
        val o = JSONObject()
            .put("courses", JSONArray(_courses.value.map { it.toJson() }))
            .put("lectures", JSONArray(_lectures.value.map { it.toJson() }))
        val tmp = File(file.parentFile, "db.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }
}
