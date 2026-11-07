package il.ac.tau.lecturesummarizer.slides

import android.content.Context
import androidx.core.content.FileProvider
import androidx.work.*
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.autosync.AutoSyncStore
import il.ac.tau.lecturesummarizer.data.*
import il.ac.tau.lecturesummarizer.moodle.*
import il.ac.tau.lecturesummarizer.upload.JobPollWorker
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Runs after the first transcript-backed job completes. Never guesses by file order/date. */
class SlideMatchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LectureApp
        val id = inputData.getString("lecture") ?: return Result.failure()
        val choices = SlideChoices(app)
        val l = app.repo.lecture(id) ?: return Result.success()
        if (!AutoSyncStore(app).autoAttach || choices.isManual(id) || choices.phase(id) == "done") return Result.success()
        if (l.state != LectureState.DONE && choices.phase(id) != "uploaded") return Result.success()
        // A previously uploaded choice is safe to resume after a process death.
        if (l.allSlides.isNotEmpty() && choices.phase(id) != "uploaded") return Result.success()
        val remote = l.remoteId ?: return Result.success()
        val job = l.jobId ?: return Result.success()
        val store = MoodleStore(app)
        val course = store.lectureCourse(id) ?: return Result.success()
        val api = ApiProvider.get(app.settings)
        try {
            if (choices.phase(id) != "uploaded") {
                choices.phase(id, "checking")
                val transcript = api.transcript(remote) ?: return if (runAttemptCount < 3) Result.retry() else Result.success()
                if (!transcript.complete) return if (runAttemptCount < 3) Result.retry() else Result.success()
                val client = MoodleClient(store.baseUrl)
                val resources = client.sections(course).flatMap { it.files }.distinctBy { it.cmid }
                    .filter { it.cmid.toString() !in choices.rejected(id) }
                // Do not silently truncate a course and incorrectly declare a winner.
                if (resources.size > 60) { choices.phase(id, "done"); return Result.success() }
                var downloadedBytes = 0L
                val files = resources.mapNotNull { f ->
                    if (downloadedBytes >= 120L * 1024 * 1024) return@mapNotNull null
                    try {
                        val file = store.cached(f.cmid) ?: client.download(f, store.cacheDir, maxBytes = 40L * 1024 * 1024).also { store.putCached(f.cmid, it) }
                        downloadedBytes += file.length()
                        val text = SlideText.read(app, file)
                        if (text.isBlank()) null else Triple(f, file, SlideMatcher.Deck(f.cmid, file.name, text))
                    } catch (e: CancellationException) { throw e }
                    catch (e: MoodleAuthRequired) { throw e }
                    catch (_: Exception) { null }
                }
                val ranked = SlideMatcher.rank(files.map { it.third }, transcript.lines.joinToString(" ") { it.text })
                val winner = if (files.size == resources.size) SlideMatcher.clearWinner(ranked) else null
                choices.candidates(id, JSONArray().apply { ranked.take(5).filter { it.terms.isNotEmpty() }.forEach { m ->
                    put(JSONObject().put("id", m.deck.id).put("name", m.deck.name).put("terms", JSONArray(m.terms.take(12))))
                } }.toString())
                if (winner == null) { choices.phase(id, "done"); return Result.success() }
                // A manual picker may have completed while the downloads were in flight.
                if (choices.isManual(id) || app.repo.lecture(id)?.state != LectureState.DONE || app.repo.lecture(id)?.jobId != job || app.repo.lecture(id)?.allSlides?.isNotEmpty() != false) return Result.success()
                val file = files.single { it.third.id == winner.deck.id }.second
                val mime = if (file.extension.equals("pdf", true)) "application/pdf" else "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                file.inputStream().use { api.uploadSlides(remote, file.name, mime, it) }
                val uri = FileProvider.getUriForFile(app, app.packageName + ".files", file).toString()
                if (choices.isManual(id)) return Result.success()
                choices.phase(id, "uploaded")
                app.repo.updateLecture(id) { it.copy(slidesUri = uri, uploadedSlides = (it.uploadedSlides + uri).distinct()) }
                choices.auto(id, file.name, listOf("same_course", "terms:${winner.terms.size}"))
            }
            // The existing ASR is retained. Only summary/PDF are rebuilt.
            val st = api.resummarizeJob(job)
            app.repo.updateLecture(id) { it.copy(jobId = st.jobId ?: job, state = LectureState.PROCESSING,
                stage = st.stage, stageDetail = null, progress = st.progress, pdfPath = null, error = null) }
            choices.phase(id, "done")
            JobPollWorker.enqueue(app, id)
            return Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            if (runAttemptCount >= 3 && choices.phase(id) != "uploaded") choices.phase(id, "done")
            return if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }
    companion object {
        fun enqueue(ctx: Context, id: String) {
            val choices = SlideChoices(ctx)
            if (choices.isManual(id) || choices.phase(id) == "done") return
            if (choices.phase(id) == null) choices.phase(id, "queued")
            WorkManager.getInstance(ctx).enqueueUniqueWork("slides-match-$id", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SlideMatchWorker>().setInputData(workDataOf("lecture" to id))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
    }
}
