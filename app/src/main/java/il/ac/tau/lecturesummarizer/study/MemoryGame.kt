package il.ac.tau.lecturesummarizer.study

import java.util.Locale
import kotlin.random.Random

data class Concept(val term:String,val explanation:String)
fun cleanConcepts(raw:List<Concept>):List<Concept>{
 val terms=mutableSetOf<String>();val definitions=mutableSetOf<String>()
 fun key(s:String)=s.trim().lowercase(Locale.ROOT).replace(Regex("\\s+")," ")
 return raw.asSequence().map{Concept(it.term.trim(),it.explanation.trim())}.filter{
   it.term.isNotBlank()&&it.explanation.isNotBlank()&&it.term.length<=240&&it.explanation.length<=6000&&key(it.term)!=key(it.explanation)
 }.filter{val t=key(it.term);val d=key(it.explanation);if(t in terms||d in definitions)false else{terms+=t;definitions+=d;true}}.take(100).toList()
}
data class MemoryCard(val pair:Int,val definition:Boolean,val text:String)
fun memoryCards(concepts:List<Concept>,seed:Int)=concepts.flatMapIndexed{i,c->listOf(MemoryCard(i,false,c.term),MemoryCard(i,true,c.explanation))}.shuffled(Random(seed))
data class MemoryRound(val open:List<Int> = emptyList(),val matched:List<Int> = emptyList(),val attempts:Int=0){
 fun flip(index:Int,cards:List<MemoryCard>):MemoryRound{
   if(index !in cards.indices||open.size>=2||index in open||cards[index].pair in matched)return this
   val next=open+index
   return if(next.size==2&&cards[next[0]].pair==cards[next[1]].pair)copy(open=emptyList(),matched=matched+cards[index].pair,attempts=attempts+1)
   else copy(open=next,attempts=attempts+if(next.size==2)1 else 0)
 }
 fun conceal()=if(open.size==2)copy(open=emptyList())else this
 fun complete(pairCount:Int)=pairCount>0&&matched.size==pairCount
}

/** Avoid a final one-pair round: five remaining concepts become rounds of three and two. */
fun memoryGroups(concepts:List<Concept>):List<List<Concept>>{
 val clean=cleanConcepts(concepts);val groups=mutableListOf<List<Concept>>();var start=0
 while(start<clean.size){val take=if(clean.size-start==5)3 else minOf(4,clean.size-start);groups+=clean.subList(start,start+take);start+=take}
 return groups
}
