package il.ac.tau.lecturesummarizer.upload

import android.content.Context
import android.net.Uri
import il.ac.tau.lecturesummarizer.data.MAX_COURSE_CONTEXT
import il.ac.tau.lecturesummarizer.data.allContext
import android.provider.OpenableColumns
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.ForegroundInfo
import kotlinx.coroutines.CancellationException
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.data.ApiProvider
import il.ac.tau.lecturesummarizer.data.LectureState
import il.ac.tau.lecturesummarizer.data.allSlides
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Idempotent upload pipeline. Each step records progress in the Repository
 * (remoteId, segment.uploaded, jobId), so a retry resumes where it failed.
 * WorkManager retries with exponential backoff on any IOException.
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LectureApp
        val repo = app.repo
        val api = ApiProvider.get(app.settings)
        val id = inputData.getString(KEY_LECTURE) ?: return Result.failure()
        // Live = mid-recording: upload finished parts and keep a server job transcribing them; no state/UI changes.
        val live = inputData.getBoolean(KEY_LIVE, false)
        if (live && repo.lecture(id)?.state != LectureState.RECORDING && il.ac.tau.lecturesummarizer.panopto.PanoptoAudioWorker.extracting[id] != true) return Result.success()

        // Live part uploads ride under the Panopto queue notification (no duplicate per-part notifications).
        if (!live) try { setForeground(getForegroundInfo()) } catch (_: Exception) { /* best effort (e.g. FGS start not allowed) */ }

        return try {
            var lecture = repo.lecture(id) ?: return Result.failure()
            val hadFinished = lecture.state == LectureState.DONE || lecture.state == LectureState.FAILED || lecture.state == LectureState.RECORDED
            if (!live) repo.updateLecture(id) { it.copy(state = LectureState.UPLOADING, error = null, stage = null, stageDetail = null, progress = 0) }
            val course = repo.course(lecture.courseId) ?: return Result.failure()

            val courseRemote = course.remoteId ?: api.createCourse(course.name).also { rid ->
                repo.updateCourse(course.id) { it.copy(remoteId = rid) }
            }
            val remote = lecture.remoteId ?: api.createLecture(courseRemote, lecture.title, lecture.recordedAtMs).also { rid ->
                repo.updateLecture(id) { it.copy(remoteId = rid) }
            }

            val freshness=il.ac.tau.lecturesummarizer.slides.SummaryFreshness(app)
            val slideRevision=freshness.revision(lecture)
            if(lecture.slideRanges.keys.any{it in lecture.allSlides} && !api.supportsSlideRanges(remote))throw IllegalStateException("Slide ranges require an updated API")
            if(!live && lecture.allSlides.isEmpty())api.clearSlides(remote)
            // Live runs send slides once (ASR spelling hints) and skip course context; the final run sends everything.
            if (!live || lecture.uploadedSlides.isEmpty()) for ((i, s) in lecture.allSlides.withIndex()) {
                val uri = Uri.parse(s)
                val mime = applicationContext.contentResolver.getType(uri) ?: "application/pdf"
                (applicationContext.contentResolver.openInputStream(uri) ?: error(applicationContext.getString(il.ac.tau.lecturesummarizer.R.string.slides_error))).use { api.uploadSlides(remote, il.ac.tau.lecturesummarizer.slides.slideUploadName(lecture,s){displayName(Uri.parse(it))}, mime, it, replaceAll = i == 0,range=lecture.slideRanges[s]) }
                repo.updateLecture(id) { l -> l.copy(uploadedSlides = (if (i == 0) emptyList() else l.uploadedSlides) + s) }
            }
            // Course context: lecture override (single) or all course files (max 3). First replaces, rest are added.
            if (!live) (lecture.contextPdfUri?.let { listOf(it) } ?: course.allContext.take(MAX_COURSE_CONTEXT)).forEachIndexed { i, s ->
                val uri = Uri.parse(s)
                val mime = applicationContext.contentResolver.getType(uri) ?: "application/pdf"
                applicationContext.contentResolver.openInputStream(uri)?.let { api.uploadCourseContext(remote, displayName(uri), it, mime, add = i > 0) }
            }

            lecture = repo.lecture(id)!!
            val pending = lecture.segments.filterNot { it.uploaded }.sortedBy { it.seq }
            for ((n, seg) in pending.withIndex()) {
                if (isStopped) return interrupted(id)
                runCatching { setForeground(info(applicationContext.getString(il.ac.tau.lecturesummarizer.R.string.upload_part, n+1,pending.size))) }
                api.uploadSegment(remote, seg, File(seg.path))
                repo.updateLecture(id) { l -> l.copy(segments = l.segments.map { if (it.seq == seg.seq) it.copy(uploaded = true) else it }) }
            }
            if (lecture.markers.isNotEmpty()) api.uploadMarkers(remote, lecture.markers)

            val cur = repo.lecture(id)!!
            if (live) {
                // Don't hijack an older (non-live) job, e.g. "Record another" after a finished summary.
                if (cur.jobId != null && !cur.liveJob) return Result.success()
                val jobId = api.startProcessing(remote, live = true, densities = (applicationContext as il.ac.tau.lecturesummarizer.LectureApp).settings.summaryDensities) // idempotent: server reuses the live job
                repo.updateLecture(id) { it.copy(jobId = jobId, liveJob = true) }
                JobPollWorker.enqueue(applicationContext, id)
                return Result.success()
            }
            if(freshness.revision(cur)!=slideRevision)return Result.retry()
            val panoptoOnly = cur.fromPanopto && cur.segments.isEmpty()
            val jobId = if (panoptoOnly) {
                // Panopto entry: captions replace ASR (never fall back to a slides-only job). Slides above are already uploaded.
                val srt = cur.panoptoSrtPath?.let(::File)?.takeIf { it.exists() }
                    ?: error("Panopto captions not downloaded yet — open the lecture and tap Retry captions")
                val segs = il.ac.tau.lecturesummarizer.moodle.Panopto.parseSrt(srt.readText())
                if (segs.isEmpty()) error("Panopto captions are empty")
                JobKick.kick(id)
                api.importTranscript(remote, il.ac.tau.lecturesummarizer.data.TranscriptImport("panopto", cur.panoptoId!!, cur.panoptoTitle ?: cur.title,
                    il.ac.tau.lecturesummarizer.moodle.Panopto.languageOf(segs), segs)) ?: error("Server didn't start a captions job (transcript import returned no job id)")
            } else if (cur.jobId == null || cur.liveJob || hadFinished) { JobKick.kick(id); api.startProcessing(remote, densities = (applicationContext as il.ac.tau.lecturesummarizer.LectureApp).settings.summaryDensities) } else cur.jobId
            if(cur.jobId==null || jobId!=cur.jobId || cur.liveJob)freshness.started(id,jobId,slideRevision)
            repo.updateLecture(id) { it.copy(jobId = jobId, liveJob = false, state = LectureState.PROCESSING) }
            JobPollWorker.enqueue(applicationContext, id)
            Result.success()
        } catch (e: CancellationException) {
            // App swiped away / WorkManager stopped us: not a real failure. Segments already
            // uploaded stay flagged, so the next run resumes. Re-throw so WorkManager reschedules.
            repo.updateLecture(id) { it.copy(error = INTERRUPTED) }
            throw e
        } catch (e: Exception) {
            if (live) return if (runAttemptCount < 3) Result.retry() else Result.success() // final upload catches up
            if (isStopped) return interrupted(id)
            repo.updateLecture(id) { it.copy(error = e.message) }
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry()
            else { repo.updateLecture(id) { it.copy(state = LectureState.FAILED) }; Result.failure() }
        }
    }

    private suspend fun interrupted(id: String): Result {
        (applicationContext as LectureApp).repo.updateLecture(id) { it.copy(error = INTERRUPTED) }
        return Result.retry()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = info(applicationContext.getString(il.ac.tau.lecturesummarizer.R.string.upload_for_summary))

    private fun info(text: String): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, LectureApp.CHANNEL_UPLOAD)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(inputData.getString(KEY_LECTURE)?.let { id -> (applicationContext as LectureApp).repo.lecture(id)?.let { l ->
                listOfNotNull(l.title, (applicationContext as LectureApp).repo.course(l.courseId)?.name).joinToString(" · ") } } ?: "Lecture Summarizer")
            .setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setProgress(0, 0, true)
            .build()
        val nid = NOTIF_BASE + ((inputData.getString(KEY_LECTURE)?.hashCode() ?: 0) and 0xFFFF)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(nid, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(nid, n)
    }

    private fun displayName(uri: Uri): String =
        applicationContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"

    companion object {
        const val KEY_LECTURE = "lecture"
        const val KEY_LIVE = "live"
        private const val MAX_ATTEMPTS = 8
        private const val NOTIF_BASE = 0x20000
        const val INTERRUPTED = "Upload interrupted (app closed) — resumes automatically, or tap Retry upload"

        /** [retry] = explicit user retry: REPLACE so a cancelled/failed previous run doesn't swallow it. */
        fun enqueue(ctx: Context, lectureId: String, retry: Boolean = false, live: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(KEY_LECTURE to lectureId, KEY_LIVE to live))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("upload-$lectureId", if (retry) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}
