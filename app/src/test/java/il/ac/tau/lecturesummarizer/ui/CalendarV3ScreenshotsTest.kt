package il.ac.tau.lecturesummarizer.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.calendar.*
import il.ac.tau.lecturesummarizer.data.*
import il.ac.tau.lecturesummarizer.autosync.SyncQueueEntry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[34],qualifiers="he-rIL-w393dp-h852dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalendarV3ScreenshotsTest {
 @get:Rule val compose=createComposeRule()
 private val ctx get()=ApplicationProvider.getApplicationContext<Application>()
 private val rows=(1..5).map{SyncQueueEntry("demo-$it","wait","ביולוגיה $it","הרצאה $it",1794102400000L+it*3600000)}+SyncQueueEntry("demo-queued","queued","ביולוגיה","הרצאה בתור",1794102400000L)
 @Test fun infoIsIndependentAndWrapsItsContent(){
    compose.setContent{AppTheme(darkMode=false){AppScaffold("הגדרות"){pad->Column(Modifier.padding(pad).fillMaxSize()){SyncQueueDisclosure(rows,{},{})}}}}
    compose.onNodeWithContentDescription(ctx.getString(R.string.queue_info_for,ctx.getString(R.string.queue_wait))).performClick()
    compose.onNodeWithTag("sync-info-dialog").assertIsDisplayed()
    assertTrue(compose.onNodeWithTag("sync-info-dialog").fetchSemanticsNode().boundsInRoot.height<600)
    compose.onNodeWithTag("sync-count-sheet").assertDoesNotExist()
    compose.onNodeWithText(ctx.getString(R.string.queue_close)).performClick()
    compose.onNodeWithTag("sync-info-dialog").assertDoesNotExist()
 }
 @Test fun countSheetStartsCompactAndExpands(){
    compose.setContent{CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl){AppTheme(darkMode=false){AppScaffold("הגדרות"){pad->Column(Modifier.padding(pad).fillMaxSize()){SyncQueueDisclosure(rows,{},{})}}}}}
    compose.onNodeWithText(ctx.getString(R.string.sync_waiting,5)).performClick()
    compose.onAllNodes(isRoot()).onLast().captureRoboImage(File(System.getenv("LECTURE_SCREEN_DIR") ?: "build/screens/v3","count-interaction-compact.png").absolutePath)
    compose.onNodeWithText(bidi("ביולוגיה 1")).assertIsDisplayed()
    compose.onNodeWithText(bidi("ביולוגיה 2")).assertDoesNotExist()
    val before=compose.onNodeWithTag("sync-count-sheet").fetchSemanticsNode().boundsInRoot.height
    assertTrue(before>0 && before<600)
    compose.onNodeWithText(ctx.getString(R.string.queue_expand,ltr(5))).performClick()
    compose.onAllNodes(isRoot()).onLast().captureRoboImage(File(System.getenv("LECTURE_SCREEN_DIR") ?: "build/screens/v3","count-interaction-expanded.png").absolutePath)
    compose.onNodeWithText(bidi("ביולוגיה 2")).assertIsDisplayed()
    val after=compose.onNodeWithTag("sync-count-sheet").fetchSemanticsNode().boundsInRoot.height
    assertTrue(after>before)
 }
 @Test fun homeAndSearchAndActualPopups(){
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Jerusalem"))
    val out=File(System.getenv("LECTURE_SCREEN_DIR") ?: "build/screens/v3").apply{mkdirs()}
    val course=Course("demo-science","שיטות מחקר",emoji="🧪",color=0xFF3949AB)
    val other=Course("demo-math","מתמטיקה שימושית",emoji="📐",color=0xFF00796B)
    val ready=Lecture("demo-ready",course.id,"הרצאה 4 · תכנון ניסוי",1794002400000,state=LectureState.DONE)
    val processing=Lecture("demo-working",other.id,"הרצאה 3 · מודלים",1794102400000,state=LectureState.PROCESSING,stage="summary",progress=72)
    val event=CalendarImportEvent("demo-next",1,1,"הרצאה 5 · ניתוח תוצאות","",1799654400000,1799661600000,false,"Asia/Jerusalem")
    val next=CalendarRuleMatch(event,CalendarImportRule("demo-rule","Demo",enabled=true,title="*",targetCourse=course.name,targetCourseId=course.id))
    var screen by mutableIntStateOf(0)
    compose.setContent{CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl){AppTheme(darkMode=false){
      val home=screen<3;val search=screen in 3..6
      Scaffold(bottomBar={if(home || search)MainTabs(if(home)"home"else "search") {}}){outer->
        AppScaffold(stringResource(if(home)R.string.home_title else if(search)R.string.tab_search else R.string.settings),logo=home,actions={if(home)IconButton(onClick={}){Icon(Icons.Default.Settings,stringResource(R.string.settings))}}){pad->
          val padding=Modifier.padding(top=pad.calculateTopPadding(),bottom=outer.calculateBottomPadding())
          when{
            home->HomeContent(listOf(ready,processing),listOf(course,other),setOf(ready.id),if(screen==2)null else next,emptyList(),false,padding,{},{},{},{_,_->},{},calendarStatus=if(screen==0)null else ({CalendarWatchStatus(if(screen==2)emptyList()else listOf(next),true,true,System.currentTimeMillis()-5*60000,false,{},{},compact=true)}))
            search->SearchContent("ניסוי",{},listOf(StudyIndex.Hit(ready,course.name,"ניסוי טוב מתחיל בשאלה ברורה, משתנה מסביר וקבוצת ביקורת.")),screen==4,padding,{},{},refreshPhase=listOf(RefreshPhase.IDLE,RefreshPhase.RUNNING,RefreshPhase.SUCCESS,RefreshPhase.ERROR)[screen-3],lastUpdated=if(screen==3)0L else System.currentTimeMillis()-5*60000,refreshDone=3,refreshTotal=8)
            else->Column(padding.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text(stringResource(R.string.sync_last_run),style=MaterialTheme.typography.titleMedium);SyncQueueDisclosure(rows,{},{})}
          }
        }
      }
    }}}
    listOf("home-baseline","home-connected","home-no-upcoming","search-refresh-idle","search-refresh-running","search-refresh-success","search-refresh-error","sync-counts").forEachIndexed{i,name->
      compose.runOnIdle{screen=i};compose.waitForIdle();compose.onRoot().captureRoboImage(File(out,"$name-he-light.png").absolutePath)
      if(i==1){
        compose.onNodeWithText(ctx.getString(R.string.home_greeting)).assertIsDisplayed()
        compose.onNodeWithText(ctx.getString(R.string.home_processing)).assertIsDisplayed()
        compose.onNodeWithText(ctx.getString(R.string.ob1_cta)).assertDoesNotExist()
      }
      if(i==3)compose.onNodeWithText(ctx.getString(R.string.refresh_action)).assertIsDisplayed()
    }
    for(category in listOf("wait","queued","done","notfound")){
      compose.onNodeWithContentDescription(ctx.getString(R.string.queue_info_for,ctx.getString(syncCategoryLabel(category)))).performClick()
      compose.waitForIdle()
      compose.onNodeWithTag("sync-info-dialog").captureRoboImage(File(out,"info-$category-he-light.png").absolutePath)
      compose.onNodeWithText(ctx.getString(R.string.queue_close)).performClick()
    }
    compose.onNodeWithText(ctx.getString(R.string.sync_waiting,5)).performClick();compose.waitForIdle()
    compose.onAllNodes(isRoot()).onLast().captureRoboImage(File(out,"count-compact-he-light.png").absolutePath)
    compose.onNodeWithText(ctx.getString(R.string.queue_expand,ltr(5))).performClick();compose.waitForIdle()
    compose.onAllNodes(isRoot()).onLast().captureRoboImage(File(out,"count-expanded-he-light.png").absolutePath)
 }
}
