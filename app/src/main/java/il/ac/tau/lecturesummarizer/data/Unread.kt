package il.ac.tau.lecturesummarizer.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lectures whose summary finished (new or re-summarized) and that the user hasn't opened since.
 * Drives the red count badge per course and the red dot per lecture; cleared when the lecture is opened.
 */
class UnreadStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("unread", Context.MODE_PRIVATE)
    private val _ids = MutableStateFlow(p.getStringSet("ids", emptySet())!!.toSet())
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()
    /** Lecture currently on screen: a summary finishing while it's open counts as seen. */
    @Volatile var openLectureId: String? = null

    @Synchronized fun markUnread(id: String) { if (id == openLectureId || id in _ids.value) return; set(_ids.value + id) }
    @Synchronized fun markSeen(id: String) { if (id in _ids.value) set(_ids.value - id) }
    @Synchronized fun retain(existing: Set<String>) { if (!existing.containsAll(_ids.value)) set(_ids.value intersect existing) }
    private fun set(v: Set<String>) { _ids.value = v; p.edit().putStringSet("ids", v).apply() }

    companion object {
        /** A summary became ready: entered DONE with a PDF, or the PDF changed (re-summarize / other density). */
        fun justFinished(old: Lecture, new: Lecture): Boolean =
            new.state == LectureState.DONE && new.pdfPath != null && (old.state != LectureState.DONE || old.pdfPath != new.pdfPath)
    }
}
