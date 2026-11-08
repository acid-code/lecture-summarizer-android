package il.ac.tau.lecturesummarizer.slides

import java.util.Locale
import kotlin.math.ln
import kotlin.math.exp
import java.time.LocalDate

/** Deliberately conservative: dates and bare numbers are NOT lecture numbers. */
object SlideMatcher {
    data class Deck(val id: Long, val name: String, val text: String, val taughtOn: LocalDate? = null, val pages:List<String> = if(text.contains('\u000c'))text.split('\u000c')else emptyList())
    data class Match(val deck: Deck, val terms: List<String>, val score: Double)
    private val index = Regex("""(?i)(?:הרצאה|שיעור|lecture|lec|ppt|מצגת)[\s_#.:\-]*0*(\d{1,2})(?!\d|\s*[/.-]\s*\d)""")
    fun lectureNumber(title: String): Int? = index.find(title)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
    fun early(names: List<Pair<Long, String>>, recording: String): Long? {
        val n = lectureNumber(recording) ?: return null
        return names.filter { lectureNumber(it.second) == n }.singleOrNull()?.first
    }
    private val stop = ("the and with that this from have will which your are for was were into not can also these " +
        "lecture slide slides course introduction university tel aviv ppt pdf copyright www http " +
        "של את על זה לא הוא היא עם גם או יש אז אנחנו אתם כל מה אבל יותר כמו כדי כאן שם שיעור מצגת הרצאה מבוא").split(' ').toSet()
    fun keywords(text: String): Set<String> = Regex("""[\p{L}]{3,}""").findAll(text.lowercase(Locale.ROOT))
        .map { it.value }.filter { it !in stop }.take(80_000).toSet()
    /** Only explicit teaching dates are evidence. File IDs, order, upload/modified time are not. */
    fun explicitDate(name:String):LocalDate? = il.ac.tau.lecturesummarizer.autosync.RecordingMatcher.datesIn(name)
        .filter{it.y!=null}.mapNotNull{runCatching{LocalDate.of(it.y!!,it.m,it.d)}.getOrNull()}.distinct().singleOrNull()
    private fun script(term:String)=Character.UnicodeScript.of(term.codePointAt(0))
    fun rank(decks: List<Deck>, transcript: String, lectureDate:LocalDate?=null): List<Match> {
        val heard = keywords(transcript)
        val sets = decks.map { keywords(it.text) }
        val frequency=mutableMapOf<String,Int>()
        sets.forEach{terms->terms.forEach{frequency[it]=(frequency[it] ?: 0)+1}}
        val idf=frequency.mapValues{(_,count)->ln(1.0+decks.size.toDouble()/count)}
        val queryScripts=heard.groupingBy(::script).eachCount()
        return decks.mapIndexed { i, deck ->
            val all=sets[i]
            val terms = (all intersect heard).sortedWith(compareByDescending<String>{idf.getValue(it)}.thenBy{it})
            // Normalize by the deck's vocabulary mass, not absolute hits. A long appendix or
            // speaker-note transcript must not win just by containing more conversational words.
            // Balance independently evidenced scripts: verbose Hebrew notes cannot drown out
            // the Latin scientific anchors (and vice versa). This is script-neutral.
            val channels=all.groupBy(::script).filter{(lang,words)->words.size>=4 && (queryScripts[lang] ?: 0)>=4}
            val coverages=(if(channels.isEmpty())listOf(all.toList())else channels.values).map{words->
                val total=words.sumOf{idf.getValue(it)}
                val hits=words.filter{it in heard}.sumOf{idf.getValue(it)}
                if(total==0.0)0.0 else hits/(total+4.0*total/words.size.coerceAtLeast(1))
            }
            val coverage=if(coverages.isEmpty() || coverages.any{it<=0})0.0 else exp(coverages.sumOf{ln(it)}/coverages.size)
            val futurePenalty=if(lectureDate!=null && deck.taughtOn?.isAfter(lectureDate)==true)0.25 else 1.0
            Match(deck,terms,100.0*coverage*futurePenalty)
        }.sortedWith(compareByDescending<Match>{it.score}.thenBy{it.deck.id})
    }
    fun clearWinner(ranked: List<Match>): Match? {
        val top = ranked.firstOrNull() ?: return null
        if (top.terms.size < 4 || top.score < 3.0) return null
        val next = ranked.getOrNull(1)?.score ?: 0.0
        return top.takeIf { top.score >= next * 1.6 && top.score - next >= 2.0 }
    }
}
