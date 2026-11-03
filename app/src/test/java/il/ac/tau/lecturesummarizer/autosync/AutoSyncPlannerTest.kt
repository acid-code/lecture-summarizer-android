package il.ac.tau.lecturesummarizer.autosync

import il.ac.tau.lecturesummarizer.calendar.ClassKind
import il.ac.tau.lecturesummarizer.calendar.TauClass
import org.junit.Assert.*
import org.junit.Test

class AutoSyncPlannerTest {
    private val day = 86_400_000L
    private val now = 1_000 * day
    private fun c(daysAgo: Int) = TauClass("Algo", ClassKind.LECTURE, "03681234", null, null, now - daysAgo * day, now - daysAgo * day + 3_600_000, true)

    @Test fun newestFirstAndSkipsExisting() {
        val cls = listOf(c(40), c(2), c(20), c(9))
        val p = AutoSyncPlanner.plan(cls, now, retro = true, live = true, status = { null }, alreadyHave = { it == cls[2] })
        assertEquals(listOf(2, 9, 40), p.map { ((now - it.cls.beginMs) / day).toInt() })
        assertTrue(p[0].live); assertFalse(p[2].live)
    }
    @Test fun liveOnlyKeepsWindow() {
        val p = AutoSyncPlanner.plan(listOf(c(40), c(1)), now, retro = false, live = true, status = { null }, alreadyHave = { false })
        assertEquals(1, p.size)
    }
    @Test fun finalStatusesDropped() {
        val a = c(3); val b = c(5); val d = c(30)
        val st = mapOf(AutoSyncStore.key(a) to "done:x", AutoSyncStore.key(b) to "wait", AutoSyncStore.key(d) to "wait")
        val p = AutoSyncPlanner.plan(listOf(a, b, d), now, retro = true, live = true, status = { st[it] }, alreadyHave = { false })
        assertEquals(listOf(b), p.map { it.cls })
    }
    @Test fun futureIgnored() {
        assertTrue(AutoSyncPlanner.plan(listOf(c(0).copy(endMs = now + 1)), now, true, true, { null }, { false }).isEmpty())
    }
    @Test fun pendingWatchPolledDaily() {
        val a = c(6); val b = c(7); val n = c(5)
        val st = mapOf(AutoSyncStore.key(a) to "wait:${now - 2 * 3_600_000}", AutoSyncStore.key(b) to "wait:${now - day}")
        val p = AutoSyncPlanner.plan(listOf(a, b, n), now, retro = false, live = true, status = { st[it] }, alreadyHave = { false })
        assertEquals(listOf(b), p.map { it.cls })   // a polled 2h ago, n older than live window and never enrolled
    }
}
