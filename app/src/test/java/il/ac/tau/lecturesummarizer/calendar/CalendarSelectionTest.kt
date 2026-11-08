package il.ac.tau.lecturesummarizer.calendar

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import il.ac.tau.lecturesummarizer.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[34])
class CalendarSelectionTest {
    private val ctx get()=ApplicationProvider.getApplicationContext<Application>()
    private val source=CalendarSource(12,"University","student@example.invalid","com.google","Asia/Jerusalem")
    private fun event(key:String,title:String="Biology",time:Long=1_800_000_000_000)=CalendarImportEvent(key,12,key.hashCode().toLong(),title,"",time,time+3600000,false,"Asia/Jerusalem",true)
    @Test fun connectedAccountDoesNotRequireSeparateAppLogin(){
        assertTrue(CalendarConnection.connected(true,emptySet(),setOf(12)))
        assertTrue(CalendarConnection.connected(true,setOf(12),emptySet()))
        assertFalse(CalendarConnection.connected(false,setOf(12),setOf(12)))
        assertFalse(CalendarConnection.connected(true,emptySet(),emptySet()))
    }
    @Test fun approvedEventsSurviveReopenInCoursesRulesAndLectures(){
        val repo=Repository(ctx);val chosen=listOf(event("lecture1"),event("lecture2",time=1_800_604_800_000))
        val result=CalendarSelectionImporter.import(ctx,repo,listOf(source),chosen)
        val reopened=Repository(ctx);val store=CalendarRuleStore(ctx,reopened)
        assertEquals(1,result.courses);assertEquals(2,result.events)
        assertEquals(1,reopened.courses.value.size);assertEquals(2,reopened.lectures.value.size)
        assertEquals(listOf(source),store.sources());assertEquals(1,store.rules().size)
        assertTrue(store.rules().single().enabled);assertTrue(store.rules().single().exactTitle)
        assertEquals(2,CalendarRules.preview(store.rules(),chosen).size)
        assertEquals(0,CalendarRules.preview(store.rules(),listOf(event("unselected","Biology exam"))).size)
        assertTrue(Settings(ctx).calendarRulesEnabled);assertEquals(setOf(12L),Settings(ctx).calendarIds)
        assertTrue(CalendarRuleApplier(ctx,reopened).current(CalendarRules.preview(store.rules(),chosen).first()))
        CalendarSelectionImporter.import(ctx,reopened,listOf(source),chosen)
        assertEquals(2,Repository(ctx).lectures.value.size)
    }
    @Test fun selectionIsScopedAndDoesNotEnableBackgroundDownloads(){
        val repo=Repository(ctx);CalendarSelectionImporter.import(ctx,repo,listOf(source),listOf(event("selected")))
        assertFalse(CalendarRuleStore(ctx,repo).automatic)
        val store=CalendarRuleStore(ctx,repo)
        val other=event("other").copy(calendarId=99)
        assertNull(CalendarRules.firstMatch(store.rules(),other))
        assertEquals(0,il.ac.tau.lecturesummarizer.autosync.AutoSyncStore(ctx).counts().size)
    }
    @Test fun searchSelectAllKeepsHiddenSelectionsAndClearReallyClearsEverything(){
        val all=listOf(event("a","Biology"),event("b","Chemistry"),event("c","Biology lab"))
        val filtered=EventSelection.filter(all,"biology")
        assertEquals(listOf("a","c"),filtered.map{it.key})
        val selected=EventSelection.selectVisible(setOf("b"),filtered)
        assertEquals(setOf("a","b","c"),selected)
    }
    @Test fun duplicateCalendarsDoNotCreateDuplicateLectures(){
        val repo=Repository(ctx)
        val other=source.copy(id=13)
        CalendarSelectionImporter.import(ctx,repo,listOf(source,other),listOf(event("a"),event("b").copy(calendarId=13)))
        assertEquals(1,repo.lectures.value.size)
    }
    @Test fun changingChosenCalendarsRevokesThePreviousActiveSource(){
        val repo=Repository(ctx)
        CalendarSelectionImporter.import(ctx,repo,listOf(source),listOf(event("old")))
        val other=source.copy(id=99,account="other@example.invalid")
        CalendarSelectionImporter.import(ctx,repo,listOf(other),listOf(event("new","Chemistry").copy(calendarId=99)))
        assertEquals(listOf(other),CalendarRuleStore(ctx,repo).sources())
        assertEquals(setOf(99L),Settings(ctx).calendarIds)
        assertEquals(2,repo.lectures.value.size) // Historical saved lectures are not deleted.
    }
    @Test fun selectedEventRescheduleUpdatesExistingLocalLecture(){
        val repo=Repository(ctx);val original=event("same")
        CalendarSelectionImporter.import(ctx,repo,listOf(source),listOf(original))
        val id=repo.lectures.value.single().id
        CalendarSelectionImporter.import(ctx,repo,listOf(source),listOf(original.copy(start=original.start+60000,end=original.end+60000)))
        assertEquals(id,repo.lectures.value.single().id);assertEquals(original.start+60000,repo.lectures.value.single().recordedAtMs)
    }
}
