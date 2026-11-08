package il.ac.tau.lecturesummarizer.ui

import android.app.Application
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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.data.*
import il.ac.tau.lecturesummarizer.calendar.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.TimeZone

/** Real production Compose components, deterministic DEMO data; no account, mailbox or server access. */
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PolishScreenshotsTest{
 @get:Rule val compose=createComposeRule()
 @Test @Config(qualifiers="he-rIL-w393dp-h852dp-notnight-mdpi") fun hebrewLight()=render("he",false)
 @Test @Config(qualifiers="he-rIL-w393dp-h852dp-night-mdpi") fun hebrewDark()=render("he",true)
 @Test @Config(qualifiers="en-rUS-w393dp-h852dp-notnight-mdpi") fun englishLight()=render("en",false)
 @Test @Config(qualifiers="en-rUS-w393dp-h852dp-night-mdpi") fun englishDark()=render("en",true)
 private fun render(lang:String,dark:Boolean){
  TimeZone.setDefault(TimeZone.getTimeZone("Asia/Jerusalem"))
  val out=File(System.getenv("LECTURE_SCREEN_DIR") ?: "build/screens").apply{mkdirs()}
  val fixturePdf=File(out,"demo-summary.pdf").also{it.writeBytes(javaClass.getResourceAsStream("/screenshots/demo-summary.pdf")!!.readBytes())}
  val course=Course("demo-science",if(lang=="he")"שיטות מחקר"else "Scientific methods",emoji="🧪",color=0xFF3949AB)
  val other=Course("demo-math",if(lang=="he")"מתמטיקה שימושית"else "Applied mathematics",emoji="📐",color=0xFF00796B)
  val ready=Lecture("demo-ready",course.id,if(lang=="he")"הרצאה 4 · תכנון ניסוי"else "Lecture 4 · Experimental design",1794002400000,state=LectureState.DONE)
  val processing=Lecture("demo-working",other.id,if(lang=="he")"הרצאה 3 · מודלים"else "Lecture 3 · Models",1794102400000,state=LectureState.PROCESSING,stage="summary",progress=72)
  val event=CalendarImportEvent("demo-next",1,1,if(lang=="he")"הרצאה 5 · ניתוח תוצאות"else "Lecture 5 · Interpreting results","",1799654400000,1799661600000,false,"Asia/Jerusalem")
  val next=CalendarRuleMatch(event,CalendarImportRule("demo-rule","Demo",enabled=true,title="*",targetCourse=course.name,targetCourseId=course.id))
  var screen by mutableIntStateOf(0)
  compose.setContent{
   CompositionLocalProvider(LocalLayoutDirection provides if(lang=="he")LayoutDirection.Rtl else LayoutDirection.Ltr){AppTheme(darkMode=dark){
    Scaffold(bottomBar={if(screen<2 || screen==4)MainTabs(if(screen==0)"home"else if(screen==4)"search"else "courses") {}}){outer->
     AppScaffold(stringResource(if(screen==0)R.string.home_title else if(screen==1)R.string.tab_courses else if(screen==4)R.string.tab_search else R.string.copy_lecture),logo=screen<2,actions={if(screen==0){IconButton(onClick={}){Icon(Icons.Default.Settings,stringResource(R.string.settings))}};if(screen in 2..3){IconButton(onClick={}){Icon(Icons.Default.Share,stringResource(R.string.share_pdf))};IconButton(onClick={}){Icon(Icons.Default.MoreVert,stringResource(R.string.more_actions))}}}){pad->
      val padding=Modifier.padding(top=pad.calculateTopPadding(),bottom=outer.calculateBottomPadding())
      when(screen){
       0->HomeContent(listOf(ready,processing),listOf(course,other),setOf(ready.id),next,emptyList(),false,padding,{},{},{},{_,_->},{})
       1->Column(padding.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){NotebookCover(course.name,stringResource(R.string.course_lecture_count,4),course.coverEmoji(),course.coverColorValue(),CoverPattern.LINED,Modifier.weight(1f)){};NotebookCover(other.name,stringResource(R.string.course_lecture_count,3),other.coverEmoji(),other.coverColorValue(),CoverPattern.GRID,Modifier.weight(1f)){}}}
       2->Column(padding.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){Text(bidi(course.name),style=MaterialTheme.typography.labelMedium);Text(bidi(ready.title),style=MaterialTheme.typography.titleLarge);LectureStatusChip(ready);SectionCard(stringResource(R.string.copy_study_pdf),Icons.Default.AutoStories){SummaryHero(fixturePdf,setOf("standard","detailed"),"standard",{},{})};OutlinedButton(onClick={}){Text(stringResource(R.string.listen_transcript))};OutlinedButton(onClick={}){Text(stringResource(R.string.lecture_sources))}}
       4->SearchContent(if(lang=="he")"ניסוי"else "design",{},listOf(StudyIndex.Hit(ready,course.name,if(lang=="he")"ניסוי טוב מתחיל בשאלה ברורה, משתנה מסביר וקבוצת ביקורת."else "A strong experimental design starts with a clear question and a control group.")),false,padding,{},{})
       else->Column(padding.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){Text(bidi(other.name),style=MaterialTheme.typography.labelMedium);Text(bidi(processing.title),style=MaterialTheme.typography.titleLarge);LectureStatusChip(processing);SectionCard(stringResource(R.string.home_processing),Icons.Default.AutoAwesome){ProcessingSteps(processing);Text(stringResource(R.string.processing_return))}}
      }
     }
    }
   }}
  }
  listOf("home","courses","summary","processing","search").forEachIndexed{i,name->
   compose.runOnIdle{screen=i};compose.waitForIdle()
   compose.onRoot().captureRoboImage(File(out,"$name-$lang-${if(dark)"dark"else "light"}.png").absolutePath)
  }
 }
}
