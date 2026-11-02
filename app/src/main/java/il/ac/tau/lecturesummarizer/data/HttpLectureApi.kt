package il.ac.tau.lecturesummarizer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
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

class HttpLectureApi(
    private val baseUrl: String,
    private val token: String,
    private val deviceId: String = "",
    private val claimIds: () -> List<String> = { emptyList() },
) : LectureApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)   // segments are tens of MB
        .readTimeout(2, TimeUnit.MINUTES)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    private fun req(path: String) = Request.Builder().url("$baseUrl$path").apply {
        if (token.isNotBlank()) header("Authorization", "Bearer $token")
        if (deviceId.isNotBlank()) header("X-Device-Id", deviceId)
    }

    /**
     * Per-device migration: before the first API call of this process (per server+device), claim the legacy
     * (unowned) server courses whose remote ids this phone already stores. Only sets ownership server-side;
     * ids never change. Older servers (404) are ignored; network errors retry on the next call.
     */
    private suspend fun ensureClaimed() {
        val key = "$baseUrl|$deviceId"
        if (deviceId.isBlank() || key in claimedKeys) return
        claimMutex.withLock {
            if (key in claimedKeys) return
            val ids = runCatching { claimIds() }.getOrDefault(emptyList()).distinct()
            val body = JSONObject().put("claim_course_ids", JSONArray(ids)).toString().toRequestBody(json)
            withContext(Dispatchers.IO) {
                client.newCall(req("/v1/devices/claim").post(body).build()).execute().use { resp ->
                    if (resp.isSuccessful || resp.code == 404 || resp.code == 405) claimedKeys += key
                    else throw IOException("HTTP ${resp.code} POST /v1/devices/claim")
                }
            }
        }
    }

    private suspend fun <T> call(r: Request, f: (Response) -> T): T = withContext(Dispatchers.IO) {
        ensureClaimed()
        client.newCall(r).execute().use { resp ->
            if (!resp.isSuccessful) {
                val msg = runCatching { resp.body?.string().orEmpty() }.getOrDefault("").let { b ->
                    runCatching { JSONObject(b).let { it.optString("error").ifBlank { it.optString("message") } } }.getOrNull()
                        ?.takeIf { it.isNotBlank() } ?: b.take(200)
                }
                throw HttpStatusException(resp.code, "HTTP ${resp.code} ${r.method} ${r.url.encodedPath}" + if (msg.isNotBlank()) " — $msg" else "")
            }
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
            if (url != null) { postJson(path, JSONObject().put("url", url).put("mime", mime).put("name", fileName)); return }
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, f.asRequestBody(mime.toMediaType())).build()
            call(req(path).post(body).build()) {}
        } finally { f.delete() }
    }

    override suspend fun uploadSlides(lectureRemoteId: String, fileName: String, mime: String, data: InputStream, replaceAll: Boolean) =
        uploadDoc("/v1/lectures/$lectureRemoteId/slides" + if (replaceAll) "?replace=all" else "", fileName, mime, data)

    override suspend fun uploadCourseContext(lectureRemoteId: String, fileName: String, data: InputStream, mime: String, add: Boolean) =
        uploadDoc("/v1/lectures/$lectureRemoteId/context" + if (add) "?mode=add" else "", fileName, mime, data)

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

    override suspend fun startProcessing(lectureRemoteId: String, live: Boolean, densities: Set<String>) =
        postJson("/v1/lectures/$lectureRemoteId/process",
            JSONObject().put("options", JSONObject().put("asr", "cloud").put("diagrams", true).put("images", false).put("live", live)
                .put("density", SummaryDensity.entries.map { it.api }.firstOrNull { it in densities } ?: "standard")
                .put("densities", JSONArray(SummaryDensity.entries.map { it.api }.filter { it in densities })))
        ).getString("job_id")

    override suspend fun importTranscript(lectureRemoteId: String, import: TranscriptImport): String? {
        val segs = org.json.JSONArray()
        import.segments.forEach { g ->
            segs.put(JSONObject().put("t0", g.t0).put("t1", g.t1).put("text", g.text).apply { g.speaker?.let { put("speaker", it) } })
        }
        val body = JSONObject().put("source", import.source).put("external_id", import.externalId)
            .put("title", import.title).put("language", import.language).put("segments", segs)
        return postJson("/v1/lectures/$lectureRemoteId/transcript/import", body).optString("job_id").ifBlank { null }
    }

    override suspend fun deleteCourse(courseRemoteId: String) = withContext(Dispatchers.IO) {
        val r = req("/v1/courses/$courseRemoteId").delete().build()
        client.newCall(r).execute().use { resp -> if (!resp.isSuccessful && resp.code != 404 && resp.code != 405) throw IOException("HTTP ${resp.code} DELETE ${r.url.encodedPath}") }
    }

    override suspend fun deleteLecture(lectureRemoteId: String) = withContext(Dispatchers.IO) {
        val r = req("/v1/lectures/$lectureRemoteId").delete().build()
        client.newCall(r).execute().use { resp -> if (!resp.isSuccessful && resp.code != 404) throw IOException("HTTP ${resp.code} DELETE ${r.url.encodedPath}") }
    }

    override suspend fun jobStatus(jobId: String): JobStatus = call(req("/v1/jobs/$jobId").get().build()) {
        val o = JSONObject(it.body!!.string())
        JobStatus(o.getString("state"), o.optStringOrNull("stage"), o.optInt("progress"), o.optStringOrNull("error"),
            o.optJSONObject("progress_detail")?.optStringOrNull("label"))
    }

    override suspend fun retryJob(jobId: String): JobStatus {
        // 409 = server still has the job mid-stage (e.g. "pdf" after a killed invocation) while the app shows Failed → force.
        val o = try { postJson("/v1/jobs/$jobId/retry", JSONObject()) }
        catch (e: HttpStatusException) {
            if (e.code != 409) throw e
            // force still 409 → server job already finished ("done"): hand back its status so the poller fetches the PDF.
            try { postJson("/v1/jobs/$jobId/retry?force=1", JSONObject()) }
            catch (e2: HttpStatusException) { if (e2.code == 409) return jobStatus(jobId) else throw e2 }
        }
        return JobStatus(o.getString("state"), o.optStringOrNull("stage"), o.optInt("progress"), o.optStringOrNull("error"))
    }

    override suspend fun resummarizeJob(jobId: String, density: String?): JobStatus {
        // 409 = server thinks the job is mid-flight (e.g. stuck in summary with a stale "Done"); retry once with force.
        val q = density?.let { "density=$it" }
        val o = try { postJson("/v1/jobs/$jobId/resummarize" + (q?.let { "?$it" } ?: ""), JSONObject()) }
        catch (e: HttpStatusException) { if (e.code == 409) postJson("/v1/jobs/$jobId/resummarize?force=1" + (q?.let { "&$it" } ?: ""), JSONObject()) else throw e }
        return JobStatus(o.getString("state"), o.optStringOrNull("stage"), o.optInt("progress"), o.optStringOrNull("error"), jobId = o.optStringOrNull("job_id"))
    }

    override suspend fun reprocessJob(jobId: String, fallback: Boolean): ReprocessResult = withContext(Dispatchers.IO) {
        val r = req("/v1/jobs/$jobId/reprocess" + if (fallback) "?fallback=1" else "").post("{}".toRequestBody(json)).build()
        client.newCall(r).execute().use { resp ->
            val s = resp.body?.string().orEmpty()
            val o = runCatching { JSONObject(s) }.getOrElse { JSONObject() }
            if (resp.code == 404) throw IOException("HTTP 404 — reprocess not supported or job not found")
            if (resp.code == 409) return@use ReprocessResult(null, null, o.optBoolean("audio_missing"), o.optStringOrNull("error"))
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} POST ${r.url.encodedPath}")
            ReprocessResult(JobStatus(o.getString("state"), o.optStringOrNull("stage"), o.optInt("progress"), null),
                o.optStringOrNull("mode"), o.optBoolean("audio_missing"))
        }
    }

    override suspend fun transcript(lectureRemoteId: String): Transcript? = withContext(Dispatchers.IO) {
        val r = req("/v1/lectures/$lectureRemoteId/transcript").get().build()
        client.newCall(r).execute().use { resp ->
            if (resp.code == 404) return@use null
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} GET ${r.url.encodedPath}")
            val o = JSONObject(resp.body!!.string())
            val a = o.optJSONArray("segments") ?: JSONArray()
            Transcript(o.optString("source"), o.optBoolean("complete"), (0 until a.length()).map { a.getJSONObject(it) }
                .map { TranscriptLine(it.optDouble("t0"), it.optDouble("t1"), it.optString("text")) })
        }
    }

    override suspend fun availableDensities(lectureRemoteId: String): Set<String> = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(req("/v1/lectures/$lectureRemoteId/summaries").get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptySet()
                val d = JSONObject(resp.body!!.string()).optJSONObject("densities") ?: return@use emptySet()
                d.keys().asSequence().filter { d.optJSONObject(it)?.optStringOrNull("done_job_id") != null }.toSet()
            }
        }.getOrDefault(emptySet())
    }

    override suspend fun downloadSummaryPdf(lectureRemoteId: String, dest: File, density: String?) =
        call(req("/v1/lectures/$lectureRemoteId/summary.pdf" + (density?.let { "?density=$it" } ?: "")).get().build()) { resp ->
            val tmp = File(dest.parentFile, dest.name + ".part")
            tmp.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
            tmp.renameTo(dest); Unit
        }

    companion object {
        /** Above this, use Blob direct upload when the server offers it (Vercel body limit is 4.5 MB). */
        const val BLOB_THRESHOLD = 4L * 1024 * 1024
        private val claimMutex = kotlinx.coroutines.sync.Mutex()
        private val claimedKeys = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    }
}

/** MIME for a local audio file by extension (recorded = .m4a; imported files keep their extension). */
fun audioMime(f: File): String = when (f.extension.lowercase()) {
    "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "ogg", "oga", "opus" -> "audio/ogg"; "flac" -> "audio/flac"
    "aac" -> "audio/aac"; "webm" -> "audio/webm"; "amr" -> "audio/amr"; "3gp" -> "audio/3gpp"
    else -> "audio/mp4"
}

/** Non-2xx HTTP response; [message] includes the server's error body when present. */
class HttpStatusException(val code: Int, message: String) : IOException(message)
