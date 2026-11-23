package il.ac.tau.lecturesummarizer.moodle

import androidx.core.content.FileProvider
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.calendar.ClassKind
import il.ac.tau.lecturesummarizer.data.allSlides
import il.ac.tau.lecturesummarizer.slides.SlideChoices

/**
 * High-confidence Moodle deck attach (same rule as AutoSync): [MoodleMatch.autoPick] first,
 * then reuse of the previous same-kind lecture's last deck only when no Moodle file matched
 * (Genetics / Biochem continuation when this week has no new upload).
 */
object MoodleDeckAttach {
    /** @return true when at least one deck was attached to a previously empty (or auto-only) lecture. */
    fun tryAttach(
        app: LectureApp,
        lectureId: String,
        client: MoodleClient,
        store: MoodleStore,
        course: MoodleCourse,
        recordingTitle: String?,
        dayMonth: Pair<Int, Int>,
    ): Boolean {
        val choices = SlideChoices(app)
        if (choices.isManual(lectureId)) return false
        val lecture = app.repo.lecture(lectureId) ?: return false
        // Never clobber a user-edited set; allow append only while still empty or previously auto.
        if (lecture.allSlides.isNotEmpty() && !choices.isAuto(lectureId)) return false
        val site = store.lectureBase(lectureId)
        val preferKind = sequenceOf(lecture.title, recordingTitle.orEmpty())
            .map(MoodleMatch::kindFromTitle)
            .firstOrNull { it != ClassKind.UNKNOWN }
            ?: ClassKind.UNKNOWN
        var attached = false
        val reasons = mutableListOf<String>()
        val names = mutableListOf<String>()

        // 1) Moodle pick: number/date first, then shared title words, then sole kind-compatible deck.
        if (course.id > 0) {
            val files = client.sections(course.id).flatMap { it.files }.filter { !store.isUsed(it.cmid) }
                .filter { f -> MoodleMatch.kindCompatible(f.name, preferKind) }
                .take(40).map { f -> f to (client.fileName(f) ?: f.name) }
            val pick = MoodleMatch.autoPick(files, recordingTitle, dayMonth, preferKind)
            if (pick != null && !choices.isManual(lectureId)) {
                val current = app.repo.lecture(lectureId)?.allSlides.orEmpty()
                val file = store.cached(pick.cmid, site) ?: client.download(pick, store.cacheDir(site)).also { store.putCached(pick.cmid, it, site) }
                val uri = FileProvider.getUriForFile(app, app.packageName + ".files", file).toString()
                if (uri !in current) {
                    store.markUsed(pick.cmid)
                    app.repo.updateLecture(lectureId) { x ->
                        if (x.slidesUri == null) x.copy(slidesUri = uri)
                        else x.copy(extraSlides = (x.extraSlides + uri).distinct())
                    }
                    attached = true
                    reasons += "lecture_number"
                    names += file.name
                }
            }
        }

        // 2) Previous same-kind deck only when Moodle had no unique file for this class
        //    (continuation weeks). Never carry a recitation onto a lecture, etc.
        if (!attached) {
            val previous = app.repo.lectures.value
                .filter {
                    it.courseId == lecture.courseId &&
                        it.id != lectureId &&
                        it.recordedAtMs < lecture.recordedAtMs &&
                        sameKind(MoodleMatch.kindFromTitle(it.title), preferKind)
                }
                .maxByOrNull { it.recordedAtMs }
            val carry = previous?.allSlides?.lastOrNull()?.takeIf { uri ->
                MoodleMatch.kindCompatible(il.ac.tau.lecturesummarizer.ui.displayName(app, uri), preferKind)
            }
            if (carry != null && carry !in (app.repo.lecture(lectureId)?.allSlides.orEmpty())) {
                app.repo.updateLecture(lectureId) { x ->
                    if (x.slidesUri == null) x.copy(slidesUri = carry)
                    else x.copy(extraSlides = (x.extraSlides + carry).distinct())
                }
                attached = true
                reasons += "previous_lecture"
                names += il.ac.tau.lecturesummarizer.ui.displayName(app, carry)
            }
        }

        if (attached) {
            choices.auto(lectureId, names.firstOrNull().orEmpty(), (listOf("same_course") + reasons).distinct())
            // Keep carryover open for post-DONE transcript verification in SlideMatchWorker.
            val onlyCarryover = reasons.size == 1 && reasons.first() == "previous_lecture"
            choices.phase(lectureId, if (onlyCarryover) "carryover" else "done")
        }
        return attached
    }

    private fun sameKind(a: ClassKind, b: ClassKind) =
        a == b || a == ClassKind.UNKNOWN || b == ClassKind.UNKNOWN
}
