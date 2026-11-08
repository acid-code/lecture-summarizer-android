package il.ac.tau.lecturesummarizer.sources

import android.content.Context
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.calendar.ClassKind
import il.ac.tau.lecturesummarizer.data.Course
import il.ac.tau.lecturesummarizer.moodle.*
import org.json.*

class SourceMappingStore(private val ctx:Context) {
    private val p=ctx.getSharedPreferences("course_source_mappings",Context.MODE_PRIVATE)
    fun get(courseId:String):List<SourceConfig>?=p.getString("course:$courseId",null)?.let{raw->runCatching{val a=JSONArray(raw);(0 until a.length()).map{i->val o=a.getJSONObject(i);SourceConfig(o.getString("provider"),o.getString("base"),if(o.isNull("course"))null else o.getLong("course"),o.optString("folder"),o.optString("session"),o.optBoolean("date",true),o.optBoolean("time",true),o.optBoolean("title",true),o.optString("number",SourceConfig.DEFAULT_NUMBER_PATTERN),o.optInt("tolerance",120),o.optBoolean("legacy",false),o.optJSONObject("options")?.let{obj->obj.keys().asSequence().associateWith{key->obj.getString(key)}} ?: emptyMap())}}.getOrNull()}
    fun save(courseId:String,configs:List<SourceConfig>){require(configs.all{it.valid});p.edit().putString("course:$courseId",JSONArray().apply{configs.forEach{c->put(JSONObject().put("provider",c.providerId).put("base",c.baseUrl).put("course",c.courseId ?: JSONObject.NULL).put("folder",c.folderId).put("session",c.sessionId).put("date",c.matchDate).put("time",c.matchTime).put("title",c.matchTitle).put("number",c.numberPattern).put("tolerance",c.toleranceMinutes).put("legacy",c.legacy).put("options",JSONObject(c.options)))}}.toString()).commit()}
    fun pin(eventKey:String,start:Long,item:SourceItem){p.edit().putString("event:$eventKey",JSONObject().put("start",start).put("provider",item.providerId).put("id",item.id).put("title",item.title).put("url",item.url).put("time",item.start ?: JSONObject.NULL).toString()).commit()}
    fun pinned(eventKey:String,start:Long):SourceItem?=p.getString("event:$eventKey",null)?.let{runCatching{val o=JSONObject(it);if(o.getLong("start")!=start)null else SourceItem(o.getString("provider"),o.getString("id"),o.getString("title"),o.getString("url"),if(o.isNull("time"))null else o.getLong("time"))}.getOrNull()}
    /** Existing manually selected Moodle IDs become editable defaults. No network call or remapping. */
    fun migrate(course:Course):List<SourceConfig> {
        get(course.id)?.let{return it}
        val store=MoodleStore(ctx)
        val old=ClassKind.entries.firstNotNullOfOrNull{k->store.mappedCourse("${course.courseNumber ?: course.id}:${k.name}:manual") ?: store.mappedCourse("${course.courseNumber ?: course.id}:${k.name}")}
        val defaults=listOf(SourceConfig("moodle",store.baseUrl,old,legacy=true),SourceConfig("panopto",store.baseUrl,old,legacy=true))
        save(course.id,defaults);return defaults
    }
    fun forName(app:LectureApp,name:String):List<SourceConfig>?=app.repo.courses.value.firstOrNull{it.name.trim()==name.trim()}?.let{get(it.id)}
}
