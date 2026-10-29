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

        try { setForeground(getForegroundInfo()) } catch (_: Exception) { /* best effort (e.g. FGS start not allowed) */ }

        return try {
            var lecture = repo.lecture(id) ?: return Result.failure()
            repo.updateLecture(id) { it.copy(state = LectureState.UPLOADING, error = null) }
            val course = repo.course(lecture.courseId) ?: return Result.failure()

            val courseRemote = course.remoteId ?: api.createCourse(course.name).also { rid ->
                repo.updateCourse(course.id) { it.copy(remoteId = rid) }
            }
            val remote = lecture.remoteId ?: api.createLecture(courseRemote, lecture.title, lecture.recordedAtMs).also { rid ->
                repo.updateLecture(id) { it.copy(remoteId = rid) }
            }

            for ((i, s) in lecture.allSlides.withIndex()) {
                val uri = Uri.parse(s)
                val mime = applicationContext.contentResolver.getType(uri) ?: "application/pdf"
                applicationContext.contentResolver.openInputStream(uri)?.let { api.uploadSlides(remote, displayName(uri), mime, it, replaceAll = i == 0) }
                repo.updateLecture(id) { l -> l.copy(uploadedSlides = (if (i == 0) emptyList() else l.uploadedSlides) + s) }
            }
            // Course context: lecture override (single) or all course files (max 3). First replaces, rest are added.
            (lecture.contextPdfUri?.let { listOf(it) } ?: course.allContext.take(MAX_COURSE_CONTEXT)).forEachIndexed { i, s ->
                val uri = Uri.parse(s)
                val mime = applicationContext.contentResolver.getType(uri) ?: "application/pdf"
                applicationContext.contentResolver.openInputStream(uri)?.let { api.uploadCourseContext(remote, displayName(uri), it, mime, add = i > 0) }
            }

            lecture = repo.lecture(id)!!
            val pending = lecture.segments.filterNot { it.uploaded }.sortedBy { it.seq }
            for ((n, seg) in pending.withIndex()) {
                if (isStopped) return interrupted(id)
                runCatching { setForeground(info("Uploading part ${n + 1} of ${pending.size}…")) }
                api.uploadSegment(remote, seg, File(seg.path))
                repo.updateLecture(id) { l -> l.copy(segments = l.segments.map { if (it.seq == seg.seq) it.copy(uploaded = true) else it }) }
            }
            if (lecture.markers.isNotEmpty()) api.uploadMarkers(remote, lecture.markers)

            val jobId = repo.lecture(id)?.jobId ?: api.startProcessing(remote)
            repo.updateLecture(id) { it.copy(jobId = jobId, state = LectureState.PROCESSING) }
            JobPollWorker.enqueue(applicationContext, id)
            Result.success()
        } catch (e: CancellationException) {
            // App swiped away / WorkManager stopped us: not a real failure. Segments already
            // uploaded stay flagged, so the next run resumes. Re-throw so WorkManager reschedules.
            repo.updateLecture(id) { it.copy(error = INTERRUPTED) }
            throw e
        } catch (e: Exception) {
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

    override suspend fun getForegroundInfo(): ForegroundInfo = info("Uploading lecture…")

    private fun info(text: String): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, LectureApp.CHANNEL_UPLOAD)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Lecture Summarizer")
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
        private const val MAX_ATTEMPTS = 8
        private const val NOTIF_BASE = 0x20000
        const val INTERRUPTED = "Upload interrupted (app closed) — resumes automatically, or tap Retry upload"

        /** [retry] = explicit user retry: REPLACE so a cancelled/failed previous run doesn't swallow it. */
        fun enqueue(ctx: Context, lectureId: String, retry: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(KEY_LECTURE to lectureId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("upload-$lectureId", if (retry) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
        }
    }
}
