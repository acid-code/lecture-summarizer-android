package il.ac.tau.lecturesummarizer.calendar

import java.time.*

data class CalendarSeries(val key:String,val title:String,val calendarId:Long,val rule:String,val start:Long,val zone:String,val allDay:Boolean,val instances:List<CalendarImportEvent>) {
 val interval:Int get()=rule.split(';').firstOrNull{it.startsWith("INTERVAL=",true)}?.substringAfter('=')?.toIntOrNull()?.coerceIn(1,365) ?: 1
 val frequency:String get()=rule.split(';').firstOrNull{it.startsWith("FREQ=",true)}?.substringAfter('=')?.uppercase().orEmpty()
 val days:List<DayOfWeek> get(){
  val map=mapOf("MO" to DayOfWeek.MONDAY,"TU" to DayOfWeek.TUESDAY,"WE" to DayOfWeek.WEDNESDAY,"TH" to DayOfWeek.THURSDAY,"FR" to DayOfWeek.FRIDAY,"SA" to DayOfWeek.SATURDAY,"SU" to DayOfWeek.SUNDAY)
  val fromRule=rule.split(';').firstOrNull{it.startsWith("BYDAY=",true)}?.substringAfter('=')?.split(',')?.mapNotNull{map[it.uppercase()]}?.distinct().orEmpty()
  return if(fromRule.isNotEmpty())fromRule else if(frequency=="WEEKLY")listOf(Instant.ofEpochMilli(start).atZone(runCatching{ZoneId.of(zone)}.getOrDefault(ZoneId.of("Asia/Jerusalem"))).dayOfWeek)else emptyList()
 }
}
object CalendarSeriesSelection {
 fun groups(events:List<CalendarImportEvent>):List<CalendarSeries> = events.filter{it.recurring && it.title.isNotBlank()}.groupBy{it.seriesKey!!}.map{(key,rows)->
  val sorted=rows.distinctBy{it.key}.sortedBy{it.start};val root=sorted.firstOrNull{it.recurrenceRule.isNotBlank() && it.eventId==it.seriesId} ?: sorted.first()
  CalendarSeries(key,root.seriesTitle?.takeIf{it.isNotBlank()} ?: root.title,root.calendarId,root.recurrenceRule,root.seriesStart ?: root.start,root.zone,root.allDay,sorted)
 }.sortedWith(compareBy<CalendarSeries>{CalendarRules.norm(it.title)}.thenBy{it.key})
 fun filter(groups:List<CalendarSeries>,query:String)=groups.filter{CalendarRules.norm(query) in CalendarRules.norm(it.title+" "+it.instances.joinToString(" "){e->e.snippet})}
 /** Restore old individual-instance keys to series; one-off keys are deliberately not selected. */
 fun migrateKeys(keys:Set<String>,groups:List<CalendarSeries>)=groups.filter{it.key in keys || it.instances.any{e->e.key in keys}}.map{it.key}.toSet()
 fun instances(keys:Set<String>,events:List<CalendarImportEvent>):List<CalendarImportEvent>{val all=groups(events);val wanted=migrateKeys(keys,all);return all.filter{it.key in wanted}.flatMap{it.instances}}
 fun pickerRule(rule:CalendarImportRule)=rule.id.startsWith("selection:") || rule.id.startsWith("series-selection:")
 /** Migrate only rules created by the old picker, not custom user rules or unmappable history. */
 fun migrateRules(rules:List<CalendarImportRule>,events:List<CalendarImportEvent>):List<CalendarImportRule>{
  val series=groups(events)
  return rules.map{r->if(!pickerRule(r) || r.seriesKeys.isNotEmpty() || !r.exactTitle)r else {
   val matches=series.filter{(r.calendars.isEmpty() || it.calendarId in r.calendars) && (CalendarRules.norm(it.title)==CalendarRules.norm(r.title) || it.instances.any{e->CalendarRules.norm(e.title)==CalendarRules.norm(r.title)})}
   if(matches.isEmpty())r else r.copy(seriesKeys=matches.map{it.key}.toSet(),recurringOnly=true,title="",exactTitle=false)
  }}
 }
 fun savedKeys(rules:List<CalendarImportRule>,events:List<CalendarImportEvent>):Set<String>{val live=groups(events).map{it.key}.toSet();return migrateRules(rules,events).filter{pickerRule(it)&&it.enabled}.flatMap{it.seriesKeys}.filter{it in live}.toSet()}
}
