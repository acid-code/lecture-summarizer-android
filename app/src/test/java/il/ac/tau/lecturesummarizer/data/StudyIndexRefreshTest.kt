package il.ac.tau.lecturesummarizer.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[34])
class StudyIndexRefreshTest {
 @Test fun startRefreshIsSingleFlightAndSurvivesWithoutUiScope()=runBlocking{
  val ctx=ApplicationProvider.getApplicationContext<Application>();val repo=Repository(ctx)
  repo.addCourse("Biology").also{repo.addLecture(it.id,"Class")}
  assertTrue(StudyIndex.startRefresh(ctx,repo))
  assertFalse(StudyIndex.startRefresh(ctx,repo))
  withTimeout(10_000){StudyIndex.live.first{it.phase==StudyIndex.LivePhase.SUCCESS||it.phase==StudyIndex.LivePhase.ERROR}}
  assertEquals(StudyIndex.LivePhase.SUCCESS,StudyIndex.live.value.phase)
  assertTrue(StudyIndex.live.value.generation>=1)
 }
}
