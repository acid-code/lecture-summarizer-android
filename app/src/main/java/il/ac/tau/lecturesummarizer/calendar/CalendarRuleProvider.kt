package il.ac.tau.lecturesummarizer.calendar

import android.content.Context
import android.content.ContentUris
import android.provider.CalendarContract as C
import java.time.*

/** Calendar metadata may populate the source picker; event queries always require explicit chosen IDs/accounts. */
object CalendarRuleProvider {
    fun sources(ctx:Context):List<CalendarSource> {
        if(!CalendarResolver.hasPermission(ctx))return emptyList()
        val out=mutableListOf<CalendarSource>()
        ctx.contentResolver.query(C.Calendars.CONTENT_URI,arrayOf(C.Calendars._ID,C.Calendars.CALENDAR_DISPLAY_NAME,C.Calendars.ACCOUNT_NAME,C.Calendars.ACCOUNT_TYPE,C.Calendars.CALENDAR_TIME_ZONE),null,null,"${C.Calendars.ACCOUNT_NAME} ASC")?.use{c->while(c.moveToNext())out+=CalendarSource(c.getLong(0),c.getString(1).orEmpty(),c.getString(2).orEmpty(),c.getString(3).orEmpty(),c.getString(4)?.takeIf{it.isNotBlank()} ?: "Asia/Jerusalem")}
        return out
    }
    data class Result(val events:List<CalendarImportEvent>,val truncated:Boolean)
    fun read(ctx:Context,selected:List<CalendarSource>,now:Long=System.currentTimeMillis()):Result {
        check(CalendarResolver.hasPermission(ctx))
        if(selected.isEmpty())return Result(emptyList(),false)
        // Prevent a provider-recycled calendar ID from reading another account's events.
        val live=sources(ctx);val chosen=selected.filter{s->live.any{it.id==s.id && it.accountKey==s.accountKey}}
        if(chosen.isEmpty())return Result(emptyList(),false)
        val byId=chosen.associateBy{it.id};val zone=ZoneId.of("Asia/Jerusalem")
        val (start,end)=CalendarCourses.currentTerm(Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
        val uri=C.Instances.CONTENT_URI.buildUpon();ContentUris.appendId(uri,start.atStartOfDay(zone).toInstant().toEpochMilli());ContentUris.appendId(uri,end.atStartOfDay(zone).toInstant().toEpochMilli())
        val projection=arrayOf(C.Instances.CALENDAR_ID,C.Instances.EVENT_ID,C.Instances.TITLE,C.Instances.DESCRIPTION,C.Instances.BEGIN,C.Instances.END,C.Instances.ALL_DAY,C.Events.ORIGINAL_ID,C.Events.ORIGINAL_INSTANCE_TIME,C.Events.RRULE,C.Events.RDATE)
        val out=mutableListOf<CalendarImportEvent>();var truncated=false
        val selection="${C.Instances.CALENDAR_ID} IN (${chosen.joinToString(","){"?"}}) AND (${C.Instances.STATUS} IS NULL OR ${C.Instances.STATUS} != ?)"
        val args=chosen.map{it.id.toString()}+C.Events.STATUS_CANCELED.toString()
        ctx.contentResolver.query(uri.build(),projection,selection,args.toTypedArray(),"${C.Instances.BEGIN} ASC")?.use{c->while(c.moveToNext()){
            if(out.size>=1000){truncated=true;break}
            val calendar=c.getLong(0);val event=c.getLong(1);val begin=c.getLong(4);val finish=c.getLong(5)
            if(finish<=begin)continue
            val original=if(c.isNull(7))event else c.getLong(7)
            val occurrence=if(!c.isNull(8))c.getLong(8)else if(!c.getString(9).isNullOrBlank() || !c.getString(10).isNullOrBlank())begin else 0
            out+=CalendarImportEvent("$calendar:$original:$occurrence",calendar,event,c.getString(2).orEmpty(),c.getString(3).orEmpty().take(700),begin,finish,c.getInt(6)==1,byId.getValue(calendar).zone,occurrence!=0L)
        }}
        return Result(out,truncated)
    }
}
