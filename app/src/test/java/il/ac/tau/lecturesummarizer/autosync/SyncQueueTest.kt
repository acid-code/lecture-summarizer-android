package il.ac.tau.lecturesummarizer.autosync

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import il.ac.tau.lecturesummarizer.data.*
import il.ac.tau.lecturesummarizer.calendar.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[34])
class SyncQueueTest {
    @Test fun countsAndRowsDescribeActualLectureStateNotImportClaim(){
        val ctx=ApplicationProvider.getApplicationContext<Application>();val repo=Repository(ctx);val st=AutoSyncStore(ctx)
        val course=repo.addCourse("Biology");val l=repo.addLecture(course.id,"Class 2")
        st.setStatus("biology|LECTURE|1000","done:${l.id}")
        assertEquals("queued",st.entries(repo).single().category)
        repo.updateLecture(l.id){it.copy(state=LectureState.FAILED)}
        assertEquals("failed",st.entries(repo).single().category)
        repo.updateLecture(l.id){it.copy(state=LectureState.DONE)}
        val row=st.entries(repo).single();assertEquals("done",row.category);assertEquals("Biology",row.course);assertEquals("Class 2",row.title)
        assertFalse(st.retry(row.key))
    }
    @Test fun retryAndDismissOnlyTouchChosenPendingEvent(){
        val ctx=ApplicationProvider.getApplicationContext<Application>();val st=AutoSyncStore(ctx);val repo=Repository(ctx)
        val c=TauClass("Chemistry",ClassKind.LECTURE,null,null,null,1000,2000,false)
        st.track(c,"Chemistry lecture");st.setStatus(AutoSyncStore.key(c),"wait:4000");st.setStatus("other|LECTURE|1001","claim:4000")
        val wait=st.entries(repo).first{it.category=="wait"};assertEquals("Chemistry lecture",wait.title)
        assertFalse(st.retry("other|LECTURE|1001"));assertTrue(st.retry(wait.key));assertNull(st.status(wait.key))
        st.setStatus(wait.key,"notfound:4001");st.dismissNotFound(wait.key)
        assertEquals("dismissed",st.entries(repo).first{it.key==wait.key}.category)
        assertEquals("claim:4000",st.status("other|LECTURE|1001"))
    }
}
