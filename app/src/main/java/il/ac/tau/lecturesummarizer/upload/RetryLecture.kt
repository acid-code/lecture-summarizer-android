package il.ac.tau.lecturesummarizer.upload

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
