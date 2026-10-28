package il.ac.tau.lecturesummarizer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

class HttpLectureApi(private val baseUrl: String, private val token: String) : LectureApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)   // segments are tens of MB
        .readTimeout(2, TimeUnit.MINUTES)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    private fun req(path: String) = Request.Builder().url("$baseUrl$path").apply {
        if (token.isNotBlank()) header("Authorization", "Bearer $token")
    }

    private suspend fun <T> call(r: Request, f: (Response) -> T): T = withContext(Dispatchers.IO) {
        client.newCall(r).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} ${r.method} ${r.url.encodedPath}")
            f(resp)
        }
    }

    private suspend fun postJson(path: String, body: Any): JSONObject =
        call(req(path).post(body.toString().toRequestBody(json)).build()) {
            val s = it.body?.string().orEmpty(); if (s.isBlank()) JSONObject() else JSONObject(s)
        }

    private fun streamBody(mime: String, input: InputStream) = object : RequestBody() {
        override fun contentType() = mime.toMediaType()
        override fun writeTo(sink: BufferedSink) { input.source().use { sink.writeAll(it) } }
    }

    override suspend fun createCourse(name: String) =
        postJson("/v1/courses", JSONObject().put("name", name).put("lang_primary", "he")).getString("course_id")

    override suspend fun createLecture(courseRemoteId: String, title: String, recordedAtMs: Long) =
        postJson("/v1/lectures", JSONObject().put("course_id", courseRemoteId).put("title", title)
            .put("recorded_at", java.time.Instant.ofEpochMilli(recordedAtMs).toString())).getString("lecture_id")

    override suspend fun uploadSlides(lectureRemoteId: String, fileName: String, mime: String, data: InputStream) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, streamBody(mime, data)).build()
        call(req("/v1/lectures/$lectureRemoteId/slides").post(body).build()) {}
    }

    override suspend fun uploadCourseContext(lectureRemoteId: String, fileName: String, data: InputStream) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, streamBody("application/pdf", data)).build()
        call(req("/v1/lectures/$lectureRemoteId/context").post(body).build()) {}
    }

    override suspend fun uploadSegment(lectureRemoteId: String, seg: Segment, file: File) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("seq", seg.seq.toString())
            .addFormDataPart("started_at", java.time.Instant.ofEpochMilli(seg.startedAtMs).toString())
            .addFormDataPart("duration", (seg.durationMs / 1000.0).toString())
            .addFormDataPart("file", file.name, file.asRequestBody("audio/mp4".toMediaType()))
            .build()
        call(req("/v1/lectures/$lectureRemoteId/audio/segments").post(body).build()) {}
    }

    override suspend fun uploadMarkers(lectureRemoteId: String, markers: List<Marker>) {
        val arr = JSONArray(markers.mapIndexed { i, m ->
            JSONObject().put("t_sec", m.tSec).put("page", i + 2).put("label", m.label)
        })
        postJson("/v1/lectures/$lectureRemoteId/markers", arr)
    }

    override suspend fun startProcessing(lectureRemoteId: String) =
        postJson("/v1/lectures/$lectureRemoteId/process",
            JSONObject().put("options", JSONObject().put("asr", "cloud").put("diagrams", true).put("images", false))
        ).getString("job_id")

    override suspend fun jobStatus(jobId: String): JobStatus = call(req("/v1/jobs/$jobId").get().build()) {
        val o = JSONObject(it.body!!.string())
        JobStatus(o.getString("state"), o.optStringOrNull("stage"), o.optInt("progress"), o.optStringOrNull("error"))
    }

    override suspend fun downloadSummaryPdf(lectureRemoteId: String, dest: File) =
        call(req("/v1/lectures/$lectureRemoteId/summary.pdf").get().build()) { resp ->
            val tmp = File(dest.parentFile, dest.name + ".part")
            tmp.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
            tmp.renameTo(dest); Unit
        }
}
