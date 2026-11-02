package il.ac.tau.lecturesummarizer.upload

import java.util.concurrent.ConcurrentHashMap

/** Remembers when a (re)started job was kicked so pollers ignore the stale "done" of the previous run. */
object JobKick {
    private val at = ConcurrentHashMap<String, Long>()
    private val sawActive = ConcurrentHashMap.newKeySet<String>()
    fun kick(lectureId: String) { at[lectureId] = System.currentTimeMillis(); sawActive.remove(lectureId) }
    /** True if a terminal "done"/"failed" reported now is likely the previous run's (no active state seen yet, within grace). */
    fun staleDone(lectureId: String, state: String): Boolean {
        if (state != "done" && state != "failed") { if (state == "running" || state == "queued") sawActive.add(lectureId); return false }
        val t = at[lectureId] ?: return false
        if (lectureId in sawActive) return false
        return System.currentTimeMillis() - t < 90_000
    }
}

/** Stage/detail text that belongs to a finished run (Done/Failed) and must not show next to an active job. */
fun isTerminalStageText(t: String?): Boolean = t?.trim()?.lowercase()?.let { it == "done" || it == "failed" || it.startsWith("done ") || it.startsWith("failed") || it.startsWith("✓") || it.startsWith("✗") } ?: false
