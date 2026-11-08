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
        CalendarDraftCleanup.run(app,app.repo,CalendarDraftCleanup.proofs(rules,result.events))
        val applier=CalendarRuleApplier(app)
        val pending=all.filterNot{store.dismissed(it) || applier.current(it)}
        // Refuse a stale background scan if the user changed source/rule selection while it was running.
        if(store.sources()!=sources || store.rules()!=rules)return@withContext Scan(store.pending(),true)
        store.pending(pending);store.prefs.edit().putLong("lastScan",System.currentTimeMillis()).putBoolean("scanError",false).apply()
        Scan(pending,result.truncated)
    }
}

/** Approval enables source watching only; a calendar occurrence is never a lecture. */
class CalendarRuleApplier(private val ctx:Context,private val repo:Repository) {
    constructor(app:LectureApp):this(app,app.repo)
    companion object{private val LOCK=Any()}
    private val p=ctx.getSharedPreferences("calendar_import_links",Context.MODE_PRIVATE)
    data class Undo(val key:String,val previousLink:String?,val afterLink:String)
    fun managedIds()=p.all.filterKeys{it.startsWith("link:")}.values.mapNotNull{runCatching{JSONObject(it as String).getString("lecture")}.getOrNull()}.toSet()
    private fun link(key:String)=p.getString("link:$key",null)?.let{runCatching{JSONObject(it)}.getOrNull()}
    fun current(m:CalendarRuleMatch)=link(m.event.key)?.let{it.optString("fingerprint")==m.fingerprint}==true
    fun apply(m:CalendarRuleMatch):Undo? = synchronized(LOCK) {
        if(current(m))return null
        require(m.rule.targetCourse.isNotBlank())
        val prior=p.getString("link:${m.event.key}",null)
        val after=JSONObject().put("fingerprint",m.fingerprint).put("title",m.event.title).put("start",m.event.start).put("watchOnly",true).toString()
        p.edit().putString("link:${m.event.key}",after).commit()
        CalendarRuleStore(ctx,repo).undoSuppression(m,false)
        Undo(m.event.key,prior,after)
    }
    fun undo(u:Undo):Boolean = synchronized(LOCK) {
        if(p.getString("link:${u.key}",null)!=u.afterLink)return false
        p.edit().apply{if(u.previousLink==null)remove("link:${u.key}")else putString("link:${u.key}",u.previousLink)}.commit()
        true
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
