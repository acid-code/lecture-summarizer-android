package il.ac.tau.lecturesummarizer.autosync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSyncDedupTest {
    @Test fun freshClassMayCreate() = assertTrue(AutoSyncPlanner.mayCreate(null, "d1", listOf(null, "d0")))
    @Test fun waitAndNotFoundMayCreate() {
        assertTrue(AutoSyncPlanner.mayCreate("wait:1", "d1", emptyList()))
        assertTrue(AutoSyncPlanner.mayCreate("notfound:1", "d1", emptyList()))
    }
    @Test fun doneOrClaimedBlocks() {
        assertFalse(AutoSyncPlanner.mayCreate("done:abc", "d1", emptyList()))
        assertFalse(AutoSyncPlanner.mayCreate("claim:1", "d1", emptyList()))
        assertFalse(AutoSyncPlanner.mayCreate("skip", "d1", emptyList()))
    }
    @Test fun samDeliveryInNotebookBlocks() = assertFalse(AutoSyncPlanner.mayCreate(null, "d1", listOf("d1")))
}
