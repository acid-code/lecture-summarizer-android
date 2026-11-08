package il.ac.tau.lecturesummarizer.calendar

import android.content.Context
import il.ac.tau.lecturesummarizer.data.*
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Conservative repair: legacy calendar link + exact source fingerprint + untouched default draft. */
object CalendarDraftCleanup {
 /** Disabled watch rules can still prove ownership of old placeholders; this never enables those rules. */
 fun proofs(rules:List<CalendarImportRule>,events:List<CalendarImportEvent>)=rules.flatMap{CalendarRules.preview(listOf(it.copy(enabled=true)),events)}
 data class Counts(val before:Map<String,Int>,val after:Map<String,Int>,val removed:Int)
 fun eligible(l:Lecture,link:JSONObject,match:CalendarRuleMatch):Boolean {
  val target=match.rule.targetCourseId ?: return false // Unprovable legacy destinations are preserved.
  if(link.optString("lecture")!=l.id||link.optString("fingerprint")!=match.fingerprint)return false
  if(link.optString("title")!=match.event.title||link.optLong("start")!=match.event.start)return false
  return l==Lecture(l.id,target,link.optString("title"),link.optLong("start"))
 }
 fun hasFiles(ctx:Context,l:Lecture):Boolean {
  val root=ctx.filesDir
  if(File(root,"recordings/${l.id}").walkTopDown().any{it.isFile})return true
  if(File(root,"pdfs").listFiles()?.any{it.name=="${l.id}.pdf"||it.name.startsWith("${l.id}-")}==true)return true
  if(File(root,"panopto/${l.id}.srt").exists()||File(root,"study-index/${l.id}.json").exists())return true
  val hash=MessageDigest.getInstance("SHA-256").digest(l.id.toByteArray()).joinToString(""){"%02x".format(it)}
  return File(root,"lecture-study").listFiles()?.any{it.name.startsWith("$hash-")}==true
 }
 /** Only selected-calendar matches are passed in. Missing permission/source evidence means preserve, not guess. */
 fun run(ctx:Context,repo:Repository,matches:List<CalendarRuleMatch>):Counts=synchronized(repo){
  fun counts()=repo.lectures.value.groupingBy{it.courseId}.eachCount()
  val before=counts();var removed=0
  val prefs=ctx.getSharedPreferences("calendar_import_links",Context.MODE_PRIVATE)
  prefs.all.filterKeys{it.startsWith("link:")}.forEach{(key,raw)->
   val link=runCatching{JSONObject(raw as String)}.getOrNull() ?: return@forEach
   val l=repo.lecture(link.optString("lecture")) ?: return@forEach
   val match=matches.firstOrNull{it.event.key==key.removePrefix("link:")&&it.fingerprint==link.optString("fingerprint")} ?: return@forEach
   if(!eligible(l,link,match)||hasFiles(ctx,l))return@forEach
   // Recoverable private backup BEFORE removing the row. No audio/material files are ever deleted.
   val backup=File(ctx.filesDir,"migration-backups/calendar-drafts-v1/${l.id}.json");backup.parentFile!!.mkdirs()
   val tmp=File(backup.path+".tmp");tmp.writeText(JSONObject().put("lecture",l.toJson()).put("linkKey",key).put("link",link).toString());check(tmp.renameTo(backup))
   if(repo.removeCalendarPlaceholder(l)){prefs.edit().remove(key).commit();removed++}
  }
  Counts(before,counts(),removed)
 }
}
