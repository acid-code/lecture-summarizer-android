package il.ac.tau.lecturesummarizer.data

import org.json.JSONArray
import org.json.JSONObject

data class Course(
    val id: String, val name: String, val remoteId: String? = null, val contextPdfUri: String? = null,
    /** Notebook cover customisation (null = derived defaults). */
    val emoji: String? = null, val color: Long? = null, val pattern: String? = null,
    /** Additional course-context files; all = [allContext] (max [MAX_COURSE_CONTEXT]). */
    val extraContext: List<String> = emptyList(),
    /** TAU course number from the calendar event description (local only). */
    val courseNumber: String? = null,
)

const val MAX_COURSE_CONTEXT = 3
val Course.allContext: List<String> get() = listOfNotNull(contextPdfUri) + extraContext

/** A "next slide/topic" tap during recording. tSec = seconds since recording start (across parts). */
data class Marker(val tSec: Long, val label: String? = null)

/** One audio segment file. A lecture "part" (45–50 min) is split into ~10 min segments for safety. */
data class Segment(val seq: Int, val path: String, val startedAtMs: Long, val durationMs: Long, val uploaded: Boolean = false)

enum class LectureState { DRAFT, RECORDING, RECORDED, UPLOADING, PROCESSING, DONE, FAILED }

/** 1-based original slide numbers; approximate ranges are suggestions, never certainty. */
data class SlideRange(val start:Int,val end:Int,val approximate:Boolean=true){
    init{require(start>=1 && end>=start && end<=10000)}
    fun toJson()=JSONObject().put("start",start).put("end",end).put("approximate",approximate)
}
fun slideRangeFromJson(o:JSONObject)=SlideRange(o.getInt("start"),o.getInt("end"),o.optBoolean("approximate",true))

data class Lecture(
    val id: String,
    val courseId: String,
    val title: String,
    val recordedAtMs: Long,
    val segments: List<Segment> = emptyList(),
    val markers: List<Marker> = emptyList(),
    val slidesUri: String? = null,
    /** Additional slide decks / PDFs (several materials per lecture). All decks = [allSlides]. */
    val extraSlides: List<String> = emptyList(),
    /** Slide URIs the server has accepted (POST /v1/lectures/{id}/slides succeeded). */
    val uploadedSlides: List<String> = emptyList(),
    val contextPdfUri: String? = null,
    val remoteId: String? = null,
    val jobId: String? = null,
    /** [jobId] is a live server job (ASR while recording); the final "Upload & process" finalizes it. */
    val liveJob: Boolean = false,
    val state: LectureState = LectureState.DRAFT,
    val stage: String? = null,
    val progress: Int = 0,
    /** Human progress text from the server (progress_detail.label), e.g. "ASR part 2/3 · window 3/6". */
    val stageDetail: String? = null,
    val error: String? = null,
    val pdfPath: String? = null,
    /** Panopto source chosen instead of recording (new-entry chooser). Captions are cached at [panoptoSrtPath]. */
    val panoptoId: String? = null,
    val panoptoTitle: String? = null,
    val panoptoUrl: String? = null,
    val panoptoSrtPath: String? = null,
    /** Recording length for Panopto imports (last caption end), since they have no audio segments. */
    val panoptoDurationMs: Long = 0,
    /** No-captions Panopto import: audio pulled on the phone (PanoptoAudioWorker) → normal ASR instead of captions. */
    val panoptoAudio: Boolean = false,
    val slideRanges:Map<String,SlideRange> = emptyMap(),
) {
    val fromPanopto get() = panoptoId != null
    /** Duration shown in list/detail: recorded audio, else Panopto recording length. */
    val displayDurationMs: Long get() = segments.sumOf { it.durationMs }.takeIf { it > 0 } ?: panoptoDurationMs
}

val Lecture.allSlides: List<String> get() = (listOfNotNull(slidesUri) + extraSlides).distinct()

data class JobStatus(val state: String, val stage: String?, val progress: Int, val error: String?, val detail: String? = null, val jobId: String? = null, val density: String? = null)
/** Summary density levels (server: compact | standard | detailed). */
enum class SummaryDensity(val api: String, val labelRes: Int) {
    COMPACT("compact", il.ac.tau.lecturesummarizer.R.string.level_compact), STANDARD("standard", il.ac.tau.lecturesummarizer.R.string.level_standard), DETAILED("detailed", il.ac.tau.lecturesummarizer.R.string.level_detailed);
    companion object { fun of(s: String?) = entries.firstOrNull { it.api == s } }
}
/** Result of POST /v1/jobs/{id}/reprocess. status==null means refused (audioMissing → server no longer has the raw audio). */
data class ReprocessResult(val status: JobStatus?, val mode: String?, val audioMissing: Boolean, val error: String? = null)

