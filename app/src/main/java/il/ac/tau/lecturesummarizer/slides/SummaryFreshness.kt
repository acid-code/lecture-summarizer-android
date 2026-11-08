package il.ac.tau.lecturesummarizer.slides

import android.content.Context
import il.ac.tau.lecturesummarizer.data.Lecture
import il.ac.tau.lecturesummarizer.data.allSlides
import java.security.MessageDigest

class SummaryFreshness(private val ctx: Context) {
    private val p=ctx.getSharedPreferences("summary_material_versions",Context.MODE_PRIVATE)
    companion object {
        fun revision(uris: List<String>)=MessageDigest.getInstance("SHA-256").digest(uris.joinToString("\n").toByteArray()).joinToString(""){"%02x".format(it)}
    }
    data class Checkpoint(val generation:Long,val dirty:Boolean)
    fun checkpoint(id:String)=Checkpoint(p.getLong("generation:$id",0),p.getBoolean("dirty:$id",false))
    fun restore(id:String,before:Checkpoint,afterGeneration:Long){if(p.getLong("generation:$id",0)==afterGeneration)p.edit().putLong("generation:$id",before.generation).putBoolean("dirty:$id",before.dirty).apply()}
    fun changed(id:String){
        val lecture=(ctx.applicationContext as? il.ac.tau.lecturesummarizer.LectureApp)?.repo?.lecture(id)
        val alreadySummarizing=lecture==null || lecture.jobId!=null || lecture.pdfPath!=null
        p.edit().putBoolean("dirty:$id",p.getBoolean("dirty:$id",false)||alreadySummarizing).putLong("generation:$id",p.getLong("generation:$id",0)+1).commit()
    }
    /** Only record a newly created/finalized run after its full material snapshot was uploaded. */
    fun started(id:String,job:String,revision:String){val key="source:$id:$job";if(!p.contains(key))p.edit().putString(key,revision).commit()}
    fun sourceRevision(id:String,job:String)=p.getString("source:$id:$job",null)
    fun ensureDirty(id:String){p.edit().putBoolean("dirty:$id",true).commit()}
    fun revision(l:Lecture)=revision(l.allSlides.map{uri->"$uri|${l.slideRanges[uri]?.toJson() ?: "all"}"} + "generation:${p.getLong("generation:${l.id}",0)}")
    fun outdated(l: Lecture,density: String): Boolean = p.getBoolean("dirty:${l.id}",false) && p.getString("built:${l.id}:$density",null)!=revision(l)
    fun built(id: String,density: String,revision: String){p.edit().putString("built:$id:$density",revision).commit()}
    fun recordChange(id:String,rev:String,count:Int,densities:List<String>){p.edit().putString("note:$id",org.json.JSONObject().put("revision",rev).put("count",count).put("levels",densities.joinToString(",")).toString()).commit()}
    fun changeNote(id:String)=p.getString("note:$id",null)
    fun state(id: String)=p.getString("state:$id",null)
    fun state(id: String,s: String){p.edit().putString("state:$id",s).commit()}
    fun prepare(id:String,revision:String){if(p.getString("request:$id",null)!=revision){val e=p.edit().putString("request:$id",revision);p.all.keys.filter{it.startsWith("job:$id:")}.forEach{e.remove(it)};e.commit()}}
    fun pendingJob(id:String,density:String,revision:String)=p.getString("job:$id:$revision:$density",null)
    fun pendingJob(id:String,density:String,revision:String,job:String?){p.edit().putString("job:$id:$revision:$density",job).commit()}
}
