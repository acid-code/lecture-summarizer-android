package il.ac.tau.lecturesummarizer.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.data.ApiProvider
import il.ac.tau.lecturesummarizer.data.LectureState
import java.util.concurrent.TimeUnit

/** Every-15-minute phone nudge: retries uploads and asks the server to advance stuck jobs. */
class JobNudgeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        val app = applicationContext as LectureApp
        val api = ApiProvider.get(app.settings)
        api.nudgeJobs()

        for (lecture in app.repo.lectures.value) {
            val hasLocalSegments = lecture.segments.any { seg ->
                java.io.File(seg.path).exists()
            }
            if (hasLocalSegments && lecture.state in setOf(
                    LectureState.FAILED,
                    LectureState.UPLOADING,
                    LectureState.RECORDED,
                )) {
                UploadWorker.enqueue(app, lecture.id, retry = true)
            } else if (lecture.jobId != null &&
                lecture.state in setOf(LectureState.PROCESSING, LectureState.UPLOADING)) {
                JobPollWorker.enqueue(app, lecture.id, replace = true)
            }
        }
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        fun schedule(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<JobNudgeWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "job-nudge-15m",
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
