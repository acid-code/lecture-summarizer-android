package il.ac.tau.lecturesummarizer.data

import java.io.File
import java.io.InputStream

/**
 * Contract with the companion backend (github.com/acid-code/lecture-summarizer-api), PLAN.md §5.
 * The phone never calls Gemini or any model directly — only this API.
 */
interface LectureApi {
    suspend fun createCourse(name: String): String
    suspend fun createLecture(courseRemoteId: String, title: String, recordedAtMs: Long): String
    /** Adds one deck (several per lecture). replaceAll=true clears the server's previous decks first (first deck of a full upload). */
    suspend fun uploadSlides(lectureRemoteId: String, fileName: String, mime: String, data: InputStream, replaceAll: Boolean = false)
    suspend fun uploadCourseContext(lectureRemoteId: String, fileName: String, data: InputStream, mime: String = "application/pdf", add: Boolean = false)
    suspend fun uploadSegment(lectureRemoteId: String, seg: Segment, file: File)
    suspend fun uploadMarkers(lectureRemoteId: String, markers: List<Marker>)
    /** [live]: transcribe parts as they upload (server waits for more audio); a later call without it finalizes. */
    suspend fun startProcessing(lectureRemoteId: String, live: Boolean = false, densities: Set<String> = setOf("standard")): String
    /**
     * POST /v1/lectures/{id}/transcript/import — external timed transcript (e.g. Panopto captions) instead of ASR.
     * Returns the job id if the server started one, else null (caller then calls [startProcessing]).
     */
    suspend fun importTranscript(lectureRemoteId: String, import: TranscriptImport): String?
    suspend fun jobStatus(jobId: String): JobStatus
    /** POST /v1/jobs/{id}/retry — resume a failed/stuck job from its first incomplete stage (keeps finished ASR etc.). */
    suspend fun retryJob(jobId: String): JobStatus
    /** POST /v1/jobs/{id}/resummarize — re-run summary+PDF only, keeping ASR/repair (no ASR quota). */
    /** [density]: a level different from the job's creates a NEW server job (returned in JobStatus.jobId); others are kept. */
    suspend fun resummarizeJob(jobId: String, density: String? = null): JobStatus
    /** POST /v1/jobs/{id}/reprocess — full redo incl. ASR if server audio exists; fallback=true re-summarizes when audio is gone. */
    suspend fun reprocessJob(jobId: String, fallback: Boolean = false): ReprocessResult
    /** GET /v1/lectures/{id}/transcript — null when the server has no transcript yet (404). */
    suspend fun transcript(lectureRemoteId: String): Transcript?
    /** DELETE /v1/lectures/{id} — removes the lecture, its jobs and server files (idempotent). */
    suspend fun deleteLecture(lectureRemoteId: String)
    /** DELETE /v1/courses/{id} — removes the course, its lectures and course files (idempotent; 404/405 = older server, ignored). */
    suspend fun deleteCourse(courseRemoteId: String)
    suspend fun downloadSummaryPdf(lectureRemoteId: String, dest: File, density: String? = null)
    /** GET /v1/lectures/{id}/summaries — density levels that have a finished summary. Empty on older servers. */
    suspend fun availableDensities(lectureRemoteId: String): Set<String> = emptySet()
}
