package il.ac.tau.lecturesummarizer.upload

import androidx.work.WorkManager
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.data.*

/** Same operation for the inline error chip and lecture actions. */
suspend fun retryLecture(app: LectureApp, id: String) {
    val l = app.repo.lecture(id) ?: return
    val job = l.jobId
    if (job == null) { UploadWorker.enqueue(app, id); return }
    JobKick.kick(id)
    val status = ApiProvider.get(app.settings).retryJob(job)
    app.repo.updateLecture(id) { it.copy(state = LectureState.PROCESSING, progress = status.progress,
        stage = status.stage.takeUnless { stage -> stage == "done" || stage == "failed" }, stageDetail = null, error = null) }
    JobPollWorker.enqueue(app, id)
}

/**
 * Safe unstick while still PROCESSING: force-retry on the server (clears dead leases / holds, keeps finished ASR),
 * then restart background polling. Does not wipe transcript or re-upload audio.
 */
suspend fun forceRestartLecture(app: LectureApp, id: String) {
    val l = app.repo.lecture(id) ?: return
    val job = l.jobId ?: return
    JobKick.kick(id)
    val status = ApiProvider.get(app.settings).forceRestartJob(job)
    app.repo.updateLecture(id) {
        it.copy(
            state = if (status.state == "done") LectureState.DONE else if (status.state == "failed") LectureState.FAILED else LectureState.PROCESSING,
            progress = status.progress,
            stage = status.stage.takeUnless { s -> s == "done" || s == "failed" || isTerminalStageText(s) },
            stageDetail = status.detail,
            error = null,
        )
    }
    if (status.state != "done" && status.state != "failed") JobPollWorker.enqueue(app, id, replace = true)
}

/** Stop a running job on the server and cancel local poll/upload workers for this lecture. */
suspend fun stopLectureJob(app: LectureApp, id: String) {
    val l = app.repo.lecture(id) ?: return
    val job = l.jobId
    WorkManager.getInstance(app).cancelUniqueWork("poll-$id")
    if (job != null) runCatching { ApiProvider.get(app.settings).cancelJob(job) }
    app.repo.updateLecture(id) {
        it.copy(state = LectureState.FAILED, stage = "failed", stageDetail = null, error = app.getString(il.ac.tau.lecturesummarizer.R.string.job_cancelled))
    }
}
