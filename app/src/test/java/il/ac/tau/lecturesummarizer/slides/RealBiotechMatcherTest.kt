package il.ac.tau.lecturesummarizer.slides

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File

class RealBiotechMatcherTest {
    @Test fun reportRealReadOnlyFixturesWhenProvided() {
        val dir=System.getenv("REAL_BIOTECH_FIXTURES")?.let(::File)
        assumeTrue("Real fixtures are private external inputs, not synthetic repo fixtures",dir!=null && dir.exists())
        val decks=JSONObject(File(dir,"decks.json").readText()).getJSONArray("rows")
        val pool=(0 until decks.length()).map{val row=decks.getJSONArray(it);SlideMatcher.Deck(it.toLong(),row.getString(0),row.getString(1))}
        val lectures=JSONObject(File(dir,"transcripts.json").readText()).getJSONArray("rows")
        for(i in 0 until lectures.length()) {
            val row=lectures.getJSONArray(i);val segments=JSONArray(row.getString(4))
            val text=(0 until segments.length()).joinToString(" "){segments.getJSONObject(it).getString("text")}
            val ranked=SlideMatcher.rank(pool,text)
            println("REAL BIOTECH ${row.getString(1)}: segments=${segments.length()} transcriptChars=${text.length} winner=${SlideMatcher.clearWinner(ranked)?.deck?.name ?: "ABSTAIN"}")
            val expected=when{row.getString(1).contains("28/10")->"PPT03";row.getString(1).contains("02/11")->"PPT04";else->error("Unknown real fixture") }
            assertTrue("Real top suggestion must be $expected",ranked.first().deck.name.contains(expected))
            assertEquals(ranked.first().deck.id,SlideMatcher.rank(pool.reversed(),text).first().deck.id)
            ranked.forEach{println("  ${it.deck.name}: sharedTerms=${it.terms.size} score=${it.score}")}
        }
    }
}
