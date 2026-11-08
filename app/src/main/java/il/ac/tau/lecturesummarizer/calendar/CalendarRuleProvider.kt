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
        // Read master metadata only in the explicitly selected calendars. Instances may be moved exceptions.
        data class Master(val title:String,val rrule:String,val rdate:String,val start:Long,val zone:String)
        val masters=mutableMapOf<Pair<Long,Long>,Master>()
        ctx.contentResolver.query(C.Events.CONTENT_URI,arrayOf(C.Events.CALENDAR_ID,C.Events._ID,C.Events.TITLE,C.Events.RRULE,C.Events.RDATE,C.Events.DTSTART,C.Events.EVENT_TIMEZONE),"${C.Events.CALENDAR_ID} IN (${chosen.joinToString(","){"?"}}) AND (${C.Events.RRULE} IS NOT NULL OR ${C.Events.RDATE} IS NOT NULL)",chosen.map{it.id.toString()}.toTypedArray(),null)?.use{c->while(c.moveToNext()){
            val id=c.getLong(0);val rr=c.getString(3).orEmpty();val rd=c.getString(4).orEmpty();if(rr.isBlank() && rd.isBlank())continue;masters[id to c.getLong(1)]=Master(c.getString(2).orEmpty(),rr,rd,c.getLong(5),c.getString(6)?.takeIf{runCatching{ZoneId.of(it)}.isSuccess} ?: byId.getValue(id).zone)
        }}
        val out=mutableListOf<CalendarImportEvent>();var truncated=false
        val selection="${C.Instances.CALENDAR_ID} IN (${chosen.joinToString(","){"?"}}) AND (${C.Instances.STATUS} IS NULL OR ${C.Instances.STATUS} != ?)"
        val args=chosen.map{it.id.toString()}+C.Events.STATUS_CANCELED.toString()
        ctx.contentResolver.query(uri.build(),projection,selection,args.toTypedArray(),"${C.Instances.BEGIN} ASC")?.use{c->while(c.moveToNext()){
            if(out.size>=1000){truncated=true;break}
            val calendar=c.getLong(0);val event=c.getLong(1);val begin=c.getLong(4);val finish=c.getLong(5)
            if(finish<=begin)continue
            val original=if(c.isNull(7))event else c.getLong(7)
            val master=masters[calendar to original]
            val recurring=!c.isNull(7) || master!=null || !c.getString(9).isNullOrBlank() || !c.getString(10).isNullOrBlank()
            val occurrence=if(!c.isNull(8))c.getLong(8)else if(recurring)begin else 0
            out+=CalendarImportEvent("$calendar:$original:$occurrence",calendar,event,c.getString(2).orEmpty(),c.getString(3).orEmpty().take(700),begin,finish,c.getInt(6)==1,master?.zone ?: byId.getValue(calendar).zone,recurring,if(recurring)original else null,master?.rrule ?: c.getString(9).orEmpty(),master?.rdate ?: c.getString(10).orEmpty(),master?.title,master?.start ?: occurrence.takeIf{it!=0L})
        }}
        val store=CalendarRuleStore(ctx);val old=store.rules();val migrated=CalendarSeriesSelection.migrateRules(old,out);if(migrated!=old)store.saveRules(migrated)
        return Result(out,truncated)
    }
}
