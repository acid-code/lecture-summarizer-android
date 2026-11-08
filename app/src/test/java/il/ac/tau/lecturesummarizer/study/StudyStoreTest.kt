package il.ac.tau.lecturesummarizer.study
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import il.ac.tau.lecturesummarizer.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
@RunWith(RobolectricTestRunner::class) @Config(application=Application::class,sdk=[34])
class StudyStoreTest {
 private fun store()=StudyStore(ApplicationProvider.getApplicationContext<Application>())
 private val entries=listOf(Concept("Term","A precise definition from the stored summary"),Concept("Other","A different explanation in the summary"))
 private val reply=TutorReply("answer","A precise definition from the stored summary",listOf(TutorCitation("t:0","transcript","Transcript",entries[0].explanation)))
 @Test fun cachedConceptsWorkAfterReopeningWithoutApiAndStayPerLecture(){val s=store();s.cache("offline-A","standard","job-A",entries);s.cache("offline-B","detailed","job-B",listOf(entries[1]));val re=store();assertEquals(entries,re.cached("offline-A")!!.entries);assertEquals("job-B",re.cached("offline-B")!!.summaryKey);assertNotEquals(re.cached("offline-A")!!.fingerprint,re.cached("offline-B")!!.fingerprint)}
 @Test fun preferredSummaryLevelAndContentFingerprintAreStable(){val s=store();s.cache("density","standard","one",entries);s.cache("density","detailed","two",listOf(entries[0]));assertEquals("detailed",s.cached("density","detailed")!!.density);val before=s.cached("density","standard")!!.fingerprint;s.cache("density","standard","new-job",entries);assertEquals(before,s.cached("density","standard")!!.fingerprint)}
 @Test fun failedRefreshKeepsOldCache()=runBlocking{val s=store();s.cache("failure","standard","old",entries);val api=object:LectureApi by MockLectureApi(){override suspend fun glossary(lectureRemoteId:String,density:String?):List<Concept>{error("offline")}};try{s.refresh(api,"failure","remote");fail()}catch(_:IllegalStateException){};assertEquals("old",s.cached("failure")!!.summaryKey)}
 @Test fun refreshedCacheComesOnlyFromSummaryGlossary()=runBlocking{val s=store();val api=object:LectureApi by MockLectureApi(){override suspend fun glossary(lectureRemoteId:String,density:String?)=entries};s.refresh(api,"refreshed","remote","standard","job");assertEquals(entries,s.cached("refreshed")!!.entries)}
 @Test fun chatIsIsolatedBoundedAndClearDoesNotDeleteConcepts(){val s=store();s.clearChat("chat-a");s.clearChat("chat-b");s.cache("chat-a","standard","job",entries);repeat(25){s.append("chat-a",TutorTurn("Question $it",reply))};s.append("chat-b",TutorTurn("Only B",reply));assertEquals(20,s.turns("chat-a").size);assertEquals("Question 5",s.turns("chat-a").first().question);s.clearChat("chat-a");assertTrue(s.turns("chat-a").isEmpty());assertEquals(1,s.turns("chat-b").size);assertNotNull(s.cached("chat-a"))}
 @Test fun removingLecturePurgesAllStudyDataAndIdsCannotEscapeStorage(){val s=store();val id="../../private";s.cache(id,"standard","j",entries);s.cache(id,"detailed","j",entries);s.append(id,TutorTurn("Q",reply));s.remove(id);assertNull(s.cached(id));assertTrue(s.turns(id).isEmpty())}
 @Test fun nakedModelAnswerWithoutEvidenceIsNotAccepted(){try{tutorReplyFromJson(JSONObject().put("kind","answer").put("answer","unsupported"));fail()}catch(_:IllegalArgumentException){}}
}
