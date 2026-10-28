package il.ac.tau.lecturesummarizer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import il.ac.tau.lecturesummarizer.ui.AppNav
import il.ac.tau.lecturesummarizer.ui.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { AppNav(application as LectureApp) } }
    }
}
