package il.ac.tau.lecturesummarizer.upload

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.data.LectureState
import java.util.concurrent.TimeUnit

/**
 * Scans local lectures for Failed (idle) and PROCESSING (stuck) and restarts them on the server.
 * Periodic every 15 min (WorkManager minimum) plus a delayed one-shot after each failure.
 */
class JobAutoRecoveryWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LectureApp
        val recovery = JobAutoRecovery(app)
        val now = System.currentTimeMillis()
        for (l in app.repo.lectures.value) {
            when {
                recovery.shouldAutoRetryFailed(l, now) -> {
                    runCatching {
                        recovery.bump(l.id, "failed_idle")
                        retryLecture(app, l.id, auto = true)
                    }
                }
                recovery.shouldUnstick(l, now) -> {
                    runCatching {
                        recovery.bump(l.id, "stuck")
                        forceRestartLecture(app, l.id, auto = true)
                    }
                }
                l.state == LectureState.FAILED && l.jobId != null && !JobAutoRecovery.isDenied(l.error) -> {
                    // Ensure fail timestamp exists (upgraded installs / older failures).
                    if (recovery.failedAt(l.id) == 0L) {
                        recovery.recordFail(l.id)
                        scheduleAfterFail(app, l.id)
                    }
                }
                l.state == LectureState.PROCESSING && l.jobId != null && !l.liveJob -> {
                    // Seed progress clock if missing so stuck detection can start.
                    if (recovery.progressAt(l.id) == 0L) {
                        recovery.noteProgress(
                            l.id,
                            JobAutoRecovery.fingerprint(l.stage, l.progress, l.stageDetail, l.error),
                        )
                        JobPollWorker.enqueue(app, l.id)
                    }
                }
            }
        }
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "job-auto-recovery"
        private fun failUnique(id: String) = "job-auto-retry-$id"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<JobAutoRecoveryWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                req,
            )
            // Kick once soon after process start so already-failed lectures get a failAt + delayed retry.
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "job-auto-recovery-boot",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<JobAutoRecoveryWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInitialDelay(30, TimeUnit.SECONDS)
                    .build(),
            )
        }

        /** After a failure: wait [JobAutoRecovery.FAILED_IDLE_MS] then try again if still failed. */
        fun scheduleAfterFail(ctx: Context, lectureId: String) {
            val req = OneTimeWorkRequestBuilder<JobAutoRecoveryWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(JobAutoRecovery.FAILED_IDLE_MS, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                failUnique(lectureId),
                ExistingWorkPolicy.REPLACE,
                req,
            )
        }

        fun cancelLecture(ctx: Context, lectureId: String) {
            WorkManager.getInstance(ctx).cancelUniqueWork(failUnique(lectureId))
        }
    }
}
