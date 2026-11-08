package il.ac.tau.lecturesummarizer.calendar

import android.content.Context
import org.json.*

/** Private device cache of rules and matched event metadata. No credentials and no mailbox access. */
class CalendarRuleStore(private val ctx:Context,private val repo:il.ac.tau.lecturesummarizer.data.Repository?=(ctx.applicationContext as? il.ac.tau.lecturesummarizer.LectureApp)?.repo) {
    val prefs=ctx.getSharedPreferences("calendar_import_rules",Context.MODE_PRIVATE)
    private fun array(key:String)=runCatching{JSONArray(prefs.getString(key,"[]"))}.getOrDefault(JSONArray())
    private fun strings(a:JSONArray?)=if(a==null)emptyList()else (0 until a.length()).map{a.getString(it)}
    fun rules():List<CalendarImportRule> = runCatching {val a=array("rules");(0 until a.length()).map{i->val o=a.getJSONObject(i)
        CalendarImportRule(o.getString("id"),o.getString("name"),o.optBoolean("enabled",false),o.optString("title"),strings(o.optJSONArray("include")),strings(o.optJSONArray("exclude")),o.optString("filter"),o.optString("course"),strings(o.optJSONArray("calendars")).mapNotNull{it.toLongOrNull()}.toSet(),o.optBoolean("recurring",false),o.optString("targetId").takeIf{it.isNotBlank()},o.optBoolean("exactTitle",false))}}.getOrDefault(emptyList()).map{r->if(r.targetCourseId==null || repo==null)r else repo?.course(r.targetCourseId)?.let{r.copy(targetCourse=it.name)} ?: r.copy(enabled=false)}
    fun saveRules(rules:List<CalendarImportRule>){prefs.edit().putString("rules",JSONArray().apply{rules.forEach{r->put(JSONObject().put("id",r.id).put("name",r.name).put("exactTitle",r.exactTitle).put("enabled",r.enabled).put("title",r.title).put("include",JSONArray(r.include)).put("exclude",JSONArray(r.exclude)).put("filter",r.courseFilter).put("course",r.targetCourse).put("targetId",r.targetCourseId.orEmpty()).put("recurring",r.recurringOnly).put("calendars",JSONArray(r.calendars.map{it.toString()})))}}.toString()).commit()}
    fun sources():List<CalendarSource> = runCatching{val a=array("sources");(0 until a.length()).map{i->val o=a.getJSONObject(i);CalendarSource(o.getLong("id"),o.getString("name"),o.getString("account"),o.getString("type"),o.getString("zone"))}}.getOrDefault(emptyList())
    fun sources(v:List<CalendarSource>){prefs.edit().putBoolean("configured",true).putString("sources",JSONArray().apply{v.forEach{c->put(JSONObject().put("id",c.id).put("name",c.name).put("account",c.account).put("type",c.accountType).put("zone",c.zone))}}.toString()).commit()}
    var automatic:Boolean get()=prefs.getBoolean("automatic",false);set(v){prefs.edit().putBoolean("automatic",v).apply()}
    var autoApprove:Boolean get()=prefs.getBoolean("autoApprove",false);set(v){prefs.edit().putBoolean("autoApprove",v).apply()}
    fun pending():List<CalendarRuleMatch> = runCatching{val a=array("pending");(0 until a.length()).map{i->val o=a.getJSONObject(i);val e=o.getJSONObject("event");val r=o.getJSONObject("rule");CalendarRuleMatch(CalendarImportEvent(e.getString("key"),e.getLong("calendar"),e.getLong("id"),e.getString("title"),e.getString("snippet"),e.getLong("start"),e.getLong("end"),e.getBoolean("allDay"),e.getString("zone"),e.optBoolean("recurring",false)),CalendarImportRule(r.getString("id"),r.getString("name"),enabled=true,title=e.getString("title"),targetCourse=r.getString("course"),targetCourseId=r.optString("targetId").takeIf{it.isNotBlank()}))}}.getOrDefault(emptyList())
    fun pending(matches:List<CalendarRuleMatch>){prefs.edit().putString("pending",JSONArray().apply{matches.take(1000).forEach{m->val e=m.event;put(JSONObject().put("event",JSONObject().put("key",e.key).put("calendar",e.calendarId).put("id",e.eventId).put("title",e.title).put("snippet",e.snippet.take(700)).put("start",e.start).put("end",e.end).put("allDay",e.allDay).put("zone",e.zone).put("recurring",e.recurring)).put("rule",JSONObject().put("id",m.rule.id).put("name",m.rule.name).put("course",m.rule.targetCourse).put("targetId",m.rule.targetCourseId.orEmpty())))}}.toString()).commit()}
    fun undone(m:CalendarRuleMatch)=prefs.getString("undo:${m.event.key}",null)==m.fingerprint
    fun undoSuppression(m:CalendarRuleMatch,on:Boolean){prefs.edit().apply{if(on)putString("undo:${m.event.key}",m.fingerprint)else remove("undo:${m.event.key}")}.apply()}
    fun dismissed(m:CalendarRuleMatch)=prefs.getString("dismiss:${m.event.key}",null)==m.fingerprint
    fun dismiss(m:CalendarRuleMatch,on:Boolean){prefs.edit().apply{if(on)putString("dismiss:${m.event.key}",m.fingerprint)else remove("dismiss:${m.event.key}")}.commit()}
}
