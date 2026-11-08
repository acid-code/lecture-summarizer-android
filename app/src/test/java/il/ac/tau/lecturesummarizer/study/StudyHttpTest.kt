package il.ac.tau.lecturesummarizer.study
import android.app.Application
import il.ac.tau.lecturesummarizer.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import okhttp3.mockwebserver.*
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject
@RunWith(RobolectricTestRunner::class) @Config(application=Application::class,sdk=[34])
class StudyHttpTest {
 @Test fun tutorSendsOnlyQuestionLanguageAndDeviceHeaders()=runBlocking{
   val records=CopyOnWriteArrayList<RecordedRequest>();val server=MockWebServer()
   server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse{if(request.path!!.endsWith("/tutor"))records+=request;return MockResponse().setBody(if(request.path!!.endsWith("/claim"))"{}"else TutorReply("answer","Lecture content",listOf(TutorCitation("t:0","transcript","Transcript","A supported sentence in the lecture."))).toJson().toString())}}
   server.start();try{val base=server.url("/").toString().removeSuffix("/");HttpLectureApi(base,"fixture-token","device-study-A").tutor("lecture-A","enzyme?","en");HttpLectureApi(base,"fixture-token","device-study-B").tutor("lecture-B","מה זה?","he",listOf("מהו אנזים?"));assertEquals(listOf("device-study-A","device-study-B"),records.map{it.getHeader("X-Device-Id")});assertTrue(records.all{it.getHeader("Authorization")=="Bearer fixture-token"});assertEquals(setOf("question","language"),JSONObject(records[0].body.readUtf8()).keys().asSequence().toSet());assertEquals(setOf("question","language","previous_questions"),JSONObject(records[1].body.readUtf8()).keys().asSequence().toSet())}finally{server.shutdown()}
 }
 @Test fun summaryGlossaryParsesAndRateRetryAfterIsPreserved()=runBlocking{
   val server=MockWebServer();server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse{
     val path=request.path!!;return when{path.endsWith("/claim")->MockResponse().setBody("{}");path.contains("summary.json")->MockResponse().setBody("""{"summary":{"glossary":[{"term":"Term","explanation":"Source definition"}]}}""");else->MockResponse().setResponseCode(429).addHeader("Retry-After","120").setBody("""{"error":"rate_limited"}""")}
   }}
   server.start();try{val api=HttpLectureApi(server.url("/").toString().removeSuffix("/"),"fixture-token","device-study-C");assertEquals(listOf(Concept("Term","Source definition")),api.glossary("lecture","standard"));try{api.tutor("lecture","Q","en");fail()}catch(e:HttpStatusException){assertEquals(429,e.code);assertEquals(120,e.retryAfterSeconds)}}finally{server.shutdown()}
 }
}
