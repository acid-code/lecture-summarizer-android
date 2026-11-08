package il.ac.tau.lecturesummarizer.calendar

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import il.ac.tau.lecturesummarizer.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(application=Application::class,sdk=[34])
class CalendarDraftCleanupTest {
 private val ctx get()=ApplicationProvider.getApplicationContext<Application>()
 private fun match(course:String,i:Int):CalendarRuleMatch {
  val start=1800000000000L+i*86400000L
  return CalendarRuleMatch(CalendarImportEvent("1:$i:$start",1,i.toLong(),"Lecture $i","",start,start+3600000,false,"Asia/Jerusalem",true,seriesId=i.toLong()),CalendarImportRule("r-$course-$i","watch",true,"*",targetCourse=course,targetCourseId=course))
 }
 private fun linked(repo:Repository,m:CalendarRuleMatch):Lecture {
  val l=repo.addLecture(m.rule.targetCourseId!!,m.event.title).copy(recordedAtMs=m.event.start);repo.updateLecture(l.id){l}
  ctx.getSharedPreferences("calendar_import_links",0).edit().putString("link:${m.event.key}",link(l,m).toString()).commit();return l
 }
 private fun link(l:Lecture,m:CalendarRuleMatch)=JSONObject().put("lecture",l.id).put("title",m.event.title).put("start",m.event.start).put("fingerprint",m.fingerprint)
 @Test fun safeSimulationRepairs44_33_32_30ToSixRealLecturesEach()=runBlocking {
  val repo=Repository(ctx);val matches=mutableListOf<CalendarRuleMatch>()
  val before=listOf(44,33,32,30)
  val courses=before.mapIndexed{index,count->val c=repo.addCourse("Course ${index+1}")
   repeat(6){val l=repo.addLecture(c.id,"Real recording $it");repo.updateLecture(l.id){it.copy(state=LectureState.DONE,remoteId="real-${l.id}",pdfPath="retained-${l.id}.pdf")}}
   repeat(count-6){i->val m=match(c.id,index*100+i);matches+=m;linked(repo,m)};c
  }
  val result=CalendarDraftCleanup.run(ctx,repo,matches)
  assertEquals(before,courses.map{result.before[it.id]});assertEquals(listOf(6,6,6,6),courses.map{result.after[it.id]});assertEquals(115,result.removed)
  assertTrue(repo.lectures.value.all{it.state==LectureState.DONE&&it.pdfPath!=null})
  assertEquals(115,File(ctx.filesDir,"migration-backups/calendar-drafts-v1").listFiles()!!.count{it.extension=="json"})
  assertEquals(0,CalendarDraftCleanup.run(ctx,repo,matches).removed)
  StudyIndex(ctx).reindexLocal(ctx,repo);assertEquals(24,repo.lectures.value.size) // Refreshing search never manufactures rows.
  println("Calendar cleanup simulation: 44→6, 33→6, 32→6, 30→6; 115 proven empty calendar drafts removed; 24 real lectures kept")
 }
 @Test fun preservesEveryContentAndEditSignalAndUnknownProvenance(){
  val base=Lecture("l","c","Lecture 1",1800086400000L);val m=match("c",1);val proof=link(base,m)
  assertTrue(CalendarDraftCleanup.eligible(base,proof,m))
  val changed=listOf(base.copy(title="User renamed"),base.copy(courseId="moved"),base.copy(recordedAtMs=1),base.copy(state=LectureState.DONE),base.copy(remoteId="remote"),base.copy(jobId="job"),base.copy(pdfPath="summary.pdf"),base.copy(slidesUri="slide"),base.copy(extraSlides=listOf("slide")),base.copy(uploadedSlides=listOf("slide")),base.copy(contextPdfUri="context"),base.copy(panoptoId="recording"),base.copy(panoptoSrtPath="transcript"),base.copy(markers=listOf(Marker(1,"note"))),base.copy(segments=listOf(Segment(0,"audio",0,0))),base.copy(stageDetail="edited"),base.copy(progress=1),base.copy(error="error"),base.copy(liveJob=true))
  changed.forEach{assertFalse(it.toString(),CalendarDraftCleanup.eligible(it,proof,m))}
  assertFalse(CalendarDraftCleanup.eligible(base,JSONObject(),m));assertFalse(CalendarDraftCleanup.eligible(base,proof,m.copy(event=m.event.copy(end=m.event.end+1))))
 }
 @Test fun preservesOrphanedAudioAndIndexedTranscriptEvenWithoutMetadata(){
  val repo=Repository(ctx);val c=repo.addCourse("C");val m=match(c.id,2);val l=linked(repo,m)
  val audio=File(repo.recordingsDir(l.id),"uncommitted.m4a");audio.writeText("audio")
  assertEquals(0,CalendarDraftCleanup.run(ctx,repo,listOf(m)).removed);audio.delete()
  StudyIndex(ctx).put(l.id,"transcript","real transcript");assertEquals(0,CalendarDraftCleanup.run(ctx,repo,listOf(m)).removed)
 }
}
