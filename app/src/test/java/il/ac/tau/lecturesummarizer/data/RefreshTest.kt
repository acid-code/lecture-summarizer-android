package il.ac.tau.lecturesummarizer.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[34])
class RefreshTest {
 @Test fun failedRefreshKeepsPreviousSearchTextAndSuccessTime()=runBlocking{
    val ctx=ApplicationProvider.getApplicationContext<Application>();val repo=Repository(ctx);val course=repo.addCourse("Biology");val l=repo.addLecture(course.id,"Class")
    val index=StudyIndex(ctx);index.put(l.id,"summary:standard","Previous searchable explanation")
    index.refreshPrefs.edit().putLong("lastSuccess",1234).commit()
    File(repo.pdfDir(),"${l.id}-standard.pdf").writeText("not a valid PDF")
    val progress=mutableListOf<Pair<Int,Int>>()
    val result=index.reindexLocal(ctx,repo){d,t->progress+=d to t}
    assertEquals(1,result.failures);assertEquals(1234,index.lastUpdated);assertEquals(1,index.search(repo.lectures.value,repo.courses.value,"searchable").size)
    assertEquals(listOf(0 to 1,1 to 1),progress)
 }
 @Test fun successfulLocalRefreshAndCourseChangesHavePersistentTimestamps()=runBlocking{
    val ctx=ApplicationProvider.getApplicationContext<Application>();val repo=Repository(ctx);val course=repo.addCourse("Biology")
    assertEquals(0,CourseActivity(ctx).updated(course.id))
    val l=repo.addLecture(course.id,"Class");assertTrue(CourseActivity(ctx).updated(course.id)>0)
    val result=StudyIndex(ctx).reindexLocal(ctx,repo)
    assertEquals(0,result.failures);assertTrue(StudyIndex(ctx).lastUpdated>0)
    repo.updateLecture(l.id){it.copy(title="Updated class")}
    assertTrue(CourseActivity(ctx).updated(course.id)>0)
 }
}
