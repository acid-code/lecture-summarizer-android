package il.ac.tau.lecturesummarizer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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

    override suspend fun createCourse(name: String) =
        postJson("/v1/courses", JSONObject().put("name", name).put("lang_primary", "he")).getString("course_id")

    override suspend fun createLecture(courseRemoteId: String, title: String, recordedAtMs: Long) =
        postJson("/v1/lectures", JSONObject().put("course_id", courseRemoteId).put("title", title)
            .put("recorded_at", java.time.Instant.ofEpochMilli(recordedAtMs).toString())).getString("lecture_id")

    /**
     * Large-file path (Vercel: 4.5 MB function body limit). Ask the API how to upload: POST /v1/uploads.
     *  - {"mode":"multipart"} (local/box, no Blob store) → null, caller uses multipart.
     *  - {"mode":"blob", put_url, headers} → PUT raw bytes straight to Vercel Blob, return the Blob URL,
     *    which the caller sends as JSON {url} to the normal /v1 endpoint.
     * Only attempted for files > BLOB_THRESHOLD; any failure of the probe falls back to multipart.
     */
    private suspend fun blobUploadOrNull(file: File, mime: String): String? {
        if (file.length() <= BLOB_THRESHOLD) return null
        val plan = runCatching {
            postJson("/v1/uploads", JSONObject().put("filename", file.name).put("content_type", mime).put("size", file.length()))
        }.getOrNull() ?: return null
        if (plan.optString("mode") != "blob") return null
        val put = Request.Builder().url(plan.getString("put_url")).put(file.asRequestBody(mime.toMediaType()))
        plan.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> put.header(k, h.getString(k)) } }
        return call(put.build()) { JSONObject(it.body!!.string()).getString("url") }
    }

    /** Spool an InputStream (slides/context from a content Uri) to a temp file so its size is known. */
    private suspend fun spool(name: String, data: InputStream): File = withContext(Dispatchers.IO) {
        File.createTempFile("upload-", "-" + name.replace(Regex("[^\\w.\\-]"), "_")).also { f ->
            data.use { input -> f.outputStream().use { input.copyTo(it) } }
        }
    }

    private suspend fun uploadDoc(path: String, fileName: String, mime: String, data: InputStream) {
        val f = spool(fileName, data)
        try {
            val url = blobUploadOrNull(f, mime)
            if (url != null) { postJson(path, JSONObject().put("url", url).put("mime", mime)); return }
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, f.asRequestBody(mime.toMediaType())).build()
            call(req(path).post(body).build()) {}
        } finally { f.delete() }
    }

    override suspend fun uploadSlides(lectureRemoteId: String, fileName: String, mime: String, data: InputStream) =
        uploadDoc("/v1/lectures/$lectureRemoteId/slides", fileName, mime, data)

    override suspend fun uploadCourseContext(lectureRemoteId: String, fileName: String, data: InputStream) =
        uploadDoc("/v1/lectures/$lectureRemoteId/context", fileName, "application/pdf", data)

    override suspend fun uploadSegment(lectureRemoteId: String, seg: Segment, file: File) {
        val startedAt = java.time.Instant.ofEpochMilli(seg.startedAtMs).toString()
        val duration = seg.durationMs / 1000.0
        val path = "/v1/lectures/$lectureRemoteId/audio/segments"
        val mime = audioMime(file)
        blobUploadOrNull(file, mime)?.let { url ->
            postJson(path, JSONObject().put("seq", seg.seq).put("started_at", startedAt)
                .put("duration", duration).put("mime", mime).put("url", url))
            return
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("seq", seg.seq.toString())
            .addFormDataPart("started_at", startedAt)
            .addFormDataPart("duration", duration.toString())
            .addFormDataPart("file", file.name, file.asRequestBody(mime.toMediaType()))
            .build()
        call(req(path).post(body).build()) {}
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

    override suspend fun retryJob(jobId: String): JobStatus {
        val o = postJson("/v1/jobs/$jobId/retry", JSONObject())
        return JobStatus(o.getString("state"), o.optStringOrNull("stage"), o.optInt("progress"), o.optStringOrNull("error"))
    }

    override suspend fun downloadSummaryPdf(lectureRemoteId: String, dest: File) =
        call(req("/v1/lectures/$lectureRemoteId/summary.pdf").get().build()) { resp ->
            val tmp = File(dest.parentFile, dest.name + ".part")
            tmp.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
            tmp.renameTo(dest); Unit
        }

    companion object {
        /** Above this, use Blob direct upload when the server offers it (Vercel body limit is 4.5 MB). */
        const val BLOB_THRESHOLD = 4L * 1024 * 1024
    }
}

/** MIME for a local audio file by extension (recorded = .m4a; imported files keep their extension). */
fun audioMime(f: File): String = when (f.extension.lowercase()) {
    "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "ogg", "oga", "opus" -> "audio/ogg"; "flac" -> "audio/flac"
    "aac" -> "audio/aac"; "webm" -> "audio/webm"; "amr" -> "audio/amr"; "3gp" -> "audio/3gpp"
    else -> "audio/mp4"
}
