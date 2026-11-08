package il.ac.tau.lecturesummarizer.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.github.takahirom.roborazzi.captureRoboImage
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.study.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Production components; synthetic concepts and tutor replies only. No live model/account traffic. */
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[34],qualifiers="he-rIL-w393dp-h852dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StudyToolsScreenshotsTest {
 @get:Rule val compose=createComposeRule()
 private val ctx get()=ApplicationProvider.getApplicationContext<Application>()
 private val out get()=File(System.getenv("LECTURE_SCREEN_DIR")?:"build/screens/study-tools").apply{mkdirs()}
 @Test @Config(qualifiers="he-rIL-w393dp-h852dp-notnight-mdpi") fun hebrewLight()=render("he",false)
 @Test @Config(qualifiers="he-rIL-w393dp-h852dp-night-mdpi") fun hebrewDark()=render("he",true)
 @Test @Config(qualifiers="en-rUS-w393dp-h852dp-notnight-mdpi") fun englishLight()=render("en",false)
 @Test @Config(qualifiers="en-rUS-w393dp-h852dp-night-mdpi") fun englishDark()=render("en",true)
 private fun render(lang:String,dark:Boolean){
   val he=lang=="he";val title=if(he)"הרצאה 3 · אנזימים"else "Lecture 3 · Enzymes"
   val concepts=if(he)listOf(Concept("אנזים","זרז ביולוגי המפחית את אנרגיית השפעול של תגובה."),Concept("סובסטרט","החומר שעליו פועל האנזים."),Concept("אתר פעיל","האזור באנזים שאליו נקשר הסובסטרט."),Concept("אנרגיית שפעול","האנרגיה הדרושה כדי להתחיל את התגובה."))else listOf(Concept("Enzyme","A biological catalyst that lowers the activation energy of a reaction."),Concept("Substrate","The material on which an enzyme acts."),Concept("Active site","The region of the enzyme where the substrate binds."),Concept("Activation energy","The energy needed to start a reaction."))
   val text=if(he)"אנזים מפחית את אנרגיית השפעול של תגובה ואינו נצרך בה."else "An enzyme lowers the activation energy of a reaction and is not consumed."
   val question=if(he)"מה תפקיד האנזים בשיעור הזה?"else "What does an enzyme do in this lecture?"
   val answered=listOf(TutorTurn(question,TutorReply("answer",text,listOf(TutorCitation("t:0","transcript","Transcript",text)))))
   val refused=listOf(TutorTurn(if(he)"עזרו לי לתכנן חופשה"else "Help me plan a holiday",TutorReply("refused",if(he)"אפשר לשאול רק על החומר של השיעור הזה."else "I can only help with this lecture's material.",emptyList())))
   var screen by mutableIntStateOf(0);var input by mutableStateOf("")
   compose.setContent{CompositionLocalProvider(LocalLayoutDirection provides if(he)LayoutDirection.Rtl else LayoutDirection.Ltr){AppTheme(darkMode=dark){
     AppScaffold(stringResource(if(screen in 1..3)R.string.memory_title else if(screen>=4)R.string.tutor_title else R.string.copy_lecture),onBack={}){pad->
       when(screen){
         0->Column(Modifier.padding(pad).fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text(bidi(title),style=MaterialTheme.typography.titleLarge);StudyToolsEntry({},{})}
         1->MemoryGameContent(title,concepts,"fixture-$lang",Modifier.padding(pad),initialSeed=7)
         2->MemoryAvailabilityContent(title,true,false,false,true,true,Modifier.padding(pad)){}
         3->MemoryAvailabilityContent(title,false,false,false,false,true,Modifier.padding(pad)){}
         else->TutorConversation(title,when(screen){5,7->answered;6->refused;else->emptyList()},input,{input=it},screen==9,screen!=7,when(screen){7->ctx.getString(R.string.tutor_offline);8->ctx.getString(R.string.tutor_server_required);else->null},if(screen==4)concepts.take(3)else emptyList(),Modifier.padding(pad)){}
       }
     }
   }}}
   fun capture(name:String){compose.waitForIdle();compose.mainClock.advanceTimeBy(500);compose.waitForIdle();compose.onRoot().captureRoboImage(File(out,"$name-$lang-${if(dark)"dark"else "light"}.png").absolutePath)}
   capture("lecture-study-entry")
   compose.runOnIdle{screen=1};capture("memory-board")
   compose.onNodeWithTag("recall-card").performClick();compose.mainClock.advanceTimeBy(400);capture("memory-definition")
   compose.onNodeWithTag("recall-review").performClick();capture("memory-review")
   repeat(concepts.size){compose.onNodeWithTag("recall-card").performClick();compose.mainClock.advanceTimeBy(400);compose.onNodeWithTag("recall-known").performClick()}
   compose.onNodeWithText(ctx.getString(R.string.flashcard_finished)).assertIsDisplayed();capture("memory-complete")
   listOf(2 to "memory-empty",3 to "memory-download-offline",4 to "tutor-empty",5 to "tutor-answer",6 to "tutor-refusal",7 to "tutor-offline",8 to "tutor-server-required",9 to "tutor-loading").forEach{(i,name)->compose.runOnIdle{screen=i};capture(name)
     if(i==5){compose.onNodeWithText(ctx.getString(R.string.tutor_sources,ltr(1))).performClick();capture("tutor-sources");compose.onAllNodesWithText(bidi(text)).assertCountEquals(2)}
   }
 }
 @Test fun longDefinitionCanBeReadInFull(){
   val text="A long definition from this lecture summary. ".repeat(12)
   val entries=listOf(Concept("Long concept",text),Concept("Other","A different definition for this concept"))
   val cards=memoryCards(entries,9);val i=cards.indexOfFirst{it.definition&&it.pair==0}
   compose.setContent{AppTheme(darkMode=false){AppScaffold("Memory"){pad->MemoryGameContent("Lecture",entries,"long",Modifier.padding(pad),initialSeed=9)}}}
   compose.onNodeWithTag("recall-card").performClick();compose.mainClock.advanceTimeBy(400)
   compose.onNodeWithText(text.trim()).assertExists()
 }
 @Test fun flippedCardSurvivesStateRestoration(){
   val entries=listOf(Concept("Term A","Definition A from this summary"),Concept("Term B","Definition B from this summary"));val cards=memoryCards(entries,9)
   val restore=StateRestorationTester(compose)
   restore.setContent{AppTheme(darkMode=false){AppScaffold("Memory"){pad->MemoryGameContent("Lecture",entries,"saved",Modifier.padding(pad),initialSeed=9)}}}
   compose.onNodeWithTag("recall-card").performClick();compose.mainClock.advanceTimeBy(400);compose.onNodeWithText(entries[0].explanation).assertIsDisplayed()
   restore.emulateSavedInstanceStateRestore();compose.onNodeWithText(entries[0].explanation).assertIsDisplayed()
 }
}
