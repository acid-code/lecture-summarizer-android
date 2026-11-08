package il.ac.tau.lecturesummarizer.ui

import android.app.Application
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.autosync.SyncQueueEntry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[34],qualifiers="he-rIL-w393dp-h852dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalendarV4ScreenshotsTest {
 @get:Rule val compose=createComposeRule()
 private val ctx get()=ApplicationProvider.getApplicationContext<Application>()
 private val out get()=File(System.getenv("LECTURE_SCREEN_DIR") ?: "build/screens/v4").apply{mkdirs()}
 private val rows=listOf("done" to 3,"skip" to 2,"wait" to 5,"notfound" to 1).flatMap{(c,n)->(1..n).map{SyncQueueEntry("$c-$it",c,"ביולוגיה", "הרצאה $it",1794102400000L+it*3600000)}}

 @Test fun restoredCountersMatchHistoricalPixelsAndCompactPopupsStillOpen(){
    var mode by mutableIntStateOf(0)
    compose.setContent{Frame(mode,rows)}
    compose.onRoot().captureRoboImage(File(out,"settings-before-c1aac2d-he-light.png").absolutePath)
    compose.runOnIdle{mode=1}
    compose.onRoot().captureRoboImage(File(out,"settings-reference-7dca316-he-light.png").absolutePath)
    compose.onNodeWithTag("reference-count-items").captureRoboImage(File(out,"counts-reference-7dca316-he-light.png").absolutePath)
    compose.runOnIdle{mode=2}
    compose.onRoot().captureRoboImage(File(out,"settings-after-he-light.png").absolutePath)
    compose.onNodeWithTag("sync-count-items").captureRoboImage(File(out,"counts-after-he-light.png").absolutePath)
    val old=BitmapFactory.decodeFile(File(out,"counts-reference-7dca316-he-light.png").absolutePath)
    val now=BitmapFactory.decodeFile(File(out,"counts-after-he-light.png").absolutePath)
    assertEquals(old.width,now.width);assertEquals(old.height,now.height)
    val expected=IntArray(old.width*old.height);val actual=IntArray(now.width*now.height)
    old.getPixels(expected,0,old.width,0,0,old.width,old.height);now.getPixels(actual,0,now.width,0,0,now.width,now.height)
    assertArrayEquals("Legacy count appearance must not change when actions are added",expected,actual)
    assertTrue(compose.onNodeWithText(ctx.getString(R.string.sync_imported,3)).fetchSemanticsNode().boundsInRoot.left<100f)
    // Actual touch targets, not only semantics callbacks: the old info glyph and count remain independent.
    compose.onNodeWithContentDescription(ctx.getString(R.string.queue_info_for,ctx.getString(R.string.queue_wait))).performTouchInput{click(center)}
    compose.onNodeWithTag("sync-info-dialog").assertIsDisplayed()
    compose.onNodeWithTag("sync-count-sheet").assertDoesNotExist()
    compose.onNodeWithTag("sync-info-dialog").captureRoboImage(File(out,"settings-info-popup-he-light.png").absolutePath)
    compose.onNodeWithText(ctx.getString(R.string.queue_close)).performClick()
    compose.onNodeWithText(ctx.getString(R.string.sync_waiting,5)).performTouchInput{click(center)}
    compose.onNodeWithTag("sync-count-sheet").assertIsDisplayed()
    assertTrue(compose.onNodeWithTag("sync-count-sheet").fetchSemanticsNode().boundsInRoot.height<600)
    compose.onNodeWithText(bidi("ביולוגיה")).assertIsDisplayed()
    compose.onAllNodes(isRoot()).onLast().captureRoboImage(File(out,"settings-count-compact-he-light.png").absolutePath)
    compose.onNodeWithText(ctx.getString(R.string.queue_expand,ltr(5))).performClick()
    compose.waitForIdle()
    compose.mainClock.advanceTimeBy(500)
    compose.waitForIdle()
    compose.onNodeWithText(bidi("הרצאה 2")).assertIsDisplayed()
    compose.onAllNodes(isRoot()).onLast().captureRoboImage(File(out,"settings-count-expanded-he-light.png").absolutePath)
    compose.onNodeWithText(bidi("הרצאה 2")).assertIsDisplayed()
 }

 @Test fun extraWorkStatesKeepCompactStyleAndOpenTheirInfo(){
    val active=rows+SyncQueueEntry("queued","queued","ביולוגיה","בתור",1794102400000L)+SyncQueueEntry("failed","failed","ביולוגיה","נכשל",1794102400000L)
    compose.setContent{Frame(2,active)}
    compose.onRoot().captureRoboImage(File(out,"settings-active-work-he-light.png").absolutePath)
    compose.onNodeWithText(ctx.getString(R.string.queue_count,ctx.getString(R.string.queue_queued),ltr(1))).assertIsDisplayed()
    compose.onNodeWithContentDescription(ctx.getString(R.string.queue_info_for,ctx.getString(R.string.queue_queued))).performTouchInput{click(center)}
    compose.onNodeWithTag("sync-info-dialog").assertIsDisplayed()
 }

 @OptIn(ExperimentalLayoutApi::class)
 @Composable private fun Frame(mode:Int,rows:List<SyncQueueEntry>){
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl){AppTheme(darkMode=false){
      AppScaffold(stringResource(R.string.settings),onBack={}){pad->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
          Row(verticalAlignment=Alignment.CenterVertically){Text(stringResource(R.string.copy_automatic_sync),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold);ProBadge()}
          SyncCard(stringResource(R.string.sync_last_run)){
            Row(verticalAlignment=Alignment.CenterVertically){
              Text(stringResource(R.string.copy_queue),Modifier.padding(end=8.dp),style=MaterialTheme.typography.bodyMedium)
              if(mode==0)StatusChip(stringResource(R.string.sync_active),MaterialTheme.colorScheme.primary,icon=Icons.Default.Sync)
              else StatusChip(stringResource(R.string.sync_active),MaterialTheme.colorScheme.primary)
            }
            when(mode){0->BeforeCountChips(rows,onOpen={});1->HistoricalCounts(rows);else->SyncQueueDisclosure(rows,{},{})}
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
              Button(onClick={}){Text(stringResource(R.string.copy_run_now))};OutlinedButton(onClick={}){Text(stringResource(R.string.copy_pause))}
            }
          }
        }
      }
    }}
 }
}

