package il.ac.tau.lecturesummarizer.slides

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import il.ac.tau.lecturesummarizer.data.*
import il.ac.tau.lecturesummarizer.study.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(application=Application::class,sdk=[34])
class SummaryVariantsTest {
 private val ctx get()=ApplicationProvider.getApplicationContext<Application>()
 @Test fun preloadAndTapShareOneDownloadAndCachedSwitchesNeverRefetch()=runBlocking {
  val cache=SummaryPdfCache(File(ctx.cacheDir,"variants").apply{mkdirs()},"lecture");var downloads=0
  coroutineScope{repeat(8){launch{cache.ensure("standard"){downloads++;delay(20);it.writeText("%PDF-standard")}}}}
  assertEquals(1,downloads);repeat(8){cache.ensure("standard"){downloads++;error("must not refetch")}}
  assertEquals(setOf("standard"),cache.available());assertEquals("standard",cache.density(cache.file("standard")))
 }
 @Test fun workStateSurvivesRecreationAndOldWorkersCannotEraseNewRequests(){
  val store=SummaryVariantState(ctx);store.claim("l",listOf("standard","detailed"),"old");store.claim("l",listOf("detailed"),"new")
  store.update("l","detailed","old","ready",100);assertTrue(SummaryVariantState(ctx).entry("l","detailed")!!.pending)
  store.update("l","detailed","new","updating",34);assertEquals(34,SummaryVariantState(ctx).entry("l","detailed")!!.progress)
  store.update("l","detailed","new","error");assertFalse(store.entry("l","detailed")!!.pending)
 }
 @Test fun serverRunningStateOverridesStaleClientFailureButNotNewlyQueuedWork(){
  val store=SummaryVariantState(ctx);store.claim("sync",listOf("standard"),"old");store.update("sync","standard","old","error")
  SummaryJobsSync.reconcile(store,"sync",mapOf("standard" to SummaryJobState("running-job","updating","pdf")))
  assertTrue(store.entry("sync","standard")!!.pending);assertEquals("pdf",store.entry("sync","standard")!!.stage)
  store.claim("sync",listOf("standard"),"new-local")
  SummaryJobsSync.reconcile(store,"sync",mapOf("standard" to SummaryJobState("old-done","ready")))
  assertEquals("new-local",store.entry("sync","standard")!!.token);assertTrue(store.entry("sync","standard")!!.pending)
 }
 @Test fun pairsDeliveredWithPdfAreUsedOfflineWithoutCallingAnApi(){
  val pdf=File(ctx.cacheDir,"l-standard.pdf").apply{writeText("pdf")};File(pdf.path+".study.json").writeText("""[{"term":"אנזים","definition":"מוריד אנרגיית שפעול"},{"term":"pH","definition":"מדד לחומציות התמיסה"}]""")
  val store=StudyStore(ctx);assertTrue(store.delivered("l",pdf,"standard"));assertEquals(2,StudyStore(ctx).cached("l","standard")!!.entries.size)
 }
 @Test fun movePreservesAllLectureFieldsAndDoesNotCommitLocallyOnServerFailure()=runBlocking {
  val repo=Repository(ctx);val source=repo.addCourse("Source");val target=repo.addCourse("Target");repo.updateCourse(target.id){it.copy(remoteId="target-remote")}
  val l=repo.addLecture(source.id,"Real lecture").copy(remoteId="lecture-remote",state=LectureState.DONE,pdfPath="summary.pdf",slidesUri="deck2",extraSlides=listOf("deck3"),markers=listOf(Marker(12,"note")));repo.updateLecture(l.id){l}
  var calls=0;val api=object:LectureApi by MockLectureApi(){override suspend fun moveLecture(lectureRemoteId:String,courseRemoteId:String){calls++;if(calls==1)error("offline");assertEquals("lecture-remote",lectureRemoteId);assertEquals("target-remote",courseRemoteId)}}
  runCatching{LectureMover.move(repo,api,l.id,target.id)};assertEquals(l,repo.lecture(l.id))
  LectureMover.move(repo,api,l.id,target.id);assertEquals(l.copy(courseId=target.id),repo.lecture(l.id))
 }
}
