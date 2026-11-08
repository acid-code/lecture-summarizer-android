package il.ac.tau.lecturesummarizer.slides

import il.ac.tau.lecturesummarizer.data.SlideRange
import il.ac.tau.lecturesummarizer.data.TranscriptLine
import java.time.LocalDate
import kotlin.math.*

/** Sequence is a candidate constraint, not proof. Always suggestions; never lecture-number = deck-number. */
object ContinuationMatcher {
 data class Suggestion(val match:SlideMatcher.Match,val range:SlideRange?,val continued:Boolean,val evidenceWindows:Int)
 data class Result(val suggestions:List<Suggestion>,val candidates:List<SlideMatcher.Match>,val constrained:Boolean)
 fun deckNumber(name:String):Int?{
  val clean=name.replace(Regex("^\\d{4,}[_-]"),"")
  return Regex("""(?i)(?:slides?|deck|ppt|lecture|lec|מצגת)[\s_#.:-]*0*(\d{1,2})(?!\d)""").find(clean)?.groupValues?.get(1)?.toIntOrNull()
   ?: Regex("""^0*(\d{1,2})[._ -]+[^\d]""").find(clean)?.groupValues?.get(1)?.toIntOrNull()
 }
 fun suggest(decks:List<SlideMatcher.Deck>,lines:List<TranscriptLine>,previousIds:List<Long> = emptyList(),date:LocalDate?=null,sequenceOverrides:Map<Long,Int> = emptyMap()):Result{
  fun number(d:SlideMatcher.Deck)=sequenceOverrides[d.id]?.takeIf{it in 1..99} ?: deckNumber(d.name)
  val full=SlideMatcher.rank(decks,lines.joinToString(" "){it.text},date)
  val previous=previousIds.mapNotNull{id->decks.firstOrNull{it.id==id}}.maxByOrNull{number(it) ?: -1}
  val base=previous ?: full.firstOrNull()?.deck
  val n=base?.let{number(it)}
  val next=if(n==null)null else decks.filter{number(it)==n+1}.maxByOrNull{d->full.firstOrNull{it.deck.id==d.id}?.score ?: 0.0}
  val allowed=if(previous!=null)setOfNotNull(previous.id,next?.id)else null
  val candidates=full.filter{allowed==null || it.deck.id in allowed}
  val primary=candidates.firstOrNull{it.terms.size>=4 && it.score>=3} ?: return Result(emptyList(),candidates,previous!=null)
  val chunks=lines.sortedBy{it.t0}.chunked(ceil(lines.size/10.0).toInt().coerceAtLeast(1)).map{it.joinToString(" "){line->line.text}}
  val windowRanks=chunks.map{SlideMatcher.rank(decks,it,date).associateBy{m->m.deck.id}}
  val pairFirst=previous ?: primary.deck
  val pairNext=if(pairFirst.id==base?.id)next else number(pairFirst)?.let{sequence->decks.filter{number(it)==sequence+1}.maxByOrNull{d->full.firstOrNull{it.deck.id==d.id}?.score ?: 0.0}}
  fun support(deck:SlideMatcher.Deck,other:SlideMatcher.Deck?):List<Int>{
   val unique=SlideMatcher.keywords(deck.text)-(other?.let{SlideMatcher.keywords(it.text)} ?: emptySet())
   return chunks.indices.filter{i->val m=windowRanks[i][deck.id]!!;val rival=other?.let{windowRanks[i][it.id]?.score} ?: 0.0
    m.terms.size>=3 && m.score>=3 && m.score>=rival*1.1 && (other==null || (SlideMatcher.keywords(chunks[i]) intersect unique).size>=2)}
  }
  val firstSupport=support(pairFirst,pairNext);val nextSupport=pairNext?.let{support(it,pairFirst)}.orEmpty()
  val minWindows=if(chunks.size>=6)2 else 1
  val ordered=firstSupport.size>=minWindows && nextSupport.size>=minWindows && firstSupport.average()<nextSupport.average() && firstSupport.first()<nextSupport.last()
  val chosen=if(pairNext!=null && ordered)listOf(pairFirst to firstSupport,pairNext to nextSupport)else listOf(primary.deck to support(primary.deck,null))
  val suggestions=chosen.map{(deck,windows)->Suggestion(full.first{it.deck.id==deck.id},range(deck,windows.map{chunks[it]}),deck.id in previousIds,windows.size)}
  return Result(suggestions,candidates,previous!=null)
 }
 /** Monotone page alignment over ordered transcript windows; original slide numbers, no flattened-text guesses. */
 internal fun range(deck:SlideMatcher.Deck,windows:List<String>):SlideRange?{
  if(deck.pages.size<2 || windows.size<2)return null
  val pages=deck.pages.map(SlideMatcher::keywords);val df=mutableMapOf<String,Int>();pages.forEach{it.forEach{term->df[term]=(df[term] ?: 0)+1}}
  val idf=df.mapValues{ln(1.0+pages.size.toDouble()/it.value)}
  val evidence=windows.map{window->val heard=SlideMatcher.keywords(window);pages.map{terms->val hits=terms intersect heard;if(hits.size<3)0.0 else hits.sumOf{idf.getValue(it)}/sqrt(10.0+terms.sumOf{idf.getValue(it)})}}
   .filter{scores->scores.maxOrNull()?.let{it>0}==true}
  if(evidence.size<2)return null
  val paths=Array(evidence.size){IntArray(pages.size)};var dp=evidence.first().toDoubleArray()
  for(i in 1 until evidence.size){val ndp=DoubleArray(pages.size);var best=0
   for(page in pages.indices){if(dp[page]>dp[best])best=page;paths[i][page]=best;ndp[page]=dp[best]+evidence[i][page]};dp=ndp}
  var last=dp.indices.maxByOrNull{dp[it]} ?: return null;val aligned=mutableListOf<Int>()
  for(i in evidence.indices.reversed()){if(evidence[i][last]>0)aligned+=last+1;if(i>0)last=paths[i][last]}
  if(aligned.distinct().size<2)return null
  return SlideRange(aligned.min(),aligned.max(),true)
 }
}
