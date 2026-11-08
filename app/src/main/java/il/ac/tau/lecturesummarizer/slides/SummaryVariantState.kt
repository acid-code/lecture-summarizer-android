package il.ac.tau.lecturesummarizer.slides

import android.content.Context
import il.ac.tau.lecturesummarizer.data.SummaryDensity

/** Persist per-density progress independently of the lecture's original processing state. */
class SummaryVariantState(ctx:Context) {
 val preferences=ctx.getSharedPreferences("summary_variant_work",Context.MODE_PRIVATE)
 data class Entry(val token:String,val state:String,val progress:Int,val stage:String?=null) { val pending get()=state=="queued"||state=="updating" }
 fun entry(id:String,density:String):Entry?=preferences.getString("$id:$density",null)?.split('|')?.takeIf{it.size>=3}?.let{Entry(it[0],it[1],it[2].toIntOrNull()?:0,it.getOrNull(3)?.takeIf{stage->stage.isNotBlank()} )}
 fun entries(id:String)=SummaryDensity.entries.mapNotNull{d->entry(id,d.api)?.let{d.api to it}}.toMap()
 fun claim(id:String,densities:List<String>,token:String)=synchronized(lock){
  val edit=preferences.edit();densities.forEach{edit.putString("$id:$it","$token|queued|0")};edit.commit();Unit
 }
 /** An older queued worker must never erase a newer request's marker. */
 fun update(id:String,density:String,token:String,state:String,progress:Int=0,stage:String?=null)=synchronized(lock){
  if(entry(id,density)?.token==token)preferences.edit().putString("$id:$density","$token|$state|${progress.coerceIn(0,100)}|${stage.orEmpty().replace("|","")}").commit()
  Unit
 }
 private companion object { val lock=Any() }
}
