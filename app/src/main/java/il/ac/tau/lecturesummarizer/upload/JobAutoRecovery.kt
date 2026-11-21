package il.ac.tau.lecturesummarizer.upload

import android.content.Context
import il.ac.tau.lecturesummarizer.data.Lecture
import il.ac.tau.lecturesummarizer.data.LectureState

/**
 * Client-side recovery for stuck PROCESSING and Failed·Retry lectures.
 * Hobby only allows a daily server cron, so the phone drives short auto-retry loops.
 *
 * Failed: if the user does not tap Try Again within [FAILED_IDLE_MS], retry (max [MAX_ATTEMPTS]).
 * Stuck: if stage/progress do not change for [STUCK_MS], force-restart (same attempt cap).
 * Manual retry clears the streak so the next failure can get another full loop.
 */
class JobAutoRecovery(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("job_auto_recovery", Context.MODE_PRIVATE)

    fun attempts(id: String): Int = p.getInt("attempts:$id", 0)

    fun clear(id: String) {
        p.edit()
            .remove("attempts:$id")
            .remove("failedAt:$id")
            .remove("fp:$id")
            .remove("fpAt:$id")
            .apply()
    }

    fun recordFail(id: String) {
        if (!p.contains("failedAt:$id")) {
            p.edit().putLong("failedAt:$id", System.currentTimeMillis()).apply()
        }
    }

    fun failedAt(id: String): Long = p.getLong("failedAt:$id", 0L)

    /** Call on every observed non-terminal job status so stuck detection tracks real progress. */
    fun noteProgress(id: String, fingerprint: String) {
        val prev = p.getString("fp:$id", null)
        if (prev == fingerprint) return
        p.edit().putString("fp:$id", fingerprint).putLong("fpAt:$id", System.currentTimeMillis()).apply()
    }

    fun progressAt(id: String): Long = p.getLong("fpAt:$id", 0L)

    fun bump(id: String, reason: String) {
        p.edit()
            .putInt("attempts:$id", attempts(id) + 1)
            .putLong("lastAutoAt:$id", System.currentTimeMillis())
            .putString("lastReason:$id", reason)
            .remove("failedAt:$id")
            .remove("fp:$id")
            .remove("fpAt:$id")
            .apply()
    }

    fun shouldAutoRetryFailed(l: Lecture, now: Long = System.currentTimeMillis()): Boolean {
        if (l.state != LectureState.FAILED || l.jobId.isNullOrBlank()) return false
        if (isDenied(l.error)) return false
        if (attempts(l.id) >= MAX_ATTEMPTS) return false
        val since = failedAt(l.id).takeIf { it > 0 } ?: return false
        return now - since >= FAILED_IDLE_MS
    }

    fun shouldUnstick(l: Lecture, now: Long = System.currentTimeMillis()): Boolean {
        if (l.state != LectureState.PROCESSING || l.jobId.isNullOrBlank() || l.liveJob) return false
        if (attempts(l.id) >= MAX_ATTEMPTS) return false
        val since = progressAt(l.id).takeIf { it > 0 } ?: return false
        return now - since >= STUCK_MS
    }

    companion object {
        /** Wait after Failed before auto Try Again (user can still tap sooner). */
        const val FAILED_IDLE_MS = 7 * 60_000L
        /** No stage/progress change → treat as stuck and force-restart. */
        const val STUCK_MS = 10 * 60_000L
        const val MAX_ATTEMPTS = 5

        fun fingerprint(stage: String?, progress: Int, detail: String?, error: String?): String =
            listOf(stage ?: "", progress.toString(), detail ?: "", error ?: "").joinToString("|")

        fun isDenied(error: String?): Boolean {
            if (error.isNullOrBlank()) return false
            if (error == "cancelled_by_user") return true
            if (error.startsWith("superseded:")) return true
            return false
        }
    }
}
