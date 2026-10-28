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
    suspend fun uploadSlides(lectureRemoteId: String, fileName: String, mime: String, data: InputStream)
    suspend fun uploadCourseContext(lectureRemoteId: String, fileName: String, data: InputStream)
    suspend fun uploadSegment(lectureRemoteId: String, seg: Segment, file: File)
    suspend fun uploadMarkers(lectureRemoteId: String, markers: List<Marker>)
    suspend fun startProcessing(lectureRemoteId: String): String
    suspend fun jobStatus(jobId: String): JobStatus
    /** POST /v1/jobs/{id}/retry — resume a failed/stuck job from its first incomplete stage (keeps finished ASR etc.). */
    suspend fun retryJob(jobId: String): JobStatus
    /** POST /v1/jobs/{id}/resummarize — re-run summary+PDF only, keeping ASR/repair (no ASR quota). */
    suspend fun resummarizeJob(jobId: String): JobStatus
    /** GET /v1/lectures/{id}/transcript — null when the server has no transcript yet (404). */
    suspend fun transcript(lectureRemoteId: String): Transcript?
    suspend fun downloadSummaryPdf(lectureRemoteId: String, dest: File)
}
