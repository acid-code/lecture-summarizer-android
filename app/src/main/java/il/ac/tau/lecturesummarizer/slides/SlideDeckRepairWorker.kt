package il.ac.tau.lecturesummarizer.slides

import android.content.Context
import androidx.work.*
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.autosync.AutoSyncStore
import il.ac.tau.lecturesummarizer.autosync.RecordingMatcher
import il.ac.tau.lecturesummarizer.data.LectureState
import il.ac.tau.lecturesummarizer.data.allSlides
import il.ac.tau.lecturesummarizer.moodle.MoodleAuthRequired
import il.ac.tau.lecturesummarizer.moodle.MoodleClient
import il.ac.tau.lecturesummarizer.moodle.MoodleCourse
import il.ac.tau.lecturesummarizer.moodle.MoodleDeckAttach
import il.ac.tau.lecturesummarizer.moodle.MoodleStore
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * One-shot repair for lectures that lost decks when early Moodle auto-attach was missing.
 * Only [MoodleDeckAttach] / [il.ac.tau.lecturesummarizer.moodle.MoodleMatch.autoPick] — never term-search suggestions.
 * Re-summarizes only lectures that newly received a deck.
 */
class SlideDeckRepairWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LectureApp
        val store = MoodleStore(app)
        val st = AutoSyncStore(app)
        if (!st.autoAttach) {
            status.set("Slides search is off")
            return Result.success()
        }
        var attached = 0
        var skipped = 0
        var refreshed = 0
        var auth = false
        status.set("Repairing…")
        val candidates = app.repo.lectures.value.filter { l ->
            l.state == LectureState.DONE &&
                l.allSlides.isEmpty() &&
                l.jobId != null &&
                l.remoteId != null &&
                !SlideChoices(app).isManual(l.id) &&
                store.lectureCourse(l.id) != null
        }
        for (l in candidates) {
            try {
                val courseId = store.lectureCourse(l.id)
                if (courseId == null) { skipped++; continue }
                val site = store.lectureBase(l.id)
                val client = MoodleClient.of(site)
                val course = client.courses().firstOrNull { it.id == courseId }
                    ?: MoodleCourse(courseId, "", "", "")
                val dm = RecordingMatcher.ymdOf(if (l.recordedAtMs > 0) l.recordedAtMs else System.currentTimeMillis())
                val title = store.lectureRecording(l.id) ?: l.panoptoTitle ?: l.title
                val ok = MoodleDeckAttach.tryAttach(app, l.id, client, store, course, title, dm.d to dm.m)
                if (!ok) {
                    skipped++
                    continue
                }
                attached++
                SummaryRefreshWorker.enqueue(app, l.id)
                refreshed++
            } catch (e: CancellationException) {
                throw e
            } catch (_: MoodleAuthRequired) {
                auth = true
                skipped++
            } catch (_: Exception) {
                skipped++
            }
            status.set("Attached $attached · skipped $skipped · refresh $refreshed")
            if (isStopped) break
        }
        status.set(
            when {
                auth && attached == 0 -> "Moodle sign-in required"
                else -> "Done: attached $attached · skipped $skipped · refresh $refreshed"
            },
        )
        return Result.success()
    }

    companion object {
        val status = AtomicReference<String?>(null)
        fun enqueue(ctx: Context) {
            status.set("Queued…")
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "slides-deck-repair",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SlideDeckRepairWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }
}
