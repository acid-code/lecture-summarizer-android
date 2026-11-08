package il.ac.tau.lecturesummarizer.ui

import android.app.Application
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.calendar.*
import il.ac.tau.lecturesummarizer.data.*
import il.ac.tau.lecturesummarizer.slides.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[34],qualifiers="w411dp-h914dp-notnight-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StudyFixesScreenshotsTest {
 @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
 @Test fun normalPhoneRealWindowInsetsAndVariantStates(){
  val ctx=ApplicationProvider.getApplicationContext<Application>();val display=ctx.getSystemService(android.view.WindowManager::class.java).defaultDisplay
  org.robolectric.Shadows.shadowOf(display).setWidth(1080);org.robolectric.Shadows.shadowOf(display).setHeight(2400)
  val conf=ctx.resources.configuration;conf.setLocales(LocaleList.forLanguageTags("he"));ctx.resources.updateConfiguration(conf,ctx.resources.displayMetrics)
  compose.activity.resources.displayMetrics.widthPixels=1080;compose.activity.resources.displayMetrics.heightPixels=2400
  ctx.resources.displayMetrics.widthPixels=1080;ctx.resources.displayMetrics.heightPixels=2400
  compose.activity.enableEdgeToEdge()
  compose.activity.window.setLayout(1080,2400)
  val source=CalendarSource(1,"לימודים · הסמסטר הנוכחי","student@example.invalid","com.google","Asia/Jerusalem")
  val course=Course("c","ביוכימיה")
  val events=(1..20).map{CalendarImportEvent("1:$it:1800000000000",1,it.toLong(),"ביוכימיה · קבוצה $it","",1800000000000L,1800003600000L,false,"Asia/Jerusalem",true,seriesId=it.toLong(),recurrenceRule="FREQ=WEEKLY;BYDAY=MO,WE")}
  val lecture=Lecture("screenshot-lecture",course.id,"שיעור 03/11 · ממברנות",1800000000000L,state=LectureState.DONE)
  val state=SummaryVariantState(ctx);state.claim(lecture.id,listOf("detailed"),"screen");state.update(lecture.id,"detailed","screen","updating",42)
  val dir=File(ctx.cacheDir,"shot-pdfs").apply{mkdirs()}
  fun pdf(d:String)=File(dir,"lecture-$d.pdf").also{file->file.writeBytes(javaClass.getResourceAsStream("/summary-fixture.pdf")!!.use{it.readBytes()})}
  val standard=pdf("standard");val detailed=pdf("detailed");runBlocking{PdfPreviewCache.preload(standard);PdfPreviewCache.preload(detailed)}
  var screen by mutableIntStateOf(0);var selected by mutableStateOf("detailed");var generated:SummaryDensity?=null
  var view:android.view.View?=null;var navBottom=0;var clicks=0
  compose.setContent{CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl){AppTheme(darkMode=false){
   view=LocalView.current;navBottom=WindowInsets.navigationBars.getBottom(LocalDensity.current)
   if(screen<2)CalendarImportFrame({},ctx.getString(if(screen==0)R.string.calendar_preview else R.string.calendar_import_series_count,1),true,{clicks++}){pad->
    val body=Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize().padding(horizontal=16.dp)
    if(screen==0)CalendarSourcePickerContent((1..20).map{source.copy(id=it.toLong())},setOf(1),{},body)
    else CalendarEventPickerContent(events,setOf("series:1:1"),"",{},{},body)
   }else if(screen<4)AppScaffold("ביוכימיה"){pad->Column(Modifier.padding(pad).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
    Text(bidi(lecture.title),style=MaterialTheme.typography.titleLarge);LectureStatusChip(lecture)
    SummaryHero(if(selected=="standard")standard else detailed,setOf("standard","detailed"),selected,{selected=it.api},{},updating=if(screen==2)setOf("detailed")else emptySet(),failed=if(screen==3)setOf("detailed")else emptySet(),onGenerate={generated=it})
    if(screen==3)Row{Text(ctx.getString(R.string.summary_update_failed),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall);TextButton(onClick={}){Text(ctx.getString(R.string.retry))}}
   }}else AppScaffold(ctx.getString(R.string.home_title)){pad->HomeContent(listOf(lecture),listOf(course),setOf(lecture.id),null,emptyList(),false,Modifier.padding(pad),{},{},{},{_,_->},{},calendarStatus={CalendarWatchStatus(emptyList(),true,false,0,false,{},{},compact=true,courseCount=6)})}
  }}}
  // Dispatch physical pixels through the platform View inset host (no footerInsets test override).
  compose.runOnIdle{ViewCompat.dispatchApplyWindowInsets(view!!,WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.navigationBars(),Insets.of(0,0,0,48)).setVisible(WindowInsetsCompat.Type.navigationBars(),true).build())}
  compose.waitForIdle();assertEquals(48,navBottom)
  val out=File(System.getenv("LECTURE_SCREEN_DIR")?:"build/screens/study-fixes").apply{mkdirs()}
  fun shot(name:String){compose.waitForIdle();compose.onRoot().captureRoboImage(File(out,"$name-he-1080x2400-420dpi.png").absolutePath)}
  for(i in 0..1){compose.runOnIdle{screen=i};val button=compose.onNodeWithTag("calendar-import-primary");button.assertIsDisplayed();val b=button.fetchSemanticsNode().boundsInRoot;val root=compose.onRoot().fetchSemanticsNode().boundsInRoot;assertTrue("button overlaps physical 48px gesture area",b.bottom<=root.bottom-48);button.performClick();shot(if(i==0)"calendar-list"else "series-picker")
   val listTag=if(i==0)"calendar-source-list"else "calendar-series-list";val itemTag=if(i==0)"calendar-source:20"else "calendar-series:series:1:20"
   compose.onNodeWithTag(listTag).performScrollToNode(hasTestTag(itemTag));val last=compose.onNodeWithTag(itemTag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
   assertTrue("last list item hidden under sticky action",last.bottom<=button.fetchSemanticsNode().boundsInRoot.top)
  }
  compose.runOnIdle{screen=2};shot("summary-updating")
  compose.onNodeWithTag("summary-variant:standard").assertIsEnabled().performClick().assertIsSelected()
  compose.onNodeWithTag("summary-variant:compact").performClick();compose.onNodeWithText(ctx.getString(R.string.summary_generate_variant)).assertIsDisplayed();compose.onNodeWithText(ctx.getString(R.string.summary_generate_confirm)).performClick();compose.runOnIdle{assertEquals(SummaryDensity.COMPACT,generated);assertEquals("standard",selected)}
  compose.runOnIdle{screen=3;state.update(lecture.id,"detailed","screen","error")};shot("summary-failed")
  compose.onAllNodesWithText(ctx.getString(R.string.summary_update_failed)).assertCountEquals(1)
  compose.runOnIdle{screen=4};shot("home-failed-watch-off")
  compose.onNodeWithText(ctx.getString(R.string.st_failed)).assertExists()
 }
}
