package il.ac.tau.lecturesummarizer.data

import android.content.Context
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.slides.SlideText
import java.io.File
import java.text.Normalizer
import java.util.Locale
import org.json.JSONObject
import kotlinx.coroutines.*

/** Local-only index: never sends a query or bulk-downloads content to search. */
class StudyIndex(ctx:Context){
 private val dir=File(ctx.filesDir,"study-index").apply{mkdirs()}
 companion object{
  fun normalize(s:String)=Normalizer.normalize(s.lowercase(Locale.ROOT),Normalizer.Form.NFD).replace(Regex("\\p{M}+"),"").replace(Regex("\\s+")," ").trim()
  fun matches(text:String,query:String):Boolean{val hay=normalize(text);val tokens=normalize(query).split(' ').filter{it.isNotBlank()}.take(20);return tokens.isNotEmpty() && tokens.all{it in hay}}
  private val lock=Any()
 }
 private fun read(id:String)=runCatching{JSONObject(File(dir,"$id.json").readText())}.getOrDefault(JSONObject())
 fun put(id:String,key:String,text:String)=synchronized(lock){val o=read(id);if(key.startsWith("summary:") && key!="summary:unknown")o.remove("summary:unknown");o.put(key,text.take(300_000));val f=File(dir,"$id.json");val t=File(dir,"$id.tmp");t.writeText(o.toString());check(t.renameTo(f))}
 fun remove(id:String)=synchronized(lock){File(dir,"$id.json").delete();File(dir,"$id.tmp").delete()}
 data class Hit(val lecture:Lecture,val course:String,val snippet:String)
 fun search(lectures:List<Lecture>,courses:List<Course>,query:String):List<Hit> = lectures.sortedByDescending{it.recordedAtMs}.asSequence().mapNotNull{l->
  val course=courses.firstOrNull{it.id==l.courseId}?.name.orEmpty();val o=read(l.id)
  val body=o.keys().asSequence().joinToString("\n"){o.optString(it)}
  val searchable="${l.title} $course\n$body"
  if(!matches(searchable,query))null else {val needle=normalize(query).split(' ').first();val at=normalize(body).indexOf(needle).coerceAtLeast(0);Hit(l,course,body.substring((at-35).coerceAtLeast(0).coerceAtMost(body.length),(at+150).coerceAtMost(body.length)))}
 }.take(100).toList()
 suspend fun reindexLocal(app:LectureApp)=withContext(Dispatchers.IO){
  app.repo.lectures.value.forEach{l->currentCoroutineContext().ensureActive()
   val files=(listOfNotNull(l.pdfPath?.let(::File))+SummaryDensity.entries.map{File(app.repo.pdfDir(),"${l.id}-${it.api}.pdf")}).distinctBy{it.path}.filter{it.exists()}
   files.forEach{f->currentCoroutineContext().ensureActive();runCatching{val density=SummaryDensity.entries.firstOrNull{f.name.endsWith("-${it.api}.pdf")}?.api ?: "unknown";put(l.id,"summary:$density",SlideText.read(app,f))}}
   l.panoptoSrtPath?.let(::File)?.takeIf{it.exists()}?.let{f->put(l.id,"transcript",il.ac.tau.lecturesummarizer.moodle.Panopto.parseSrt(f.readText()).joinToString(" "){it.text})}
  }
 }
}
