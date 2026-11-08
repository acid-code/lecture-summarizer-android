package il.ac.tau.lecturesummarizer.calendar

import android.content.Context
import androidx.work.*
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object CalendarRuleAutomation {
    data class Scan(val matches:List<CalendarRuleMatch>,val truncated:Boolean)
    suspend fun scan(app:LectureApp):Scan=withContext(Dispatchers.IO){
        val store=CalendarRuleStore(app);val sources=store.sources();val rules=store.rules()
        if(sources.isEmpty() || rules.none{it.enabled && it.valid}){store.pending(emptyList());return@withContext Scan(emptyList(),false)}
        val result=CalendarRuleProvider.read(app,sources)
        val all=CalendarRules.preview(rules,result.events)
        val applier=CalendarRuleApplier(app)
        val pending=all.filterNot{store.dismissed(it) || applier.current(it)}
        // Refuse a stale background scan if the user changed source/rule selection while it was running.
        if(store.sources()!=sources || store.rules()!=rules)return@withContext Scan(store.pending(),true)
        store.pending(pending);store.prefs.edit().putLong("lastScan",System.currentTimeMillis()).putBoolean("scanError",false).apply()
        Scan(pending,result.truncated)
    }
}

/** Imports/updates LOCAL lecture placeholders only. The source calendar is read-only and never modified. */
class CalendarRuleApplier(private val app:LectureApp) {
    companion object{private val LOCK=Any()}
    private val p=app.getSharedPreferences("calendar_import_links",Context.MODE_PRIVATE)
    data class Undo(val key:String,val lectureId:String,val before:Lecture?,val after:Lecture,val previousLink:String?,val createdCourse:Course?=null)
    fun managedIds()=p.all.filterKeys{it.startsWith("link:")}.values.mapNotNull{runCatching{JSONObject(it as String).getString("lecture")}.getOrNull()}.toSet()
    private fun link(key:String)=p.getString("link:$key",null)?.let{runCatching{JSONObject(it)}.getOrNull()}
    fun current(m:CalendarRuleMatch)=link(m.event.key)?.let{it.optString("fingerprint")==m.fingerprint && app.repo.lecture(it.optString("lecture"))!=null}==true
    fun apply(m:CalendarRuleMatch):Undo? = synchronized(LOCK) {
        if(current(m))return null
        require(m.rule.targetCourse.isNotBlank())
        val prior=p.getString("link:${m.event.key}",null);val old=link(m.event.key)
        val before=old?.optString("lecture")?.let{app.repo.lecture(it)}
        // User recordings, manually edited titles and processing work are not silently overwritten.
        if(before!=null)check(before.state==LectureState.DRAFT && before.segments.isEmpty() && before.remoteId==null &&
            before.title==old?.optString("title") && before.recordedAtMs==old?.optLong("start")){"calendar_local_edit_conflict"}
        val beforeCourseIds=app.repo.courses.value.map{it.id}.toSet()
        val course=m.rule.targetCourseId?.let{app.repo.course(it) ?: error("destination_course_deleted")} ?: app.repo.findOrCreateCourse(m.rule.targetCourse.trim(),null)
        val l=before ?: app.repo.addLecture(course.id,m.event.title)
        val after=l.copy(courseId=course.id,title=m.event.title,recordedAtMs=m.event.start)
        app.repo.updateLecture(l.id){after}
        p.edit().putString("link:${m.event.key}",JSONObject().put("lecture",l.id).put("fingerprint",m.fingerprint).put("title",after.title).put("start",after.recordedAtMs).toString()).commit()
        CalendarRuleStore(app).undoSuppression(m,false)
        return Undo(m.event.key,l.id,before,after,prior,course.takeIf{it.id !in beforeCourseIds})
    }
    fun undo(u:Undo):Boolean = synchronized(LOCK) {
        if(app.repo.lecture(u.lectureId)!=u.after)return false
        if(u.before==null)app.repo.deleteLecture(u.lectureId)else app.repo.updateLecture(u.lectureId){u.before}
        u.createdCourse?.let{c->if(app.repo.course(c.id)==c && app.repo.lectures.value.none{it.courseId==c.id})app.repo.deleteCourse(c.id)}
        p.edit().apply{if(u.previousLink==null)remove("link:${u.key}")else putString("link:${u.key}",u.previousLink)}.commit()
        return true
    }
}

class CalendarRuleWorker(ctx:Context,params:WorkerParameters):CoroutineWorker(ctx,params) {
    override suspend fun doWork():Result {
        val app=applicationContext as LectureApp;val store=CalendarRuleStore(app)
        if(!app.settings.calendarRulesEnabled || !store.automatic || !CalendarResolver.hasPermission(app))return Result.success()
        return try {
            val scan=CalendarRuleAutomation.scan(app)
            if(store.autoApprove && !scan.truncated)scan.matches.forEach{m->
                if(!store.automatic || !store.autoApprove || store.undone(m) || store.sources().none{it.id==m.event.calendarId})return@forEach
                val current=CalendarRules.firstMatch(store.rules(),m.event) ?: return@forEach
                if(CalendarRuleMatch(m.event,current).fingerprint!=m.fingerprint)return@forEach
                runCatching{CalendarRuleApplier(app).apply(m)} // Conflicts remain pending for explicit review.
            }
            if(store.autoApprove)store.pending(scan.matches.filterNot{CalendarRuleApplier(app).current(it)})
            Result.success()
        }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){store.prefs.edit().putBoolean("scanError",true).apply();if(runAttemptCount<3)Result.retry()else Result.failure()}
    }
    companion object {
        fun schedule(ctx:Context,on:Boolean){val wm=WorkManager.getInstance(ctx);if(!on){wm.cancelUniqueWork("calendar-rule-pull");return};wm.enqueueUniquePeriodicWork("calendar-rule-pull",ExistingPeriodicWorkPolicy.UPDATE,PeriodicWorkRequestBuilder<CalendarRuleWorker>(30,TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build())}
    }
}
