package il.ac.tau.lecturesummarizer.calendar

import android.content.Context
import il.ac.tau.lecturesummarizer.data.*
import java.security.MessageDigest

/** Connection is permission + a device account, not an app OAuth flag. Metadata only. */
object CalendarConnection {
    fun connected(granted:Boolean,selected:Set<Long>,available:Set<Long>) = granted && (selected.isNotEmpty() || available.isNotEmpty())
    fun connected(ctx:Context):Boolean = connected(CalendarResolver.hasPermission(ctx),Settings(ctx).calendarIds,CalendarResolver.listCalendars(ctx).map{it.id}.toSet())
}

object EventSelection {
    fun filter(events:List<CalendarImportEvent>,query:String)=events.filter{CalendarRules.norm(query) in CalendarRules.norm(it.title+" "+it.snippet)}
    fun selectVisible(selected:Set<String>,visible:List<CalendarImportEvent>)=selected+visible.map{it.key}
}

/** One explicit import connects the old picker to the SAME sources/rules/lecture store as Home and mappings. */
object CalendarSelectionImporter {
    data class Result(val courses:Int,val events:Int)
    fun import(ctx:Context,repo:Repository,sources:List<CalendarSource>,events:List<CalendarImportEvent>):Result {
        require(events.isNotEmpty() && sources.isNotEmpty())
        require(events.all{e->e.title.isNotBlank() && sources.any{it.id==e.calendarId}})
        val store=CalendarRuleStore(ctx,repo)
        val before=store.rules()
        val generated=events.distinctBy{it.calendarId to CalendarRules.norm(it.title)}.associate{e->
            val parsed=TauEventParser.parse(CalEvent(e.title,e.snippet,null,e.start,e.end,null,e.allDay),requireClassHint=false)
            val course=repo.findOrCreateCourse(parsed?.courseName ?: e.title,parsed?.courseNumber)
            val hash=MessageDigest.getInstance("SHA-256").digest(CalendarRules.norm(e.title).toByteArray()).joinToString(""){"%02x".format(it)}
            val key="selection:${e.calendarId}:$hash"
            (e.calendarId to CalendarRules.norm(e.title)) to CalendarImportRule(key,e.title,enabled=true,title=e.title,targetCourse=course.name,calendars=setOf(e.calendarId),targetCourseId=course.id,exactTitle=true)
        }
        // The picker is the explicit active source selection: deselection must revoke future reads.
        store.sources(sources.distinctBy{it.id})
        val ids=generated.values.map{it.id}.toSet()
        store.saveRules(generated.values.toList()+before.filterNot{it.id in ids})
        Settings(ctx).apply{calendarIds=store.sources().map{it.id}.toSet();calendarRulesEnabled=true}
        val applier=CalendarRuleApplier(ctx,repo)
        val approved=CalendarRules.preview(generated.values.toList(),events)
        approved.forEach{applier.apply(it)}
        store.pending(store.pending().filterNot{applier.current(it)})
        return Result(generated.values.map{it.targetCourseId}.distinct().size,approved.size)
    }
}
