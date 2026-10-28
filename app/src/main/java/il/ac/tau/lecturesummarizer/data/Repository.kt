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

    @Synchronized fun addCourse(name: String): Course {
        val c = Course(UUID.randomUUID().toString(), name)
        _courses.value = _courses.value + c; save(); return c
    }

    @Synchronized fun addLecture(courseId: String, title: String): Lecture {
        val l = Lecture(UUID.randomUUID().toString(), courseId, title, System.currentTimeMillis())
        _lectures.value = _lectures.value + l; save(); return l
    }

    @Synchronized fun updateCourse(id: String, f: (Course) -> Course) {
        _courses.value = _courses.value.map { if (it.id == id) f(it) else it }; save()
    }

    @Synchronized fun updateLecture(id: String, f: (Lecture) -> Lecture) {
        _lectures.value = _lectures.value.map { if (it.id == id) f(it) else it }; save()
    }

    @Synchronized fun deleteLecture(id: String) {
        recordingsDir(id).deleteRecursively()
        _lectures.value = _lectures.value.filterNot { it.id == id }; save()
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