data class TranscriptLine(val t0: Double, val t1: Double, val text: String)
data class Transcript(val source: String, val complete: Boolean, val lines: List<TranscriptLine>)

// ---- tiny JSON (de)serialization with org.json, no extra deps ----

fun Course.toJson() = JSONObject().put("id", id).put("name", name).put("remoteId", remoteId).put("contextPdfUri", contextPdfUri)
    .put("emoji", emoji).put("color", color).put("pattern", pattern).put("extraContext", JSONArray(extraContext)).put("courseNumber", courseNumber)
fun courseFromJson(o: JSONObject) = Course(o.getString("id"), o.getString("name"), o.optStringOrNull("remoteId"), o.optStringOrNull("contextPdfUri"),
    o.optStringOrNull("emoji"), if (o.has("color") && !o.isNull("color")) o.getLong("color") else null, o.optStringOrNull("pattern"),
    o.optJSONArray("extraContext")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
    o.optStringOrNull("courseNumber"))

fun Lecture.toJson(): JSONObject = JSONObject()
    .put("id", id).put("courseId", courseId).put("title", title).put("recordedAtMs", recordedAtMs)
    .put("segments", JSONArray(segments.map {
        JSONObject().put("seq", it.seq).put("path", it.path).put("startedAtMs", it.startedAtMs)
            .put("durationMs", it.durationMs).put("uploaded", it.uploaded)
    }))
    .put("markers", JSONArray(markers.map { JSONObject().put("tSec", it.tSec).put("label", it.label) }))
    .put("slideRanges",JSONObject().apply{slideRanges.filterKeys{it in allSlides}.forEach{(uri,range)->put(uri,range.toJson())}})
    .put("slidesUri", slidesUri).put("extraSlides", JSONArray(extraSlides)).put("uploadedSlides", JSONArray(uploadedSlides)).put("contextPdfUri", contextPdfUri)
    .put("remoteId", remoteId).put("jobId", jobId).put("liveJob", liveJob).put("state", state.name)
    .put("stage", stage).put("progress", progress).put("stageDetail", stageDetail).put("error", error).put("pdfPath", pdfPath)
    .put("panoptoId", panoptoId).put("panoptoTitle", panoptoTitle).put("panoptoUrl", panoptoUrl).put("panoptoSrtPath", panoptoSrtPath).put("panoptoDurationMs", panoptoDurationMs).put("panoptoAudio", panoptoAudio)

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
        slideRanges = o.optJSONObject("slideRanges")?.let{ranges->ranges.keys().asSequence().mapNotNull{uri->runCatching{uri to slideRangeFromJson(ranges.getJSONObject(uri))}.getOrNull()}.toMap()} ?: emptyMap(),
        slidesUri = o.optStringOrNull("slidesUri"),
        extraSlides = o.optJSONArray("extraSlides")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
        uploadedSlides = o.optJSONArray("uploadedSlides")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
        contextPdfUri = o.optStringOrNull("contextPdfUri"),
        remoteId = o.optStringOrNull("remoteId"), jobId = o.optStringOrNull("jobId"), liveJob = o.optBoolean("liveJob"),
        state = runCatching { LectureState.valueOf(o.getString("state")) }.getOrDefault(LectureState.DRAFT),
        stage = o.optStringOrNull("stage"), progress = o.optInt("progress"), stageDetail = o.optStringOrNull("stageDetail"), error = o.optStringOrNull("error"),
        pdfPath = o.optStringOrNull("pdfPath"),
        panoptoId = o.optStringOrNull("panoptoId"), panoptoTitle = o.optStringOrNull("panoptoTitle"),
        panoptoUrl = o.optStringOrNull("panoptoUrl"), panoptoSrtPath = o.optStringOrNull("panoptoSrtPath"),
        panoptoAudio = o.optBoolean("panoptoAudio"),
        panoptoDurationMs = o.optLong("panoptoDurationMs").takeIf { it > 0 } ?: srtDurationMs(o.optStringOrNull("panoptoSrtPath")),
    )
}

fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k) || !has(k)) null else optString(k)

/** Body of POST /v1/lectures/{id}/transcript/import. */
data class TranscriptImport(
    val source: String, val externalId: String, val title: String, val language: String,
    val segments: List<il.ac.tau.lecturesummarizer.moodle.CaptionSegment>,
)

/** Backfill for lectures imported before panoptoDurationMs existed: last caption end of the cached SRT. */
private fun srtDurationMs(path: String?): Long = path?.let { runCatching {
    val f = java.io.File(it); if (!f.exists()) 0L
    else ((il.ac.tau.lecturesummarizer.moodle.Panopto.parseSrt(f.readText()).maxOfOrNull { s -> s.t1 } ?: 0.0) * 1000).toLong()
}.getOrDefault(0L) } ?: 0L
