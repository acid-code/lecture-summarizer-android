package il.ac.tau.lecturesummarizer.captions

import android.content.Context

/**
 * Keeps the in-lecture [CaptionSession] alive across Compose disposal / Activity recreation
 * (e.g. orientation) while [RecorderService] is still recording that lecture.
 */
object CaptionSessionHolder {
    private val lock = Any()
    private var lectureId: String? = null
    private var session: CaptionSession? = null

    fun obtain(context: Context, id: String): CaptionSession = synchronized(lock) {
        val cur = session
        if (cur != null && lectureId == id) return cur
        cur?.release()
        CaptionSession(context.applicationContext).also {
            session = it
            lectureId = id
        }
    }

    /** Drop the session only when leaving the lecture for real (not mid-recording rotation). */
    fun releaseIfIdle(id: String, stillRecording: Boolean) = synchronized(lock) {
        if (lectureId != id) return
        if (stillRecording) return
        session?.release()
        session = null
        lectureId = null
    }
}
