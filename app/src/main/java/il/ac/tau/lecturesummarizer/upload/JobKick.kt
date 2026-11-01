package il.ac.tau.lecturesummarizer.upload

import java.util.concurrent.ConcurrentHashMap

/** Remembers when a (re)started job was kicked so pollers ignore the stale "done" of the previous run. */
object JobKick {
    private val at = ConcurrentHashMap<String, Long>()
    private val sawActive = ConcurrentHashMap.newKeySet<String>()
    fun kick(lectureId: String) { at[lectureId] = System.currentTimeMillis(); sawActive.remove(lectureId) }
    /** True if a "done" reported now is likely the previous run's (no active state seen yet, within grace). */
    fun staleDone(lectureId: String, state: String): Boolean {
        if (state != "done") { if (state == "running" || state == "queued") sawActive.add(lectureId); return false }
        val t = at[lectureId] ?: return false
        if (lectureId in sawActive) return false
        return System.currentTimeMillis() - t < 90_000
    }
}
