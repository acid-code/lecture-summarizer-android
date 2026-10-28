package il.ac.tau.lecturesummarizer.data

import org.json.JSONArray
import org.json.JSONObject

data class Course(val id: String, val name: String, val remoteId: String? = null, val contextPdfUri: String? = null)

/** A "next slide/topic" tap during recording. tSec = seconds since recording start (across parts). */
data class Marker(val tSec: Long, val label: String? = null)

/** One audio segment file. A lecture "part" (45–50 min) is split into ~10 min segments for safety. */
data class Segment(val seq: Int, val path: String, val startedAtMs: Long, val durationMs: Long, val uploaded: Boolean = false)

enum class LectureState { DRAFT, RECORDING, RECORDED, UPLOADING, PROCESSING, DONE, FAILED }

data class Lecture(
    val id: String,
    val courseId: String,
    val title: String,
    val recordedAtMs: Long,
    val segments: List<Segment> = emptyList(),
    val markers: List<Marker> = emptyList(),
    val slidesUri: String? = null,
    val contextPdfUri: String? = null,
    val remoteId: String? = null,
    val jobId: String? = null,
    val state: LectureState = LectureState.DRAFT,
    val stage: String? = null,
    val progress: Int = 0,
    val error: String? = null,
    val pdfPath: String? = null,
)

data class JobStatus(val state: String, val stage: String?, val progress: Int, val error: String?)

data class TranscriptLine(val t0: Double, val t1: Double, val text: String)
data class Transcript(val source: String, val complete: Boolean, val lines: List<TranscriptLine>)

// ---- tiny JSON (de)serialization with org.json, no extra deps ----

fun Course.toJson() = JSONObject().put("id", id).put("name", name).put("remoteId", remoteId).put("contextPdfUri", contextPdfUri)
fun courseFromJson(o: JSONObject) = Course(o.getString("id"), o.getString("name"), o.optStringOrNull("remoteId"), o.optStringOrNull("contextPdfUri"))

fun Lecture.toJson(): JSONObject = JSONObject()
    .put("id", id).put("courseId", courseId).put("title", title).put("recordedAtMs", recordedAtMs)
    .put("segments", JSONArray(segments.map {
        JSONObject().put("seq", it.seq).put("path", it.path).put("startedAtMs", it.startedAtMs)
            .put("durationMs", it.durationMs).put("uploaded", it.uploaded)
    }))
    .put("markers", JSONArray(markers.map { JSONObject().put("tSec", it.tSec).put("label", it.label) }))
    .put("slidesUri", slidesUri).put("contextPdfUri", contextPdfUri)
    .put("remoteId", remoteId).put("jobId", jobId).put("state", state.name)
    .put("stage", stage).put("progress", progress).put("error", error).put("pdfPath", pdfPath)

fun lectureFromJson(o: JSONObject): Lecture {
    val segs = o.optJSONArray("segments") ?: JSONArray()
    val marks = o.optJSONArray("markers") ?: JSONArray()
    return Lecture(
        id = o.getString("id"), courseId = o.getString("courseId"), title = o.getString("title"),
        recordedAtMs = o.getLong("recordedAtMs"),
        segments = (0 until segs.length()).map { segs.getJSONObject(it) }.map {
            Segment(it.getInt("seq"), it.getString("path"), it.getLong("startedAtMs"), it.getLong("durationMs"), it.optBoolean("uploaded"))
        },
        markers = (0 until marks.length()).map { marks.getJSONObject(it) }.map { Marker(it.getLong("tSec"), it.optStringOrNull("label")) },
        slidesUri = o.optStringOrNull("slidesUri"), contextPdfUri = o.optStringOrNull("contextPdfUri"),
        remoteId = o.optStringOrNull("remoteId"), jobId = o.optStringOrNull("jobId"),
        state = runCatching { LectureState.valueOf(o.getString("state")) }.getOrDefault(LectureState.DRAFT),
        stage = o.optStringOrNull("stage"), progress = o.optInt("progress"), error = o.optStringOrNull("error"),
        pdfPath = o.optStringOrNull("pdfPath"),
    )
}

fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k) || !has(k)) null else optString(k)