/** Reconstructed from 7dca316 AutoSyncSettings.kt: original order, strings, StatusChip and 8dp gaps.
 * Physical left placement follows Asaf's explicit request; Hebrew chip contents remain RTL. */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun HistoricalCounts(rows:List<SyncQueueEntry>){
  CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr){
    FlowRow(Modifier.fillMaxWidth().testTag("reference-count-items"),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
      CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl){
        StatusChip(stringResource(R.string.sync_imported,rows.count{it.category=="done"}))
        StatusChip(stringResource(R.string.sync_existing,rows.count{it.category=="skip"}))
        StatusChip(stringResource(R.string.sync_waiting,rows.count{it.category=="wait"}))
        val missing=rows.count{it.category=="notfound"}
        StatusChip(stringResource(R.string.sync_missing,missing),if(missing>0)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

// Exact pre-change c1aac2d count implementation, used only for the labelled before screenshot.
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun BeforeCountChips(rows:List<SyncQueueEntry>,onInfo:((String)->Unit)?=null,onOpen:(String)->Unit){
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        listOf("wait","queued","done","skip","notfound","failed").forEach{category->
            val title=stringResource(syncCategoryLabel(category))
            Surface(shape=MaterialTheme.shapes.small,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outline),color=MaterialTheme.colorScheme.surface){
                Row(verticalAlignment=Alignment.CenterVertically){
                    TextButton(onClick={onOpen(category)},contentPadding=PaddingValues(start=12.dp,end=8.dp,top=4.dp,bottom=4.dp)){
                        Icon(beforeIcon(category),null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.queue_count,title,ltr(rows.count{it.category==category})))
                    }
                    VerticalDivider(Modifier.height(24.dp))
                    IconButton(onClick={(onInfo ?: onOpen)(category)}){
                        Icon(Icons.Default.Info,stringResource(R.string.queue_info_for,title),Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}


private fun beforeIcon(category:String)=when(category){"wait"->Icons.Default.Schedule;"queued"->Icons.Default.CloudDownload;"done"->Icons.Default.CheckCircle;"notfound"->Icons.Default.SearchOff;"failed"->Icons.Default.ErrorOutline;else->Icons.Default.LibraryBooks}
