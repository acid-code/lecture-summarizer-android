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
 * Background poll of a server job (no Firebase): advances/polls every few seconds for up to ~8 min per run,
 * then re-enqueues itself. On done: downloads the study PDF and posts a local notification; on failed: notifies too.
 */
class JobPollWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LectureApp
        val id = inputData.getString(UploadWorker.KEY_LECTURE) ?: return Result.failure()
        val api = ApiProvider.get(app.settings)
        val recovery = JobAutoRecovery(app)
        val until = System.currentTimeMillis() + 8 * 60_000
        // Re-adopt whenever new "updating" siblings appear — derive compact/standard are spawned only after
        // detailed hits done, so a one-shot adopt at summary misses them forever.
        var adoptedKeys = emptySet<String>()
        while (System.currentTimeMillis() < until) {
            val l = app.repo.lecture(id) ?: return Result.success()
            // Live job (ASR while recording): poll so the server advances, but the lecture stays in its recording state.
            val live = l.liveJob && l.state != LectureState.PROCESSING
            if (l.state != LectureState.PROCESSING && !(live && l.state in LIVE_STATES)) return Result.success()
            val jobId = l.jobId ?: return Result.success()
            // Client-side stuck recovery (Hobby has no sub-daily server cron).
            if (!live && recovery.shouldUnstick(l)) {
                recovery.bump(id, "stuck")
                runCatching { forceRestartLecture(app, id, auto = true) }
                delay(POLL_MS)
                continue
            }
            val st = runCatching { api.jobStatus(jobId) }.getOrNull()
            if (st != null && !live && JobKick.staleDone(id, st.state)) { delay(2000); continue }
            if (st != null) {
                app.repo.updateLecture(id) { it.copy(stage = st.stage, progress = st.progress, error = st.error, stageDetail = st.detail) }
                if (!live && st.state != "done" && st.state != "failed") {
                    recovery.noteProgress(id, JobAutoRecovery.fingerprint(st.stage, st.progress, st.detail, st.error))
                }
                if (live) {
                    if (st.state == "failed") return Result.success() // next part upload resumes it on the server
                    delay(POLL_MS); continue
                }
                if ((st.stage == "summary" || st.stage == "pdf" || st.state == "done") && l.remoteId != null) {
                    adoptedKeys = adoptSiblings(app, id, l.remoteId!!, jobId, api, adoptedKeys)
                }
                if (st.state == "done") {
                    recovery.clear(id)
                    JobAutoRecoveryWorker.cancelLecture(app, id)
                    // Final adopt + advance-all so deferred derive siblings are polled before we go local DONE.
                    if (l.remoteId != null) {
                        runCatching { api.advanceAllJobs(l.remoteId!!) }
                        adoptSiblings(app, id, l.remoteId!!, jobId, api, emptySet())
                    }
                    val density=st.density?.takeIf{d->il.ac.tau.lecturesummarizer.data.SummaryDensity.entries.any{it.api==d}}
                    val dest = File(app.repo.pdfDir(), if(density!=null)"$id-$density.pdf"else "$id.pdf")
                    runCatching { api.downloadSummaryPdf(l.remoteId!!, dest,density) }
                        .onSuccess {
                            if(density!=null){
                                il.ac.tau.lecturesummarizer.slides.SummaryPdfCache(app.repo.pdfDir(),id).setToken(density,"server:$jobId")
                                val f=il.ac.tau.lecturesummarizer.slides.SummaryFreshness(app);f.sourceRevision(id,jobId)?.let{f.built(id,density,it)}
                            }
                            app.repo.updateLecture(id) { it.copy(state = LectureState.DONE, pdfPath = dest.absolutePath, progress = 100, error = null) }
                        }
                        .onFailure { e -> app.repo.updateLecture(id) { it.copy(state = LectureState.DONE, error = applicationContext.getString(il.ac.tau.lecturesummarizer.R.string.summary_download_failed)) } }
                    runCatching{val index=il.ac.tau.lecturesummarizer.data.StudyIndex(app);api.transcript(l.remoteId!!)?.let{index.put(id,"transcript",it.lines.joinToString(" "){line->line.text})};if(dest.exists())index.put(id,"summary:${density ?: "unknown"}",il.ac.tau.lecturesummarizer.slides.SlideText.read(app,dest))}
                    try{val study=il.ac.tau.lecturesummarizer.study.StudyStore(app);if(!study.delivered(id,dest,density ?: "standard",jobId))study.refresh(api,id,l.remoteId!!,density,jobId)}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){/* PDF stays usable; Study screen can retry glossary download. */}
                    il.ac.tau.lecturesummarizer.slides.SlideMatchWorker.enqueue(app, id)
                    notify(id, applicationContext.getString(il.ac.tau.lecturesummarizer.R.string.summary_ready_notification), named(app, l))
                    return Result.success()
                }
                if (st.state == "failed") {
                    app.repo.updateLecture(id) { it.copy(state = LectureState.FAILED) }
                    recovery.recordFail(id)
                    val canAuto = recovery.attempts(id) < JobAutoRecovery.MAX_ATTEMPTS && !JobAutoRecovery.isDenied(st.error)
                    if (canAuto) JobAutoRecoveryWorker.scheduleAfterFail(app, id)
                    // Notify once; auto-retry will re-poll silently if the user does not tap Try Again.
                    notify(id, applicationContext.getString(il.ac.tau.lecturesummarizer.R.string.summary_failed_notification, il.ac.tau.lecturesummarizer.ui.bidi(named(app,l))), applicationContext.getString(il.ac.tau.lecturesummarizer.R.string.summary_failure_body))
                    return Result.success()
                }
            }
            // ASR/queued: poll again immediately so the server can chain windows; heavy stages need a short pause.
            val wait = when {
                app.settings.mockMode -> 1000L
                st?.stage in setOf("asr", "queued", null) -> 2_000L
                else -> POLL_MS
            }
            delay(wait)
        }
        enqueue(applicationContext, id, replace = true)
        return Result.success()
    }

    /** @return updated set of adopted "density:jobId" keys so we only enqueue when new siblings appear. */
    private suspend fun adoptSiblings(
        app: LectureApp,
        id: String,
        remote: String,
        mainJobId: String,
        api: il.ac.tau.lecturesummarizer.data.LectureApi,
        already: Set<String>,
    ): Set<String> {
        val states = runCatching { api.summaryJobStates(remote) }.getOrNull() ?: return already
        val siblings = states.filter { (_, s) -> s.state == "updating" && s.jobId.isNotBlank() && s.jobId != mainJobId }
            .mapValues { it.value.jobId }
        if (siblings.isEmpty()) return already
        val keys = siblings.map { (d, j) -> "$d:$j" }.toSet()
        val fresh = siblings.filter { (d, j) -> "$d:$j" !in already }
        if (fresh.isNotEmpty()) il.ac.tau.lecturesummarizer.slides.SummaryRefreshWorker.adopt(app, id, fresh)
        return already + keys
    }

    /** "<course> · <lecture>" so notifications identify which course finished. */
    private fun named(app: LectureApp, l: il.ac.tau.lecturesummarizer.data.Lecture): String =
        listOfNotNull(app.repo.course(l.courseId)?.name, l.title).joinToString(" · ")

    private fun notify(id: String, title: String, text: String) {
        val ctx = applicationContext
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(ctx, id.hashCode(),
            Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_LECTURE).putExtra(MainActivity.EXTRA_LECTURE_ID, id)
                .setData(android.net.Uri.parse("lecturesummarizer://lecture/$id"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, LectureApp.CHANNEL_JOBS)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build()
        NotificationManagerCompat.from(ctx).notify(id.hashCode(), n)
    }

    companion object {
        private val LIVE_STATES = setOf(LectureState.RECORDING, LectureState.RECORDED, LectureState.UPLOADING)
        /** Between heavy-stage polls; ASR uses a shorter gap so many lectures advance in parallel. */
        private const val POLL_MS = 5_000L
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
