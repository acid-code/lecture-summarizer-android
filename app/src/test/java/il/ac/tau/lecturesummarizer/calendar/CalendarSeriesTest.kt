package il.ac.tau.lecturesummarizer.calendar

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import il.ac.tau.lecturesummarizer.data.Repository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[34])
class CalendarSeriesTest {
 private val ctx get()=ApplicationProvider.getApplicationContext<Application>()
 private val source=CalendarSource(12,"School","student@example.invalid","com.google","Asia/Jerusalem")
 private fun event(n:Int,root:Long=101,title:String="Biochemistry")=CalendarImportEvent("12:$root:${1800000000000L+n*604800000L}",12,root,title,"",1800000000000L+n*604800000L,1800003600000L+n*604800000L,false,"Asia/Jerusalem",true,root,"FREQ=WEEKLY;BYDAY=MO,WE","",title,1800000000000L)
 @Test fun oneRowPerSeriesAndNoOneOffs(){val rows=listOf(event(0),event(1),event(2,102),event(3,103).copy(recurring=false,seriesId=null));val groups=CalendarSeriesSelection.groups(rows);assertEquals(2,groups.size);assertEquals(2,groups.first().instances.size)}
 @Test fun movedAndRenamedExceptionKeepsMasterIdentityAndSchedule(){val root=event(0);val moved=event(1).copy(eventId=999,title="Moved class",start=1800000000000L+700000000,end=1800003600000L+700000000);val s=CalendarSeriesSelection.groups(listOf(moved,root)).single();assertEquals("Biochemistry",s.title);assertEquals(root.seriesStart,s.start);assertEquals(2,s.instances.size);assertEquals(listOf(DayOfWeek.MONDAY,DayOfWeek.WEDNESDAY),s.days)}
 @Test fun selectionIncludesEveryInstanceIncludingExceptions(){val rows=listOf(event(0),event(1).copy(eventId=999,title="Moved"),event(2,102));val keys=setOf(rows.first().seriesKey!!);assertEquals(2,CalendarSeriesSelection.instances(keys,rows).size)}
 @Test fun sameTitleDifferentSeriesDoesNotBroadenSelection(){val rows=listOf(event(0),event(1,102));val r=CalendarImportRule("rule","series",true,targetCourse="Biochemistry",recurringOnly=true,seriesKeys=setOf(rows.first().seriesKey!!));assertEquals(1,CalendarRules.preview(listOf(r),rows).size)}
 @Test fun seriesIdsAreScopedToCalendar(){val a=event(0);val b=a.copy(calendarId=13,key="13:101:1800000000000");assertNotEquals(a.seriesKey,b.seriesKey);assertEquals(2,CalendarSeriesSelection.groups(listOf(a,b)).size)}
 @Test fun restoredInstanceKeysBecomeSeriesKeysAndOneOffsAreDropped(){val a=event(0);val b=event(1);val one=event(2,102).copy(recurring=false);assertEquals(setOf(a.seriesKey!!),CalendarSeriesSelection.migrateKeys(setOf(b.key,one.key),CalendarSeriesSelection.groups(listOf(a,b,one))))}
 @Test fun oldPickerRulesMigrateWithoutLosingCourseOrDisabledState(){val old=CalendarImportRule("selection:12:hash","Bio",false,"Biochemistry",targetCourse="Course",calendars=setOf(12),targetCourseId="course",exactTitle=true);val r=CalendarSeriesSelection.migrateRules(listOf(old),listOf(event(0),event(1))).single();assertFalse(r.enabled);assertEquals("course",r.targetCourseId);assertTrue(r.recurringOnly);assertEquals(setOf("series:12:101"),r.seriesKeys);assertEquals(listOf(r),CalendarSeriesSelection.migrateRules(listOf(r),listOf(event(0))))}
 @Test fun unmappableAndCustomRulesArePreserved(){val old=CalendarImportRule("selection:12:x","Missing",true,"Missing",targetCourse="Course",exactTitle=true);val custom=old.copy(id="custom",title="Biochemistry");assertEquals(listOf(old,custom),CalendarSeriesSelection.migrateRules(listOf(old,custom),listOf(event(0))))}
 @Test fun seriesRulesPersistAndRestoreSelection(){val repo=Repository(ctx);val rows=listOf(event(0),event(1));CalendarSelectionImporter.import(ctx,repo,listOf(source),rows);val store=CalendarRuleStore(ctx,Repository(ctx));assertEquals(setOf("series:12:101"),CalendarSeriesSelection.savedKeys(store.rules(),rows));assertEquals(0,repo.lectures.value.size);assertEquals(2,CalendarRules.preview(store.rules(),rows).size);assertFalse(store.automatic)}
 @Test fun unselectedPickerSeriesAreDisabledWithoutDeletingHistory(){val repo=Repository(ctx);CalendarSelectionImporter.import(ctx,repo,listOf(source),listOf(event(0),event(1,102)));CalendarSelectionImporter.import(ctx,repo,listOf(source),listOf(event(0)));val rules=CalendarRuleStore(ctx,repo).rules();assertEquals(1,rules.count{it.enabled});assertEquals(0,repo.lectures.value.size);assertEquals(1,CalendarRules.preview(rules,listOf(event(0),event(1,102))).size)}
 @Test fun singleOneOffImportIsRejected(){val repo=Repository(ctx);try{CalendarSelectionImporter.import(ctx,repo,listOf(source),listOf(event(0).copy(recurring=false)));fail("one-off must be rejected")}catch(_:IllegalArgumentException){};assertTrue(repo.lectures.value.isEmpty())}
 @Test fun intervalAndRdateDoNotInventWeeklyFrequency(){val s=CalendarSeriesSelection.groups(listOf(event(0).copy(recurrenceRule="FREQ=WEEKLY;INTERVAL=2;BYDAY=SU,TH"))).single();assertEquals(2,s.interval);assertEquals(listOf(DayOfWeek.SUNDAY,DayOfWeek.THURSDAY),s.days);val other=s.copy(rule="");assertEquals("",other.frequency);assertTrue(other.days.isEmpty())}
 @Test fun pendingCacheRetainsSeriesMetadata(){val e=event(0);val r=CalendarImportRule("r","Bio",true,"*",targetCourse="Bio");val store=CalendarRuleStore(ctx);store.pending(listOf(CalendarRuleMatch(e,r)));assertEquals(e,store.pending().single().event)}
 @Test fun firstPreviewAlreadyUsesMigratedSeriesForRenamedExceptions(){val old=CalendarImportRule("selection:12:old","Bio",true,"Biochemistry",targetCourse="Course",calendars=setOf(12),exactTitle=true);assertEquals(2,CalendarRules.preview(listOf(old),listOf(event(0),event(1).copy(eventId=999,title="Moved class"))).size)}

}
