package il.ac.tau.lecturesummarizer.ui

import android.app.Application
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.calendar.*
import il.ac.tau.lecturesummarizer.data.*
import org.junit.Assert.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[34],qualifiers="w393dp-h852dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeCompactScreenshotsTest {
 @get:Rule val compose=createComposeRule()
 @Test fun hebrewNormal()=render("he",1f)
 @Test fun englishNormal()=render("en",1f)
 @Test @Config(qualifiers="w320dp-h640dp-notnight-mdpi") fun hebrewLargeSmallPhone()=render("he",2f,true)
 @Test @Config(qualifiers="w320dp-h640dp-notnight-mdpi") fun englishLargeSmallPhone()=render("en",2f,true)
 @Test @Config(qualifiers="w320dp-h640dp-notnight-mdpi") fun hebrewSmallThreeButtonNavigation()=render("he",1f,true)
 @Test @Config(qualifiers="w320dp-h640dp-notnight-mdpi") fun hebrewSmallKeyboardAndLargeFont()=render("he",2f,true,240)
 private fun render(lang:String,font:Float,small:Boolean=false,ime:Int=0){
  val ctx=ApplicationProvider.getApplicationContext<Application>();val conf=ctx.resources.configuration;conf.setLocales(LocaleList.forLanguageTags(lang));ctx.resources.updateConfiguration(conf,ctx.resources.displayMetrics);TimeZone.setDefault(TimeZone.getTimeZone("Asia/Jerusalem"))
  val course=Course("fixture-course",if(lang=="he")"ביוכימיה של חלבונים ואנזימים"else "Biochemistry of proteins and enzymes")
  val source=CalendarSource(1,if(lang=="he")"לימודים · הסמסטר הנוכחי"else "University · current semester","student@example.invalid","com.google","Asia/Jerusalem")
  val matches=(1..8).map{i->CalendarRuleMatch(CalendarImportEvent("1:$i:1",1,i.toLong(),if(lang=="he")"הרצאה $i · מבנה החלבון והפעילות האנזימטית"else "Lecture $i · Protein structure and enzyme activity","",1797840000000L+i*86400000,1797847200000L+i*86400000,false,"Asia/Jerusalem",true),CalendarImportRule("rule-$i","fixture",true,"*",targetCourse=course.name,targetCourseId=course.id))}
  val seriesEvents=matches.flatMapIndexed{i,m->(0..1).map{n->m.event.copy(key="1:${i+1}:${m.event.start+n*604800000L}",start=m.event.start+n*604800000L,end=m.event.end+n*604800000L,seriesId=(i+1).toLong(),recurrenceRule="FREQ=WEEKLY;BYDAY=MO,WE",seriesTitle=if(lang=="he")"ביוכימיה · קבוצה ${i+1}"else "Biochemistry · group ${i+1}",seriesStart=m.event.start)}}+
   matches.first().event.copy(key="1:1:changed",eventId=999,seriesId=1,seriesTitle=if(lang=="he")"ביוכימיה · קבוצה 1"else "Biochemistry · group 1",recurrenceRule="FREQ=WEEKLY;BYDAY=MO,WE",seriesStart=matches.first().event.start)+matches.first().event.copy(key="one-off",title="One-off party",recurring=false)
  var screen by mutableIntStateOf(0);var selected by mutableStateOf(emptySet<String>());var actionCount=0
  compose.setContent{CompositionLocalProvider(LocalDensity provides Density(1f,font),LocalLayoutDirection provides if(lang=="he")LayoutDirection.Rtl else LayoutDirection.Ltr){AppTheme(darkMode=false){
   if(screen<3)AppScaffold(ctx.getString(if(screen==0)R.string.home_title else R.string.calendar_import_title)){pad->when(screen){
    0->HomeContent(emptyList(),listOf(course),emptySet(),matches.first(),emptyList(),false,Modifier.padding(pad),{},{},{},{_,_->},{},calendarStatus={CalendarWatchStatus(matches,true,false,0,false,{},{actionCount++},watchConfigured=false,compact=true)},upcoming=matches,onUpcomingAll={})
    1->Column(Modifier.padding(pad).padding(20.dp)){CalendarWatchStatus(matches,true,false,0,false,{},{actionCount++},watchConfigured=false,compact=true)}
    else->Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(20.dp)){CalendarWatchStatus(matches,true,false,0,false,{},{},watchConfigured=true)}
   }}else CalendarImportFrame({},ctx.getString(if(screen==3)R.string.calendar_preview else R.string.calendar_import_series_count,selected.size),screen==3 || selected.isNotEmpty(),{actionCount++},footerInsets=WindowInsets(bottom=(if(ime>0)ime else 48).dp)){pad->
    val body=Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize().padding(horizontal=20.dp)
    if(screen==3)CalendarSourcePickerContent((1..12).map{source.copy(id=it.toLong(),name=source.name+" $it")},setOf(1L),{},body,header={Text(ctx.getString(R.string.calendar_term,"01/10/2026","31/03/2027"),style=MaterialTheme.typography.bodySmall)})
    else CalendarEventPickerContent(seriesEvents,selected,"",{},{selected=it},body)
   }
  }}}
  val out=File(System.getenv("LECTURE_SCREEN_DIR") ?: "build/screens/home-compact").apply{mkdirs()};val size=(if(font==1f)"normal"else "large")+(if(small)"-small"else "")+(if(ime>0)"-ime"else "")
  fun shot(name:String){compose.waitForIdle();compose.onRoot().captureRoboImage(File(out,"after-$name-$lang-$size.png").absolutePath)}
  listOf("home","lecture-question","upcoming","calendar-preview","series-picker").forEachIndexed{i,name->
   compose.runOnIdle{screen=i};shot(name)
   if(i==0 && font==1f && !small){compose.onNodeWithText(ctx.getString(R.string.home_more_events)).performScrollTo().performClick();shot("home-expanded");compose.onNodeWithText(ctx.getString(R.string.home_fewer_events)).performScrollTo().performClick()}
   if(i==1){for(tag in listOf("calendar-watch-action-text","calendar-watch-counts")){
    val layouts=mutableListOf<TextLayoutResult>();compose.onNodeWithTag(tag,useUnmergedTree=true).performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
    assertTrue(layouts.isNotEmpty());layouts.forEach{layout->assertFalse("$lang/$font $tag overflow width=${layout.didOverflowWidth} height=${layout.didOverflowHeight} size=${layout.size} lines=${layout.lineCount} bottom=${layout.getLineBottom(layout.lineCount-1)}",layout.hasVisualOverflow);for(line in 0 until layout.lineCount)assertFalse(layout.isLineEllipsized(line))}
   };compose.onNodeWithTag("calendar-watch-action").performClick();compose.runOnIdle{assertEquals(1,actionCount)}}
   if(i>=3){val button=compose.onNodeWithTag("calendar-import-primary");button.assertIsDisplayed();val bounds=button.fetchSemanticsNode().boundsInRoot;val root=compose.onRoot().fetchSemanticsNode().boundsInRoot;assertTrue("action below safe inset",bounds.bottom<=root.bottom-(if(ime>0)ime else 48)+1);assertTrue(bounds.top>=0);assertTrue(bounds.height>=48)}
   if(i==4){
    compose.onNodeWithText("One-off party").assertDoesNotExist()
    compose.onNodeWithTag("calendar-series-list").performScrollToNode(hasTestTag("calendar-series:series:1:1"));compose.onNodeWithTag("calendar-series:series:1:1").performClick();compose.runOnIdle{assertEquals(setOf("series:1:1"),selected);assertEquals(3,CalendarSeriesSelection.instances(selected,seriesEvents).size)};shot("series-picker-scrolled")
    compose.onNodeWithTag("calendar-series-list").performScrollToNode(hasText(ctx.getString(R.string.calendar_select_all)));compose.onNodeWithText(ctx.getString(R.string.calendar_select_all)).performScrollTo().performClick();compose.runOnIdle{assertEquals(8,selected.size)}
    compose.onNodeWithText(ctx.getString(R.string.calendar_clear_all)).performScrollTo().performClick();compose.runOnIdle{assertTrue(selected.isEmpty())}
   }
  }
 }
 @Test fun upcomingExpansionIsBoundedAndCollapses(){
  val matches=(1..8).map{val e=CalendarImportEvent("event-$it",1,it.toLong(),"Lecture $it","",1800000000000L+it,1800003600000L+it,false,"Asia/Jerusalem");CalendarRuleMatch(e,CalendarImportRule("r","r",true,"*",targetCourse="Biochemistry"))};var opened=false
  val ctx=ApplicationProvider.getApplicationContext<Application>();compose.setContent{AppTheme(darkMode=false){Column{UpcomingLectureRows(matches){opened=true}}}}
  compose.onNodeWithTag("upcoming:event-1").assertExists();compose.onNodeWithTag("upcoming:event-2").assertDoesNotExist()
  compose.onNodeWithText(ctx.getString(R.string.home_more_events)).performClick();compose.onNodeWithTag("upcoming:event-3").assertExists();compose.onNodeWithTag("upcoming:event-4").assertDoesNotExist()
  compose.onNodeWithText(ctx.getString(R.string.home_all_events,ltr(8))).performClick();compose.runOnIdle{assertTrue(opened)}
  compose.onNodeWithText(ctx.getString(R.string.home_fewer_events)).performClick();compose.onNodeWithTag("upcoming:event-2").assertDoesNotExist()
 }
 @Test fun seriesSearchBulkActionsKeepHiddenSelectionsUntilClear(){
  val ctx=ApplicationProvider.getApplicationContext<Application>()
  val events=(1..2).map{CalendarImportEvent("12:$it:1",12,it.toLong(),"Biochemistry$it","",1800000000000L,1800003600000L,false,"Asia/Jerusalem",true,seriesId=it.toLong(),recurrenceRule="FREQ=WEEKLY")}
  var selected by mutableStateOf(setOf("series:12:2"));var query by mutableStateOf("")
  compose.setContent{AppTheme(darkMode=false){CalendarEventPickerContent(events,selected,query,{query=it},{selected=it})}}
  compose.onNode(hasSetTextAction()).performTextInput("Biochemistry1")
  compose.onNodeWithText(ctx.getString(R.string.calendar_select_visible)).performScrollTo().performClick();compose.runOnIdle{assertEquals(setOf("series:12:1","series:12:2"),selected)}
  compose.onNodeWithText(ctx.getString(R.string.calendar_clear_all)).performScrollTo().performClick();compose.runOnIdle{assertTrue(selected.isEmpty())}
 }

}
