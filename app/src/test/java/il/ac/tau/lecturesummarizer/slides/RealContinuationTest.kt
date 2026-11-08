package il.ac.tau.lecturesummarizer.slides
import il.ac.tau.lecturesummarizer.data.*
import org.json.*
import java.io.File
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
class RealContinuationTest{
 @Test fun actualCourseContinuationDiagnostic(){
  val dir=System.getenv("REAL_CONTINUATION_FIXTURES")?.let(::File);assumeTrue(dir?.isDirectory==true)
  fun rows(name:String):List<JSONObject>{val a=JSONArray(File(dir,name).readText());return (0 until a.length()).map{a.getJSONObject(it)}}
  val files=rows("decks.json");val lectures=rows("transcripts.json");val links=rows("links.json");val report=JSONArray()
  val practice=Regex("תרגול|Tirgul|student version|answers",RegexOption.IGNORE_CASE)
  lectures.filter{!practice.containsMatchIn(it.getString("title"))}.forEach{l->
   val course=l.getString("course_id");val available=files.filter{it.getString("course_id")==course && !practice.containsMatchIn(it.getString("name"))}
   val sources=available.groupBy{ContinuationMatcher.deckNumber(it.getString("name"))?.toString() ?: it.getString("name")}.values.map{versions->versions.firstOrNull{it.getString("name").endsWith(".pdf")} ?: versions.first()}
   val decks=sources.mapIndexed{i,f->SlideMatcher.Deck((i+1).toLong(),f.getString("name"),f.getString("text"))}
   val previous=lectures.filter{it.getString("course_id")==course && !practice.containsMatchIn(it.getString("title")) && it.getString("recorded_at")<l.getString("recorded_at")}.maxByOrNull{it.getString("recorded_at")}
   val old=links.filter{it.getString("lecture_id")==previous?.getString("id")}.mapNotNull{link->val source=files.firstOrNull{it.getString("id")==link.getString("id")};val n=source?.let{ContinuationMatcher.deckNumber(it.getString("name"))};decks.firstOrNull{d->d.name==source?.getString("name") || n!=null && ContinuationMatcher.deckNumber(d.name)==n}?.id}
   val segments=JSONArray(l.optString("repaired").takeIf{it!="null" && it.isNotBlank()} ?: l.getString("segments"));val lines=(0 until segments.length()).map{segments.getJSONObject(it)}.map{TranscriptLine(it.optDouble("start",it.optDouble("t0")),it.optDouble("end",it.optDouble("t1")),it.optString("text"))}
   assertTrue(lines.isNotEmpty());val result=ContinuationMatcher.suggest(decks,lines,old)
   if(l.getString("id")=="b8d1cdc7-04dc-4b44-889d-573c98091b3e"){assertEquals(listOf(2),result.suggestions.map{ContinuationMatcher.deckNumber(it.match.deck.name)});assertTrue(result.suggestions.single().continued)}
   val out=JSONObject().put("title",l.getString("title")).put("course",course).put("id",l.getString("id")).put("availableDecks",decks.size).put("previousAttached",JSONArray(old)).put("suggestions",JSONArray().apply{result.suggestions.forEach{s->put(JSONObject().put("name",s.match.deck.name).put("range",s.range?.toJson()).put("continued",s.continued).put("windows",s.evidenceWindows).put("score",s.match.score))}})
   report.put(out);println(out.toString())
  }
  // Explicit read-only combined-session preview: two separate Genetics recordings on 1 Nov, NOT a live merge.
  val session=lectures.filter{it.getString("course_id")=="1bf703c6-e0cb-4d19-a4c3-fd7385fdd951" && it.getString("recorded_at").startsWith("2026-11-01")}.sortedBy{it.getString("recorded_at")}
  if(session.size==2){
   val origin=java.time.Instant.parse(session.first().getString("recorded_at"))
   val input=session.flatMap{l->val offset=java.time.Duration.between(origin,java.time.Instant.parse(l.getString("recorded_at"))).toMillis()/1000.0;val a=JSONArray(l.optString("repaired").takeIf{it!="null" && it.isNotBlank()} ?: l.getString("segments"));(0 until a.length()).map{a.getJSONObject(it)}.map{TranscriptLine(offset+it.optDouble("start",it.optDouble("t0")),offset+it.optDouble("end",it.optDouble("t1")),it.getString("text"))}}
   val decks=files.filter{it.getString("course_id")=="1bf703c6-e0cb-4d19-a4c3-fd7385fdd951" && it.getString("name").endsWith(".pdf") && ContinuationMatcher.deckNumber(it.getString("name"))!=null}.mapIndexed{i,f->SlideMatcher.Deck((i+1).toLong(),f.getString("name"),f.getString("text"))}
   val prev=decks.filter{ContinuationMatcher.deckNumber(it.name)==2}.map{it.id};val result=ContinuationMatcher.suggest(decks,input,prev)
   assertEquals(listOf(2,3),result.suggestions.map{ContinuationMatcher.deckNumber(it.match.deck.name)})
   report.put(JSONObject().put("type","combined_recording_preview_not_merged").put("sourceIds",JSONArray(session.map{it.getString("id")})).put("suggestions",JSONArray().apply{result.suggestions.forEach{put(JSONObject().put("name",it.match.deck.name).put("range",it.range?.toJson()).put("windows",it.evidenceWindows))}}))
  }
  File(dir,"results.json").writeText(report.toString(2));assertTrue(report.length()>0)
 }
}
