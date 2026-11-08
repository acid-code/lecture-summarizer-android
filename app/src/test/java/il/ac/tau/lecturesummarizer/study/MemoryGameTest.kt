package il.ac.tau.lecturesummarizer.study
import org.junit.Test
import org.junit.Assert.*
class MemoryGameTest {
 private val concepts=listOf(Concept("אנזים","זרז ביולוגי שמאיץ תגובה"),Concept("סובסטרט","החומר שעליו פועל האנזים"),Concept("ATP","מולקולה המעבירה אנרגיה"))
 @Test fun cleanRejectsBlankDuplicateAndAmbiguousPairs(){val input=concepts+Concept(" אנזים ","הסבר נוסף")+Concept("אחר",concepts[0].explanation)+Concept("", "הסבר")+Concept("כפול","כפול");assertEquals(concepts,cleanConcepts(input))}
 @Test fun deckHasOneTermAndOneDefinitionForEachConcept(){val cards=memoryCards(concepts,7);assertEquals(6,cards.size);for(i in concepts.indices){assertEquals(2,cards.count{it.pair==i});assertEquals(setOf(false,true),cards.filter{it.pair==i}.map{it.definition}.toSet())}}
 @Test fun shuffleIsDeterministicButCanChange(){assertEquals(memoryCards(concepts,7),memoryCards(concepts,7));assertNotEquals(memoryCards(concepts,7),memoryCards(concepts,8))}
 @Test fun matchingPairStaysMatchedAndCannotBePickedAgain(){val cards=memoryCards(concepts,7);val pair=cards.indices.filter{cards[it].pair==0};val round=MemoryRound().flip(pair[0],cards).flip(pair[1],cards);assertEquals(listOf(0),round.matched);assertTrue(round.open.isEmpty());assertEquals(1,round.attempts);assertEquals(round,round.flip(pair[0],cards))}
 @Test fun mismatchStaysReadableAndLocksThirdCardUntilClosed(){val cards=memoryCards(concepts,7);val a=cards.indices.first{cards[it].pair==0};val b=cards.indices.first{cards[it].pair==1};val c=cards.indices.first{cards[it].pair==2};val round=MemoryRound().flip(a,cards).flip(b,cards);assertEquals(2,round.open.size);assertEquals(round,round.flip(c,cards));assertTrue(round.conceal().open.isEmpty());assertEquals(1,round.attempts)}
 @Test fun sameCardOrInvalidIndexNeverCountsAsPair(){val cards=memoryCards(concepts,7);val round=MemoryRound().flip(0,cards);assertEquals(round,round.flip(0,cards));assertEquals(round,round.flip(-1,cards));assertEquals(0,round.attempts)}
 @Test fun completeOnlyAfterAllRealPairs(){val cards=memoryCards(concepts,7);var r=MemoryRound();for(i in concepts.indices){cards.indices.filter{cards[it].pair==i}.forEach{r=r.flip(it,cards)}};assertTrue(r.complete(3));assertFalse(MemoryRound().complete(0));assertEquals(3,r.attempts)}
 @Test fun roundsNeverEndWithASinglePairWhenTwoOrMoreExist(){for(n in 2..25){val groups=memoryGroups((1..n).map{Concept("Term $it","Definition $it")});assertEquals(n,groups.sumOf{it.size});assertTrue(groups.all{it.size in 2..4})}}
 @Test fun conceptsAreBoundedAndNeverGenerated(){val raw=(0..150).map{Concept("Term $it","Source definition $it")};assertEquals(100,cleanConcepts(raw).size);assertTrue(cleanConcepts(emptyList()).isEmpty())}
}
