package il.ac.tau.lecturesummarizer.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import com.github.takahirom.roborazzi.captureRoboImage
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.calendar.*
import il.ac.tau.lecturesummarizer.autosync.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.TimeZone

/** Production components, Hebrew/light, synthetic courses only; no private calendar or network access. */
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[34],qualifiers="he-rIL-w393dp-h852dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalendarV2ScreenshotsTest {
 @get:Rule val compose=createComposeRule()
 @Test fun bulkActionsAndCounterAreWired(){
    val events=(1..40).map{CalendarImportEvent("event-$it",1,it.toLong(),"שיעור $it","",1000,2000,false,"Asia/Jerusalem")}
    var selected by mutableStateOf(emptySet<String>())
    compose.setContent{AppTheme(darkMode=false){CalendarEventPickerContent(events,selected,"",{},{selected=it},modifier=Modifier.fillMaxSize())}}
    compose.onNodeWithText("בחירת הכול").performClick();compose.runOnIdle{assertEquals(40,selected.size)}
    compose.onNodeWithText("ביטול כל הבחירות").performClick();compose.runOnIdle{assertEquals(0,selected.size)}
 }
 @Test fun syncInfoButtonOpensCorrectCategory(){
    var opened=""
    compose.setContent{AppTheme(darkMode=false){SyncCountChips(emptyList()){opened=it}}}
    compose.onNodeWithText("ממתינים להקלטה · ⁦0⁩").performClick()
    compose.runOnIdle{assertEquals("wait",opened)}
 }
 @Test fun newFlows(){
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Jerusalem"))
    val out=File(System.getenv("LECTURE_SCREEN_DIR") ?: "build/screens/v2").apply{mkdirs()}
    val now=System.currentTimeMillis()
    val events=listOf("ביולוגיה · הרצאה","כימיה · הרצאה","ביולוגיה · תרגול","סדנת כתיבה","פיזיקה · הרצאה").mapIndexed{i,title->CalendarImportEvent("demo-$i",1,i.toLong(),title,"",now+(i+1)*86400_000L,now+(i+1)*86400_000L+3600000,false,"Asia/Jerusalem")}
    val matches=events.take(2).map{CalendarRuleMatch(it,CalendarImportRule(it.key,it.title,true,it.title,targetCourse=it.title.substringBefore(" · ")))}
    val rows=events.map{SyncQueueEntry(it.key,"wait",it.title.substringBefore(" · "),it.title,it.start-14*86400_000L)}
    var screen by mutableIntStateOf(0)
    compose.setContent{CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl){AppTheme(darkMode=false){
      AppScaffold(stringResource(when(screen){0->R.string.sources_course_mapping;1->R.string.calendar_import_title;6->R.string.home_title;else->R.string.settings})){pad->
        when(screen){
          0->Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("ביולוגיה",style=MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.sources_explanation),style=MaterialTheme.typography.bodyMedium)
            CourseSourcesOverview("2 כללים פעילים",true,"ביולוגיה · קורס 1024",true,"תיקיית ההרצאות לסמסטר א׳",true,{},{},{},{})
            Text("עדכון קורס: לפני 5 דקות",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
          }
          1->Column(Modifier.padding(pad).padding(20.dp)){
            CalendarEventPickerContent(events,setOf(events[0].key,events[2].key),"",{},{},modifier=Modifier.weight(1f))
            Button(onClick={},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.calendar_import_event_count,2))}
          }
          in 2..5->Column(Modifier.padding(pad).padding(20.dp)){
            SearchIndexRefreshCard(listOf(RefreshPhase.IDLE,RefreshPhase.RUNNING,RefreshPhase.SUCCESS,RefreshPhase.ERROR)[screen-2],if(screen==2)0 else now-5*60000,3,8){}
          }
          6->Column(Modifier.padding(pad).padding(20.dp)){CalendarWatchStatus(matches,true,true,now-5*60000,false,{},{})}
          7->Column(Modifier.padding(pad).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text(stringResource(R.string.sync_last_run),style=MaterialTheme.typography.titleMedium);SyncCountChips(rows){};Text(stringResource(R.string.queue_wait_help))}
          12->Column(Modifier.padding(pad).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            Text("ביולוגיה",style=MaterialTheme.typography.titleLarge)
            CalendarCourseRuleRows(listOf(CalendarImportRule("lecture","הרצאות",true,"ביולוגיה · הרצאה",targetCourse="ביולוגיה"),CalendarImportRule("lab","תרגולים",false,"ביולוגיה · תרגול",targetCourse="ביולוגיה")),{_,_->},{})
          }
          10,11->Column(Modifier.padding(pad).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            if(screen==10)MoodleSourceFields("https://moodle.example.org","1024",{},{})
            else PanoptoSourceFields("https://university.hosted.panopto.com","https://university.hosted.panopto.com/Panopto/Pages/Sessions/List.aspx?folderID=example","",{},{},{})
            Button(onClick={},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.save))}
            TextButton(onClick={}){Text(stringResource(R.string.cancel))}
          }
          else->SyncQueueList(if(screen==8)"wait" else "notfound",rows.take(3).map{it.copy(category=if(screen==8)"wait" else "notfound")},{},{},Modifier.padding(pad))
        }
      }
    }}}
    listOf("course-sources","event-picker","refresh-idle","refresh-running","refresh-success","refresh-error","home-connected","sync-counts","sync-waiting","sync-not-found","moodle-edit","panopto-edit","calendar-course-edit").forEachIndexed{i,name->
       compose.runOnIdle{screen=i};compose.waitForIdle();compose.onRoot().captureRoboImage(File(out,"$name-he-light.png").absolutePath)
    }
 }
}
