package il.ac.tau.lecturesummarizer.ui

import il.ac.tau.lecturesummarizer.autosync.SyncQueueEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncCountCategoriesTest {
 @Test fun idleViewRetainsOriginalFourCountersInOrder(){assertEquals(listOf("done","skip","wait","notfound"),syncCountCategories(emptyList()))}
 @Test fun activeWorkCategoriesAreNotHidden(){assertEquals(listOf("done","skip","wait","notfound","queued","failed"),syncCountCategories(listOf(row("queued"),row("failed"))))}
 @Test fun dismissedAndDuplicateRowsDoNotCreateExtraCounters(){assertEquals(listOf("done","skip","wait","notfound","queued"),syncCountCategories(listOf(row("queued"),row("queued"),row("dismissed"))))}
 private fun row(category:String)=SyncQueueEntry(category,category,"Course","Lecture",1L)
}
