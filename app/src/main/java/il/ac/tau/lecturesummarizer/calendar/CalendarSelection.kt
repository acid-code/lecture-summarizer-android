package il.ac.tau.lecturesummarizer.calendar

import android.content.Context
import il.ac.tau.lecturesummarizer.data.*

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
        val before=CalendarSeriesSelection.migrateRules(store.rules(),events)
        val series=CalendarSeriesSelection.groups(events);require(series.isNotEmpty() && events.all{it.recurring})
        val generated=series.map{s->
            val e=s.instances.first()
            val parsed=TauEventParser.parse(CalEvent(s.title,e.snippet,null,e.start,e.end,null,e.allDay),requireClassHint=false)
            val previous=before.firstOrNull{CalendarSeriesSelection.pickerRule(it)&&s.key in it.seriesKeys}
            val course=previous?.targetCourseId?.let{repo.course(it)} ?: repo.findOrCreateCourse(parsed?.courseName ?: s.title,parsed?.courseNumber)
            CalendarImportRule("series-selection:${s.key}",s.title,enabled=true,targetCourse=course.name,calendars=setOf(s.calendarId),targetCourseId=course.id,recurringOnly=true,seriesKeys=setOf(s.key))
        }
        // The picker is the explicit active source selection: deselection must revoke future reads.
        store.sources(sources.distinctBy{it.id})
        val ids=generated.map{it.id}.toSet()
        store.saveRules(generated+before.filterNot{it.id in ids}.map{r->if(CalendarSeriesSelection.pickerRule(r) && r.calendars.any{id->sources.any{it.id==id}})r.copy(enabled=false)else r})
        Settings(ctx).apply{calendarIds=store.sources().map{it.id}.toSet();calendarRulesEnabled=true}
        val applier=CalendarRuleApplier(ctx,repo)
        val approved=CalendarRules.preview(generated,events)
        approved.forEach{applier.apply(it)}
        store.pending(store.pending().filterNot{applier.current(it)})
        return Result(generated.map{it.targetCourseId}.distinct().size,approved.size)
    }
}
