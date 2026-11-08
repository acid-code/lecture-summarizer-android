package il.ac.tau.lecturesummarizer

import android.app.Activity
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import il.ac.tau.lecturesummarizer.ui.AppTheme
import il.ac.tau.lecturesummarizer.ui.CalendarImportScreen

/** A real edge-to-edge activity window. Full-screen Compose Dialogs have a separate inset host. */
class CalendarImportActivity : androidx.appcompat.app.AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // Same policy as MainActivity; gesture + three-button navigation both work.
        setContent { AppTheme {
            CalendarImportScreen(application as LectureApp,onBack={finish()}) {
                setResult(Activity.RESULT_OK)
                finish()
            }
        } }
    }
}
