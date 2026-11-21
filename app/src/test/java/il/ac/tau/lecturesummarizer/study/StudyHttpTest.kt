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
 @Test fun r2LargeUploadUsesDirectPutAndSendsReturnedUrl()=runBlocking{
   val server=MockWebServer()
   val directRequests=CopyOnWriteArrayList<RecordedRequest>()
   val jsonRequests=CopyOnWriteArrayList<RecordedRequest>()
   server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse{
     val path=request.path!!
     return when{
       path.endsWith("/claim")->MockResponse().setBody("{}")
       path.endsWith("/v1/uploads")->{
         jsonRequests+=request
         val objectUrl="https://bucket.account.r2.cloudflarestorage.com/uploads/test.m4a"
         MockResponse().setBody("""{"mode":"r2","method":"PUT","put_url":"${server.url("/r2-upload")}","url":"$objectUrl","headers":{"content-type":"audio/mp4"}}""")
       }
       path.endsWith("/r2-upload")->{directRequests+=request;MockResponse().setResponseCode(200)}
       path.endsWith("/audio/segments")->{jsonRequests+=request;MockResponse().setBody("""{"seq":1}""")}
       else->MockResponse().setResponseCode(404).setBody("{}")
     }
   }}
   val file=java.io.File.createTempFile("r2-upload-",".m4a")
   try{
     file.writeBytes(ByteArray(4*1024*1024+1){0})
     server.start()
     val api=HttpLectureApi(server.url("/").toString().removeSuffix("/"),"fixture-token","device-r2")
     api.uploadSegment("lecture-r2",Segment(1,file.absolutePath,0L,10000L),file)
     assertEquals(1,directRequests.size)
     assertEquals("PUT",directRequests[0].method)
     assertEquals("audio/mp4",directRequests[0].getHeader("Content-Type"))
     assertEquals(file.length(),directRequests[0].bodySize)
     val segmentJson=jsonRequests.last{it.path!!.endsWith("/audio/segments")}
     assertEquals("https://bucket.account.r2.cloudflarestorage.com/uploads/test.m4a",JSONObject(segmentJson.body.readUtf8()).getString("url"))
   }finally{file.delete();server.shutdown()}
 }
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
