package il.ac.tau.lecturesummarizer.calendar

import java.util.Locale
import java.security.MessageDigest

data class CalendarImportRule(val id:String,val name:String,val enabled:Boolean=false,val title:String="",
    val include:List<String> = emptyList(),val exclude:List<String> = emptyList(),val courseFilter:String="",val targetCourse:String="",val calendars:Set<Long> = emptySet(),val recurringOnly:Boolean=false,val targetCourseId:String?=null,val exactTitle:Boolean=false) {
    val valid get()=name.isNotBlank() && targetCourse.isNotBlank() && (title.isNotBlank() || include.any{it.isNotBlank()} || courseFilter.isNotBlank() || recurringOnly)
}
data class CalendarSource(val id:Long,val name:String,val account:String,val accountType:String,val zone:String) {
    val accountKey get()="$accountType\n$account"
}
data class CalendarImportEvent(val key:String,val calendarId:Long,val eventId:Long,val title:String,val snippet:String,val start:Long,val end:Long,val allDay:Boolean,val zone:String,val recurring:Boolean=false)
data class CalendarRuleMatch(val event:CalendarImportEvent,val rule:CalendarImportRule) {
    val fingerprint get()=MessageDigest.getInstance("SHA-256").digest(listOf(event.title,event.start.toString(),event.end.toString(),(rule.targetCourseId ?: rule.targetCourse.trim())).joinToString("\n").toByteArray()).joinToString(""){"%02x".format(it)}
}
object CalendarRules {
    fun norm(s:String)=s.lowercase(Locale.ROOT).trim().replace(Regex("\\s+")," ")
    /** Bounded glob matching: '*' and '?' are supported without running user-supplied regular expressions. */
    fun titleMatches(pattern:String,title:String):Boolean {
        val p="*"+norm(pattern).take(128)+"*";val text=norm(title).take(1000)
        var dp=BooleanArray(text.length+1);dp[0]=true
        for(ch in p){val next=BooleanArray(text.length+1);if(ch=='*')next[0]=dp[0]
            for(i in 1..text.length)next[i]=if(ch=='*')next[i-1] || dp[i]else dp[i-1] && (ch=='?' || ch==text[i-1]);dp=next}
        return dp[text.length]
    }
    fun firstMatch(rules:List<CalendarImportRule>,event:CalendarImportEvent):CalendarImportRule? {
        val body=norm(event.title+" "+event.snippet)
        return rules.firstOrNull{r->r.enabled && r.valid && (r.calendars.isEmpty() || event.calendarId in r.calendars) &&
            r.exclude.none{it.isNotBlank() && norm(it) in body} &&
            (!r.recurringOnly || event.recurring) && (r.title.isBlank() || if(r.exactTitle)norm(r.title)==norm(event.title) else titleMatches(r.title,event.title)) &&
            (r.include.isEmpty() || r.include.any{it.isNotBlank() && norm(it) in body}) &&
            (r.courseFilter.isBlank() || norm(r.courseFilter) in body)}
    }
    /** Same instance or normalized title/time is one preview; rules are evaluated in the visible order. */
    fun preview(rules:List<CalendarImportRule>,events:List<CalendarImportEvent>):List<CalendarRuleMatch> {
        val seen=mutableSetOf<String>();val times=mutableSetOf<Pair<String,Long>>()
        return events.sortedWith(compareBy<CalendarImportEvent>{it.start}.thenBy{it.calendarId}).mapNotNull{e->
            val rule=firstMatch(rules,e) ?: return@mapNotNull null
            if(!seen.add(e.key) || !times.add(norm(e.title) to e.start))null else CalendarRuleMatch(e,rule)
        }
    }
}
