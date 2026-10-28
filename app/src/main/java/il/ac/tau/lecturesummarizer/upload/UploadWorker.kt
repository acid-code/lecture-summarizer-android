package il.ac.tau.lecturesummarizer.upload

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.BackoffPolicy
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

            lecture.slidesUri?.let { s ->
                val uri = Uri.parse(s)
                val mime = applicationContext.contentResolver.getType(uri) ?: "application/pdf"
                applicationContext.contentResolver.openInputStream(uri)?.let { api.uploadSlides(remote, displayName(uri), mime, it) }
            }
            lecture.contextPdfUri?.let { s ->
                val uri = Uri.parse(s)
                applicationContext.contentResolver.openInputStream(uri)?.let { api.uploadCourseContext(remote, displayName(uri), it) }
            }

            lecture = repo.lecture(id)!!
            for (seg in lecture.segments.filterNot { it.uploaded }.sortedBy { it.seq }) {
                api.uploadSegment(remote, seg, File(seg.path))
                repo.updateLecture(id) { l -> l.copy(segments = l.segments.map { if (it.seq == seg.seq) it.copy(uploaded = true) else it }) }
            }
            if (lecture.markers.isNotEmpty()) api.uploadMarkers(remote, lecture.markers)

            val jobId = repo.lecture(id)?.jobId ?: api.startProcessing(remote)
            repo.updateLecture(id) { it.copy(jobId = jobId, state = LectureState.PROCESSING) }
            Result.success()
        } catch (e: Exception) {
            repo.updateLecture(id) { it.copy(error = e.message) }
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry()
            else { repo.updateLecture(id) { it.copy(state = LectureState.FAILED) }; Result.failure() }
        }
    }

    private fun displayName(uri: Uri): String =
        applicationContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"

    companion object {
        const val KEY_LECTURE = "lecture"
        private const val MAX_ATTEMPTS = 8

        fun enqueue(ctx: Context, lectureId: String) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(KEY_LECTURE to lectureId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("upload-$lectureId", ExistingWorkPolicy.KEEP, req)
        }
    }
}
