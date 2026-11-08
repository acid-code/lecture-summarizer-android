package il.ac.tau.lecturesummarizer.calendar

import android.Manifest
import android.app.Application
import android.content.*
import android.database.*
import android.net.Uri
import android.provider.CalendarContract as C
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[34])
class CalendarProviderSeriesTest {
 private val ctx get()=ApplicationProvider.getApplicationContext<Application>()
 private class Provider(val onlyOneOff:Boolean=false):ContentProvider(){
  val eventReads=mutableListOf<List<String>>();override fun onCreate()=true
  override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,order:String?):Cursor{
   val rows=when{
    uri.path!!.startsWith("/calendars")->listOf(mapOf(C.Calendars._ID to 12L,C.Calendars.CALENDAR_DISPLAY_NAME to "School",C.Calendars.ACCOUNT_NAME to "student@example.invalid",C.Calendars.ACCOUNT_TYPE to "com.google",C.Calendars.CALENDAR_TIME_ZONE to "Asia/Jerusalem"))
    uri.path!!.startsWith("/events")-> {eventReads+=args!!.toList();listOf(mapOf(C.Events.CALENDAR_ID to 12L,C.Events._ID to if(onlyOneOff)555L else 101L,C.Events.TITLE to "Biochemistry",C.Events.RRULE to if(onlyOneOff)"" else "FREQ=WEEKLY;BYDAY=MO",C.Events.DTSTART to 1793606400000L,C.Events.EVENT_TIMEZONE to "Asia/Jerusalem"))}
    else->{eventReads+=args!!.toList();listOf(mapOf(C.Instances.CALENDAR_ID to 12L,C.Instances.EVENT_ID to 999L,C.Instances.TITLE to "Moved lesson",C.Instances.BEGIN to 1794214800000L,C.Instances.END to 1794218400000L,C.Instances.ALL_DAY to 0,C.Events.ORIGINAL_ID to 101L,C.Events.ORIGINAL_INSTANCE_TIME to 1794211200000L),mapOf(C.Instances.CALENDAR_ID to 12L,C.Instances.EVENT_ID to 555L,C.Instances.TITLE to "One-off",C.Instances.BEGIN to 1794214800000L,C.Instances.END to 1794218400000L,C.Instances.ALL_DAY to 0)).filter{!onlyOneOff || it[C.Instances.EVENT_ID]==555L}}
   }
   return MatrixCursor(projection!!).apply{rows.forEach{r->addRow(projection.map{r[it]})}}
  }
  override fun getType(uri:Uri):String?=null;override fun insert(uri:Uri,values:ContentValues?):Uri?=null;override fun delete(uri:Uri,selection:String?,args:Array<out String>?)=0;override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?)=0
 }
 @Test fun masterMetadataGroupsMovedExceptionAndReadsOnlyChosenCalendar(){
  Shadows.shadowOf(ctx).grantPermissions(Manifest.permission.READ_CALENDAR);val provider=Provider();ShadowContentResolver.registerProviderInternal(C.AUTHORITY,provider)
  val source=CalendarSource(12,"School","student@example.invalid","com.google","Asia/Jerusalem")
  val result=CalendarRuleProvider.read(ctx,listOf(source),1794117600000L);assertEquals(2,result.events.size);val series=CalendarSeriesSelection.groups(result.events).single();assertEquals("Biochemistry",series.title);assertEquals("series:12:101",series.key);assertEquals("WEEKLY",series.frequency);assertEquals(1793606400000L,series.start);assertTrue(provider.eventReads.all{it.first()=="12"})
 }
 @Test fun reusedCalendarIdWithDifferentAccountNeverReadsEvents(){
  Shadows.shadowOf(ctx).grantPermissions(Manifest.permission.READ_CALENDAR);val provider=Provider();ShadowContentResolver.registerProviderInternal(C.AUTHORITY,provider)
  assertTrue(CalendarRuleProvider.read(ctx,listOf(CalendarSource(12,"School","other@example.invalid","com.google","Asia/Jerusalem"))).events.isEmpty());assertTrue(provider.eventReads.isEmpty())
 }
 @Test fun emptyRecurrenceStringsNeverTurnOneOffsIntoSeries(){
  Shadows.shadowOf(ctx).grantPermissions(Manifest.permission.READ_CALENDAR);val provider=Provider(true);ShadowContentResolver.registerProviderInternal(C.AUTHORITY,provider)
  val events=CalendarRuleProvider.read(ctx,listOf(CalendarSource(12,"School","student@example.invalid","com.google","Asia/Jerusalem"))).events
  assertEquals(1,events.size);assertFalse(events.single().recurring);assertTrue(CalendarSeriesSelection.groups(events).isEmpty())
 }

}
