package il.ac.tau.lecturesummarizer.ui
import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.core.app.ApplicationProvider
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.study.Concept
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[34],qualifiers="en-rUS-w393dp-h852dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActiveRecallTest{
 @get:Rule val compose=createComposeRule()
 private fun flip(){compose.onNodeWithTag("recall-card").performClick();compose.mainClock.advanceTimeBy(400);compose.waitForIdle()}
 @Test fun reviewRequeuesGotItAdvancesAndFinishes(){
  val entries=listOf(Concept("ATP","Energy carrier in cells"),Concept("אנזים","זרז ביולוגי / biological catalyst"))
  compose.setContent{AppTheme(darkMode=false){MemoryGameContent("Lecture",entries,"flow",Modifier.fillMaxSize())}}
  compose.onNodeWithTag("recall-known").assertDoesNotExist();compose.onNodeWithText("ATP").assertIsDisplayed()
  flip();compose.onNodeWithText(entries[0].explanation).assertIsDisplayed();compose.onNodeWithTag("recall-review").performClick()
  compose.onNodeWithText("אנזים").assertIsDisplayed();flip();compose.onNodeWithTag("recall-known").performClick()
  compose.onNodeWithText("ATP").assertIsDisplayed();flip();compose.onNodeWithTag("recall-known").performClick()
  compose.onNodeWithText(ApplicationProvider.getApplicationContext<Application>().getString(R.string.flashcard_finished)).assertIsDisplayed()
 }
 @Test fun flippedScrollableDefinitionSurvivesRestore(){
  val restore=StateRestorationTester(compose)
  val text="A long mixed-language definition: אנזים and ATP. ".repeat(70)
  restore.setContent{AppTheme(darkMode=false){MemoryGameContent("Lecture",listOf(Concept("Long concept",text)),"restore",Modifier.fillMaxSize())}}
  flip();compose.onNodeWithText(text.trim()).assertExists();compose.onNodeWithTag("recall-text",useUnmergedTree=true).assert(hasScrollAction())
  restore.emulateSavedInstanceStateRestore();compose.mainClock.advanceTimeBy(400)
  compose.onNodeWithText(text.trim()).assertExists();compose.onNodeWithTag("recall-known").assertIsEnabled()
 }
 @Test fun statusHasOnlyOneIndicatorAndNeverUpdatedCopyHasNoPrefix(){
  compose.setContent{AppTheme(darkMode=false){Column{
   StatusChip("Updating",busy=true)
   CourseUpdated(ApplicationProvider.getApplicationContext<Application>(),"never-updated-status-test")
  }}}
  compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(1)
  compose.onNodeWithText("Not updated yet").assertExists()
 }

}
