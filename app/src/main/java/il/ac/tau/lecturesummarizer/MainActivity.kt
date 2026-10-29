package il.ac.tau.lecturesummarizer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import il.ac.tau.lecturesummarizer.ui.AppNav
import il.ac.tau.lecturesummarizer.ui.AppTheme

class MainActivity : ComponentActivity() {
    /** URIs received from the Android share sheet; non-empty → share import wizard. */
    val shared = mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent { AppTheme { AppNav(application as LectureApp, shared) } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handle(intent) }

    @Suppress("DEPRECATION")
    private fun handle(i: Intent?) {
        i ?: return
        val uris: List<Uri> = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()
            else -> emptyList()
        }.ifEmpty { i.clipData?.let { c -> (0 until c.itemCount).mapNotNull { c.getItemAt(it).uri } } ?: emptyList() }
        if (i.action == Intent.ACTION_SEND || i.action == Intent.ACTION_SEND_MULTIPLE) shared.value = uris
    }
}
