package il.ac.tau.lecturesummarizer.panopto

import android.content.Context
import androidx.work.BackoffPolicy
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
import il.ac.tau.lecturesummarizer.moodle.CaptionQuality
import il.ac.tau.lecturesummarizer.moodle.ConnectionAuth
import il.ac.tau.lecturesummarizer.moodle.MoodleAuthRequired
import il.ac.tau.lecturesummarizer.moodle.MoodleClient
import il.ac.tau.lecturesummarizer.moodle.MoodleStore
import il.ac.tau.lecturesummarizer.moodle.Panopto
import il.ac.tau.lecturesummarizer.moodle.PanoptoAuthRequired
import il.ac.tau.lecturesummarizer.moodle.PanoptoRecording
import il.ac.tau.lecturesummarizer.ui.PanoptoFetch
import il.ac.tau.lecturesummarizer.upload.UploadWorker
import java.io.File
import java.util.concurrent.TimeUnit

class PanoptoSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LectureApp
        val lectureId = inputData.getString(KEY_LECTURE)
        if (lectureId == null) {
            enqueueAll(app)
            return Result.success()
        }

        val lecture = app.repo.lecture(lectureId) ?: return Result.success()
        val deliveryId = lecture.panoptoId ?: return Result.success()
        if (lecture.panoptoSrtPath?.let { File(it).isFile } == true) return Result.success()
        if (lecture.panoptoAudio && lecture.segments.isNotEmpty()) return Result.success()

        PanoptoFetch.status[lectureId] = PanoptoFetch.LOADING
        return try {
            val client = MoodleClient.of(MoodleStore(app).baseUrl)
            val recording = PanoptoRecording(deliveryId, lecture.panoptoTitle ?: "", lecture.panoptoUrl ?: Panopto.HOST)
            val info = runCatching { client.deliveryInfo(recording) }.getOrNull()
            val videoSec = info?.durationSec?.takeIf { it > 0 }
            if (videoSec != null) {
                app.repo.updateLecture(lectureId) { it.copy(panoptoDurationMs = (videoSec * 1000).toLong()) }
            }

            val srt = client.downloadCaptions(recording)
            val segments = Panopto.parseSrt(srt)
            if (!CaptionQuality.captionsUsable(segments, videoSec)) {
                if (info?.hasAudio == false) throw IllegalStateException(Panopto.NO_CAPTIONS)
                app.repo.updateLecture(lectureId) {
                    it.copy(panoptoSrtPath = null, panoptoAudio = true, segments = emptyList(), error = null)
                }
                PanoptoFetch.status[lectureId] = PanoptoFetch.OK
                PanoptoAudioWorker.enqueue(app, lectureId, wifiOnly = true)
                return Result.success()
            }

            val file = File(app.filesDir, "panopto/$lectureId.srt").apply {
                parentFile?.mkdirs()
                writeText(srt)
            }
            app.repo.updateLecture(lectureId) {
                it.copy(panoptoSrtPath = file.absolutePath, panoptoAudio = false, error = null)
            }
            PanoptoFetch.status[lectureId] = PanoptoFetch.OK
            ConnectionAuth.clearPanopto(app)

            if (lecture.state != LectureState.PROCESSING) {
                UploadWorker.enqueue(app, lectureId, retry = true)
            }
            Result.success()
        } catch (e: PanoptoAuthRequired) {
            PanoptoFetch.status[lectureId] = PanoptoFetch.AUTH
            ConnectionAuth.markPanoptoNeeded(app)
            Result.retry()
        } catch (e: MoodleAuthRequired) {
            PanoptoFetch.status[lectureId] = PanoptoFetch.AUTH
            ConnectionAuth.markMoodleNeeded(app)
            Result.retry()
        } catch (e: Exception) {
            PanoptoFetch.status[lectureId] = "error:${e.message ?: "Panopto sync failed"}"
            if (runAttemptCount < 8) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_LECTURE = "lecture"
        private const val PERIODIC = "panopto-sync-periodic"

        fun enqueue(ctx: Context, lectureId: String) {
            val request = OneTimeWorkRequestBuilder<PanoptoSyncWorker>()
                .setInputData(androidx.work.workDataOf(KEY_LECTURE to lectureId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "panopto-sync-$lectureId",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun enqueueAll(app: LectureApp) {
            app.repo.lectures.value
                .filter { it.fromPanopto }
                .filter { it.panoptoSrtPath?.let { path -> File(path).isFile } != true }
                .filter { !it.panoptoAudio || it.segments.isEmpty() }
                .forEach { enqueue(app, it.id) }
        }

        fun schedule(app: LectureApp) {
            val request = PeriodicWorkRequestBuilder<PanoptoSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
            enqueueAll(app)
        }
    }
}
