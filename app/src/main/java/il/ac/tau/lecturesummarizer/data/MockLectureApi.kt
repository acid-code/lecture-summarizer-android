package il.ac.tau.lecturesummarizer.data

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.delay
import java.io.File
import java.io.InputStream
import java.util.UUID

/** Offline stand-in used when mock mode is on or the API is unreachable. Fakes the job pipeline. */
class MockLectureApi : LectureApi {
    private val stages = listOf("uploaded", "normalized", "transcribed", "slides_ingested",
        "aligned", "repaired", "summarized", "rendered")
    private val jobStarts = mutableMapOf<String, Long>()

    override suspend fun createCourse(name: String) = "mock-c-" + UUID.randomUUID()
    override suspend fun createLecture(courseRemoteId: String, title: String, recordedAtMs: Long) = "mock-l-" + UUID.randomUUID()
    override suspend fun uploadSlides(lectureRemoteId: String, fileName: String, mime: String, data: InputStream, replaceAll: Boolean) { data.close(); delay(300) }
    override suspend fun uploadCourseContext(lectureRemoteId: String, fileName: String, data: InputStream, mime: String, add: Boolean) { data.close(); delay(300) }
    override suspend fun uploadSegment(lectureRemoteId: String, seg: Segment, file: File) { delay(500) }
    override suspend fun uploadMarkers(lectureRemoteId: String, markers: List<Marker>) {}
    override suspend fun startProcessing(lectureRemoteId: String): String {
        val id = "mock-j-$lectureRemoteId"; jobStarts[id] = System.currentTimeMillis(); return id
    }

    override suspend fun jobStatus(jobId: String): JobStatus {
        val start = jobStarts.getOrPut(jobId) { System.currentTimeMillis() }
        val i = ((System.currentTimeMillis() - start) / 2000).toInt()  // one stage every 2s
        return if (i >= stages.size) JobStatus("done", "rendered", 100, null)
        else JobStatus("running", stages[i], i * 100 / stages.size, null)
    }

    override suspend fun retryJob(jobId: String): JobStatus {
        jobStarts[jobId] = System.currentTimeMillis(); return JobStatus("running", stages[0], 0, null)
    }

    override suspend fun resummarizeJob(jobId: String): JobStatus = retryJob(jobId)
    override suspend fun reprocessJob(jobId: String, fallback: Boolean) = ReprocessResult(retryJob(jobId), "full", false)
    override suspend fun transcript(lectureRemoteId: String): Transcript? = Transcript("mock", true,
        (0 until 12).map { TranscriptLine(it * 10.0, it * 10.0 + 10, "Mock line ${it + 1}: דיפ לרנינג → deep learning") })
    override suspend fun downloadSummaryPdf(lectureRemoteId: String, dest: File) {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val p = Paint().apply { textSize = 18f }
        page.canvas.drawText("Mock study PDF", 40f, 60f, p)
        p.textSize = 12f
        page.canvas.drawText("Lecture $lectureRemoteId", 40f, 90f, p)
        page.canvas.drawText("סיכום לדוגמה — Hebrew content with English terms (e.g. eigenvalue).", 40f, 120f, p)
        doc.finishPage(page)
        dest.outputStream().use { doc.writeTo(it) }
        doc.close()
    }
}
