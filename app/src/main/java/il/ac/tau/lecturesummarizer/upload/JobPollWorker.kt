package il.ac.tau.lecturesummarizer.upload

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.MainActivity
import il.ac.tau.lecturesummarizer.data.ApiProvider
import il.ac.tau.lecturesummarizer.data.LectureState
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Background poll of a server job (no Firebase): advances/polls every 15 s for up to ~8 min per run,
 * then re-enqueues itself. On done: downloads the study PDF and posts a local notification; on failed: notifies too.
 */
class JobPollWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LectureApp
        val id = inputData.getString(UploadWorker.KEY_LECTURE) ?: return Result.failure()
        val api = ApiProvider.get(app.settings)
        val until = System.currentTimeMillis() + 8 * 60_000
        while (System.currentTimeMillis() < until) {
            val l = app.repo.lecture(id) ?: return Result.success()
            if (l.state != LectureState.PROCESSING) return Result.success()
            val jobId = l.jobId ?: return Result.success()
            val st = runCatching { api.jobStatus(jobId) }.getOrNull()
            if (st != null) {
                app.repo.updateLecture(id) { it.copy(stage = st.stage, progress = st.progress, error = st.error, stageDetail = st.detail) }
                if (st.state == "done") {
                    val dest = File(app.repo.pdfDir(), "$id.pdf")
                    runCatching { api.downloadSummaryPdf(l.remoteId!!, dest) }
                        .onSuccess { app.repo.updateLecture(id) { it.copy(state = LectureState.DONE, pdfPath = dest.absolutePath, progress = 100, error = null) } }
                        .onFailure { e -> app.repo.updateLecture(id) { it.copy(state = LectureState.DONE, error = "PDF download failed: ${e.message}") } }
                    notify(id, "Study summary ready", l.title)
                    return Result.success()
                }
                if (st.state == "failed") {
                    app.repo.updateLecture(id) { it.copy(state = LectureState.FAILED) }
                    notify(id, "Processing failed", (st.error ?: l.title).take(200))
                    return Result.success()
                }
            }
            delay(if (app.settings.mockMode) 1000 else 15_000)
        }
        enqueue(applicationContext, id, replace = true)
        return Result.success()
    }

    private fun notify(id: String, title: String, text: String) {
        val ctx = applicationContext
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(ctx, id.hashCode(),
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, LectureApp.CHANNEL_JOBS)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build()
        NotificationManagerCompat.from(ctx).notify(id.hashCode(), n)
    }

    companion object {
        fun enqueue(ctx: Context, lectureId: String, replace: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<JobPollWorker>()
                .setInputData(workDataOf(UploadWorker.KEY_LECTURE to lectureId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .apply { if (replace) setInitialDelay(5, TimeUnit.SECONDS) }
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("poll-$lectureId",
                if (replace) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, req)
        }
    }
}
