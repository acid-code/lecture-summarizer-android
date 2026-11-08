package il.ac.tau.lecturesummarizer.study

import android.content.Context
import il.ac.tau.lecturesummarizer.data.LectureApi
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class TutorCitation(val sourceId:String,val kind:String,val label:String,val quote:String)
data class TutorReply(val kind:String,val answer:String,val citations:List<TutorCitation>)
data class TutorTurn(val question:String,val reply:TutorReply,val id:String=java.util.UUID.randomUUID().toString())
data class CachedConcepts(val entries:List<Concept>,val density:String,val summaryKey:String,val updatedAt:Long){
 val fingerprint get()=hash(JSONArray(entries.map{JSONObject().put("term",it.term).put("explanation",it.explanation)}).toString())
}
private fun hash(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString(""){"%02x".format(it)}
fun TutorReply.toJson()=JSONObject().put("kind",kind).put("answer",answer).put("citations",JSONArray(citations.map{JSONObject().put("source_id",it.sourceId).put("kind",it.kind).put("label",it.label).put("quote",it.quote)}))
fun tutorReplyFromJson(o:JSONObject):TutorReply{
 val kind=o.getString("kind");require(kind in setOf("answer","refused","not_covered"))
 val answer=o.getString("answer");require(answer.isNotBlank()&&answer.length<=5000)
 val a=o.optJSONArray("citations")?:JSONArray();require(a.length()<=6)
 val citations=(0 until a.length()).map{a.getJSONObject(it)}.map{TutorCitation(it.getString("source_id").take(120),it.getString("kind"),it.getString("label").take(160),it.getString("quote").take(1200))}
 require(citations.all{it.sourceId.isNotBlank()&&it.kind in setOf("transcript","summary","slides","context")&&it.quote.length>=12});require(if(kind=="answer")citations.isNotEmpty()else citations.isEmpty());return TutorReply(kind,answer,citations)
}
/** Private app storage, keyed by a hash of the LOCAL lecture id. Never a shared cache or remote-ID filename. */
class StudyStore(ctx:Context){
 private val dir=File(ctx.filesDir,"lecture-study").apply{mkdirs()}
 companion object{private val lock=Any();val levels=listOf("standard","detailed","compact")}
 private fun file(id:String,suffix:String)=File(dir,"${hash(id)}-$suffix.json")
 private fun read(f:File)=runCatching{JSONObject(f.readText())}.getOrNull()
 private fun write(f:File,o:JSONObject){val temp=File(f.path+".tmp");temp.writeText(o.toString());check(temp.renameTo(f))}
 /** Delivered alongside the PDF, so opening the game needs zero additional API calls. */
 fun delivered(id:String,pdf:File,density:String,key:String=""):Boolean {
  val a=runCatching{JSONArray(File(pdf.path+".study.json").readText())}.getOrNull() ?: return false
  val pairs=cleanConcepts((0 until a.length()).mapNotNull{a.optJSONObject(it)?.let{o->Concept(o.optString("term"),o.optString("definition"))}})
  if(pairs.isEmpty())return false
  cache(id,density,key,pairs);return true
 }
 fun cache(id:String,density:String,summaryKey:String,entries:List<Concept>)=synchronized(lock){require(density in levels);write(file(id,density),JSONObject().put("density",density).put("summaryKey",summaryKey).put("updatedAt",System.currentTimeMillis()).put("entries",JSONArray(cleanConcepts(entries).map{JSONObject().put("term",it.term).put("explanation",it.explanation)})))}
 fun cached(id:String,preferred:String?=null):CachedConcepts?=synchronized(lock){
   (listOfNotNull(preferred?.takeIf{it in levels})+levels).distinct().firstNotNullOfOrNull{density->read(file(id,density))?.let{o->runCatching{val a=o.getJSONArray("entries");CachedConcepts(cleanConcepts((0 until a.length()).map{a.getJSONObject(it)}.map{Concept(it.getString("term"),it.getString("explanation"))}),density,o.optString("summaryKey"),o.optLong("updatedAt"))}.getOrNull()}}
 }
 suspend fun refresh(api:LectureApi,id:String,remote:String,density:String?=null,key:String=""){
   val entries=api.glossary(remote,density);cache(id,density?.takeIf{it in levels}?:"standard",key,entries)
 }
 fun turns(id:String):List<TutorTurn> = synchronized(lock){val a=read(file(id,"chat"))?.optJSONArray("turns")?:return@synchronized emptyList();(0 until a.length()).mapNotNull{i->runCatching{val o=a.getJSONObject(i);TutorTurn(o.getString("question"),tutorReplyFromJson(o.getJSONObject("reply")),o.optString("id").takeIf{it.isNotBlank()} ?: hash("$i:${o.getString("question")}"))}.getOrNull()}.takeLast(20)}
 fun append(id:String,turn:TutorTurn)=synchronized(lock){require(turn.question.length in 1..1200);write(file(id,"chat"),JSONObject().put("turns",JSONArray((turns(id)+turn).takeLast(20).map{JSONObject().put("id",it.id).put("question",it.question).put("reply",it.reply.toJson())})))}
 fun clearChat(id:String)=synchronized(lock){file(id,"chat").delete();Unit}
 fun remove(id:String)=synchronized(lock){(levels+"chat").forEach{file(id,it).delete();File(file(id,it).path+".tmp").delete()}}
}
