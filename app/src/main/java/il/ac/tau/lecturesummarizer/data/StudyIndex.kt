package il.ac.tau.lecturesummarizer.data

import android.content.Context
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.slides.SlideText
import java.io.File
import java.text.Normalizer
import java.util.Locale
import org.json.JSONObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Local-only index: never sends a query or bulk-downloads content to search. */
class StudyIndex(ctx:Context){
 val refreshPrefs=ctx.getSharedPreferences("study_refresh",Context.MODE_PRIVATE)
 val lastUpdated get()=refreshPrefs.getLong("lastSuccess",0L)
 data class RefreshResult(val completed:Int,val total:Int,val failures:Int)
 enum class LivePhase { IDLE,RUNNING,SUCCESS,ERROR }
 data class LiveRefresh(val phase:LivePhase=LivePhase.IDLE,val done:Int=0,val total:Int=0,val generation:Int=0)
 private val dir=File(ctx.filesDir,"study-index").apply{mkdirs()}
 companion object{
  fun normalize(s:String)=Normalizer.normalize(s.lowercase(Locale.ROOT),Normalizer.Form.NFD).replace(Regex("\\p{M}+"),"").replace(Regex("\\s+")," ").trim()
  fun matches(text:String,query:String):Boolean{val hay=normalize(text);val tokens=normalize(query).split(' ').filter{it.isNotBlank()}.take(20);return tokens.isNotEmpty() && tokens.all{it in hay}}
  private val lock=Any()
  /** Survives leaving Search: rememberCoroutineScope used to cancel reindex mid-flight. */
  private val refreshScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
  private val refreshLock=Any()
  @Volatile private var refreshJob:Job?=null
  val live=MutableStateFlow(LiveRefresh())
  fun startRefresh(app:LectureApp)=startRefresh(app,app.repo)
  fun startRefresh(ctx:Context,repo:Repository):Boolean=synchronized(refreshLock){
   if(refreshJob?.isActive==true)return false
   refreshJob=refreshScope.launch{
    val index=StudyIndex(ctx)
    live.value=LiveRefresh(LivePhase.RUNNING,0,repo.lectures.value.size,live.value.generation)
    try{
     val result=index.reindexLocal(ctx,repo){d,t->live.value=live.value.copy(done=d,total=t)}
     live.value=live.value.copy(phase=if(result.failures==0)LivePhase.SUCCESS else LivePhase.ERROR,generation=live.value.generation+1)
    }catch(e:CancellationException){throw e}catch(_:Exception){live.value=live.value.copy(phase=LivePhase.ERROR,generation=live.value.generation+1)}
   }
   true
  }
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
 suspend fun reindexLocal(app:LectureApp,onProgress:(Int,Int)->Unit={_,_->})=reindexLocal(app,app.repo,onProgress)
 suspend fun reindexLocal(ctx:Context,repo:Repository,onProgress:(Int,Int)->Unit={_,_->}):RefreshResult=withContext(Dispatchers.IO){
  val lectures=repo.lectures.value.toList();var done=0;var failures=0
  onProgress(0,lectures.size)
  lectures.forEach{l->currentCoroutineContext().ensureActive()
   val files=(listOfNotNull(l.pdfPath?.let(::File))+SummaryDensity.entries.map{File(repo.pdfDir(),"${l.id}-${it.api}.pdf")}).distinctBy{it.path}.filter{it.exists()}
   files.forEach{f->currentCoroutineContext().ensureActive();try{val density=SummaryDensity.entries.firstOrNull{f.name.endsWith("-${it.api}.pdf")}?.api ?: "unknown";val text=SlideText.read(ctx,f);check(text.isNotBlank());put(l.id,"summary:$density",text)}catch(e:CancellationException){throw e}catch(_:Exception){failures++}}
   l.panoptoSrtPath?.let(::File)?.takeIf{it.exists()}?.let{f->try{val text=il.ac.tau.lecturesummarizer.moodle.Panopto.parseSrt(f.readText()).joinToString(" "){it.text};check(text.isNotBlank());put(l.id,"transcript",text)}catch(e:CancellationException){throw e}catch(_:Exception){failures++}}
   done++;onProgress(done,lectures.size)
  }
  // A partial/error refresh must not overwrite the last known-good timestamp.
  if(failures==0)refreshPrefs.edit().putLong("lastSuccess",System.currentTimeMillis()).apply()
  RefreshResult(done,lectures.size,failures)
 }
}
