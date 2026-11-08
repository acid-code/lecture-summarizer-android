package il.ac.tau.lecturesummarizer.moodle

import androidx.core.content.FileProvider
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.data.allSlides
import il.ac.tau.lecturesummarizer.slides.SlideChoices

/**
 * High-confidence Moodle deck attach (same rule as AutoSync): [MoodleMatch.autoPick] only.
 * Ambiguous / no unique stem → false (caller leaves for suggestions or skips).
 */
object MoodleDeckAttach {
    /** @return true when a deck was attached to a previously empty lecture. */
    fun tryAttach(
        app: LectureApp,
        lectureId: String,
        client: MoodleClient,
        store: MoodleStore,
        course: MoodleCourse,
        recordingTitle: String?,
        dayMonth: Pair<Int, Int>,
    ): Boolean {
        if (course.id <= 0) return false
        val choices = SlideChoices(app)
        if (choices.isManual(lectureId) || !app.repo.lecture(lectureId)?.allSlides.isNullOrEmpty()) return false
        val site = store.lectureBase(lectureId)
        val files = client.sections(course.id).flatMap { it.files }.filter { !store.isUsed(it.cmid) }
            .filter { f -> MoodleMatch.matchesRecording(f.name, recordingTitle) || MoodleMatch.suggested(f.name, dayMonth) }
            .take(6).map { f -> f to (client.fileName(f) ?: f.name) }
        val pick = MoodleMatch.autoPick(files, recordingTitle, dayMonth) ?: return false
        if (choices.isManual(lectureId) || !app.repo.lecture(lectureId)?.allSlides.isNullOrEmpty()) return false
        val file = store.cached(pick.cmid, site) ?: client.download(pick, store.cacheDir(site)).also { store.putCached(pick.cmid, it, site) }
        store.markUsed(pick.cmid)
        choices.auto(lectureId, file.name, listOf("same_course", "lecture_number"))
        choices.phase(lectureId, "done")
        val uri = FileProvider.getUriForFile(app, app.packageName + ".files", file)
        app.repo.updateLecture(lectureId) { x ->
            if (x.slidesUri == null) x.copy(slidesUri = uri.toString())
            else x.copy(extraSlides = (x.extraSlides + uri.toString()).distinct())
        }
        return true
    }
}
