package il.ac.tau.lecturesummarizer.upload

import androidx.work.WorkManager
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.data.*

/** Same operation for the inline error chip and lecture actions. */
suspend fun retryLecture(app: LectureApp, id: String, auto: Boolean = false) {
    val l = app.repo.lecture(id) ?: return
    val job = l.jobId
    if (job == null) { UploadWorker.enqueue(app, id); return }
    val recovery = JobAutoRecovery(app)
    if (auto) {
        // attempt already bumped by caller when scheduling; keep counter
    } else {
        recovery.clear(id)
        JobAutoRecoveryWorker.cancelLecture(app, id)
    }
    JobKick.kick(id)
    val status = ApiProvider.get(app.settings).retryJob(job)
    app.repo.updateLecture(id) { it.copy(state = LectureState.PROCESSING, progress = status.progress,
        stage = status.stage.takeUnless { stage -> stage == "done" || stage == "failed" }, stageDetail = null, error = null) }
    recovery.noteProgress(id, JobAutoRecovery.fingerprint(status.stage, status.progress, status.detail, null))
    JobPollWorker.enqueue(app, id)
}

/**
 * Safe unstick while still PROCESSING: force-retry on the server (clears dead leases / holds, keeps finished ASR),
 * then restart background polling. Does not wipe transcript or re-upload audio.
 *
 * Also unsticks multi-density siblings: primary [jobId] restart alone often no-ops when detailed is already
 * done and compact/standard derive jobs were orphaned (common for pre-density → detailed + infer).
 */
suspend fun forceRestartLecture(app: LectureApp, id: String, auto: Boolean = false) {
    val l = app.repo.lecture(id) ?: return
    val job = l.jobId ?: return
    val recovery = JobAutoRecovery(app)
    if (!auto) {
        recovery.clear(id)
        JobAutoRecoveryWorker.cancelLecture(app, id)
    }
    JobKick.kick(id)
    val api = ApiProvider.get(app.settings)
    val status = api.forceRestartJob(job)
    app.repo.updateLecture(id) {
        it.copy(
            state = if (status.state == "done") LectureState.DONE else if (status.state == "failed") LectureState.FAILED else LectureState.PROCESSING,
            progress = status.progress,
            stage = status.stage.takeUnless { s -> s == "done" || s == "failed" || isTerminalStageText(s) },
            stageDetail = status.detail,
            error = null,
        )
    }
    if (status.state == "failed") {
        recovery.recordFail(id)
        if (recovery.attempts(id) < JobAutoRecovery.MAX_ATTEMPTS && !JobAutoRecovery.isDenied(status.error)) {
            JobAutoRecoveryWorker.scheduleAfterFail(app, id)
        }
        return
    }
    recovery.noteProgress(id, JobAutoRecovery.fingerprint(status.stage, status.progress, status.detail, null))
    l.remoteId?.let { remote -> unstickDensityJobs(app, id, remote, job, api) }
    // Always re-poll (or re-download PDF) — primary may already be done while density siblings / PDF are missing.
    JobPollWorker.enqueue(app, id, replace = true)
}

/** Advance orphan density jobs and (re)adopt them into [SummaryRefreshWorker]. */
private suspend fun unstickDensityJobs(
    app: LectureApp,
    id: String,
    remote: String,
    mainJobId: String,
    api: LectureApi,
) {
    runCatching { api.advanceAllJobs(remote) }
    val states = runCatching { api.summaryJobStates(remote) }.getOrNull().orEmpty()
    val updating = states.filter { (_, s) -> s.state == "updating" && s.jobId.isNotBlank() && s.jobId != mainJobId }
        .mapValues { it.value.jobId }
    if (updating.isNotEmpty()) {
        il.ac.tau.lecturesummarizer.slides.SummaryRefreshWorker.adopt(app, id, updating)
    }
    val ready = states.filter { it.value.state == "ready" }.keys
    val want = app.settings.summaryDensities.intersect(setOf("compact", "standard", "detailed"))
    want.filter { it !in ready && it !in updating.keys }.forEach { density ->
        il.ac.tau.lecturesummarizer.slides.SummaryRefreshWorker.enqueueDensity(app, id, density, force = true)
    }
}

/** Stop a running job on the server and cancel local poll/upload workers for this lecture. */
suspend fun stopLectureJob(app: LectureApp, id: String) {
    val l = app.repo.lecture(id) ?: return
    val job = l.jobId
    WorkManager.getInstance(app).cancelUniqueWork("poll-$id")
    il.ac.tau.lecturesummarizer.slides.SummaryRefreshWorker.cancelAll(app, id)
    JobAutoRecoveryWorker.cancelLecture(app, id)
    JobAutoRecovery(app).clear(id)
    if (job != null) runCatching { ApiProvider.get(app.settings).cancelJob(job) }
    app.repo.updateLecture(id) {
        it.copy(state = LectureState.FAILED, stage = "failed", stageDetail = null, error = app.getString(il.ac.tau.lecturesummarizer.R.string.job_cancelled))
    }
}
