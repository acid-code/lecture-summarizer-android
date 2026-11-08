package il.ac.tau.lecturesummarizer.sources

import java.time.*
import kotlin.math.abs
import il.ac.tau.lecturesummarizer.autosync.RecordingMatcher

data class SourceConfig(val providerId:String,val baseUrl:String,val courseId:Long?=null,val folderId:String="",val sessionId:String="",
    val matchDate:Boolean=true,val matchTime:Boolean=true,val matchTitle:Boolean=true,val numberPattern:String=DEFAULT_NUMBER_PATTERN,val toleranceMinutes:Int=120,val legacy:Boolean=false,val options:Map<String,String> = emptyMap()) {
    companion object {const val DEFAULT_NUMBER_PATTERN="(?i)(?:lecture|lesson|ppt|שיעור|הרצאה|תרגול|תרגיל)[ _.-]*0*(\\d{1,3})"}
    val valid get()=providerId.isNotBlank() && runCatching{val u=java.net.URI(baseUrl);u.scheme=="https" && !u.host.isNullOrBlank() && u.userInfo==null && u.rawQuery==null && u.rawFragment==null && u.port in -1..65535}.getOrDefault(false) && toleranceMinutes in 0..1440 && SourceMatcher.safePattern(numberPattern)
}
data class SourceEvent(val title:String,val start:Long,val end:Long,val zone:String="Asia/Jerusalem")
data class SourceItem(val providerId:String,val id:String,val title:String,val url:String,val start:Long?=null,val material:Boolean=false)
data class SourceMatch(val item:SourceItem,val score:Int,val reasons:List<String>)
data class SourceListing(val items:List<SourceItem>,val partial:Boolean=false)

/** Registry extension point: a provider owns discovery and its matching strategy, not calendar parsing. */
interface DataSource {
    val providerId:String
    val matcher:SourceItemMatcher get()=SourceMatcher
    suspend fun items(config:SourceConfig):SourceListing
}

interface SourceItemMatcher {fun rank(event:SourceEvent,config:SourceConfig,items:List<SourceItem>):List<SourceMatch>}

object SourceMatcher:SourceItemMatcher {
    fun safePattern(p:String)=p.length<=200 && !Regex("\\)[*+{]|\\\\[1-9]|\\(\\?[=!<]").containsMatchIn(p) && runCatching{Regex(p);true}.getOrDefault(false)
    fun number(title:String,pattern:String):Int?=if(pattern.isBlank() || !safePattern(pattern))null else runCatching{Regex(pattern).find(title.take(2000))?.groupValues?.getOrNull(1)?.toIntOrNull()}.getOrNull()
    fun tokens(title:String)=Regex("[\\p{L}\\p{N}]{3,}").findAll(title.lowercase()).map{it.value}.filter{it !in setOf("lecture","lesson","the","and","שיעור","הרצאה","תרגול")}.toSet()
    override fun rank(event:SourceEvent,config:SourceConfig,items:List<SourceItem>):List<SourceMatch> {
        if(!config.valid)return emptyList()
        val zone=runCatching{ZoneId.of(event.zone)}.getOrDefault(ZoneId.of("Asia/Jerusalem"))
        val day=Instant.ofEpochMilli(event.start).atZone(zone).toLocalDate();val want=number(event.title,config.numberPattern)
        return items.filter{it.providerId==config.providerId}.mapNotNull{item->
            val reasons=mutableListOf("course_mapping");var score=0
            val titleDate=RecordingMatcher.datesIn(item.title).any{it.m==day.monthValue && it.d==day.dayOfMonth && (it.y==null || it.y==day.year)}
            val sameDay=item.start?.let{Instant.ofEpochMilli(it).atZone(zone).toLocalDate()==day}==true
            if(config.matchDate && !sameDay && !titleDate)return@mapNotNull null
            if(config.matchDate && (sameDay || titleDate)){score+=50;reasons+="date"}
            if(config.matchTime && item.start!=null){if(abs(item.start-event.start)>config.toleranceMinutes*60_000L)return@mapNotNull null;score+=30;reasons+="time"}
            val got=number(item.title,config.numberPattern)
            if(want!=null && got!=null){if(want!=got)return@mapNotNull null;score+=40;reasons+="number"}
            val shared=tokens(item.title).intersect(tokens(event.title))
            if(config.matchTitle && shared.isNotEmpty()){score+=minOf(shared.size,10)*5;reasons+="tokens:${shared.size}"}
            // Disabled date/time checks must not turn every item in a folder into a match.
            if(score==0)return@mapNotNull null
            SourceMatch(item,score,reasons)
        }.sortedWith(compareByDescending<SourceMatch>{it.score}.thenBy{it.item.title})
    }
    fun clearRecording(matches:List<SourceMatch>):SourceItem? {
        val candidates=matches.filterNot{it.item.material};val first=candidates.firstOrNull() ?: return null
        return first.item.takeIf{candidates.size==1 || first.score-candidates[1].score>=10}
    }
}
