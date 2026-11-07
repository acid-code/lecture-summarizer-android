package il.ac.tau.lecturesummarizer.slides

import java.util.Locale
import kotlin.math.ln

/** Deliberately conservative: dates and bare numbers are NOT lecture numbers. */
object SlideMatcher {
    data class Deck(val id: Long, val name: String, val text: String)
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
    fun rank(decks: List<Deck>, transcript: String): List<Match> {
        val heard = keywords(transcript)
        val sets = decks.map { keywords(it.text) }
        return decks.mapIndexed { i, deck ->
            val terms = (sets[i] intersect heard).sortedByDescending { term -> ln(1.0 + decks.size.toDouble() / sets.count { term in it }) }
            // Common course boilerplate contributes little; repeated words don't inflate the score.
            Match(deck, terms, terms.sumOf { term -> ln(1.0 + decks.size.toDouble() / sets.count { term in it }) })
        }.sortedByDescending { it.score }
    }
    fun clearWinner(ranked: List<Match>): Match? {
        val top = ranked.firstOrNull() ?: return null
        if (top.terms.size < 4 || top.score < 3.0) return null
        val next = ranked.getOrNull(1)?.score ?: 0.0
        return top.takeIf { top.score >= next * 1.6 && top.score - next >= 2.0 }
    }
}
