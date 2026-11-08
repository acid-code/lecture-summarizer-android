package il.ac.tau.lecturesummarizer.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.moodle.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** "Add lecture" chooser: record live, or bring an existing Panopto recording (captions) instead. */
@Composable
fun NewEntryScreen(app: LectureApp, lectureId: String, onBack: () -> Unit, onRecord: () -> Unit, onUpload: () -> Unit) {
    val l = app.repo.lecture(lectureId) ?: return
    AppScaffold(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_add_lecture), onBack = onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(l.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_where_is_this_lecture_from), style = MaterialTheme.typography.bodyMedium)
            ChoiceCard(Icons.Default.Mic, androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.new_record), androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.new_record_body), onRecord)
            ChoiceCard(Icons.Default.UploadFile, androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.new_files),
                androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.new_files_body), onUpload)
            TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_cancel)) }
        }
    }
}

@Composable
private fun ChoiceCard(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Background caption download for Panopto entries, so slides can be attached in parallel. Status per lecture id. */
object PanoptoFetch {
    const val LOADING = "loading"; const val OK = "ok"; const val AUTH = "auth"
    val status = mutableStateMapOf<String, String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(app: LectureApp, lectureId: String) {
        val l = app.repo.lecture(lectureId) ?: return
        val id = l.panoptoId ?: return
        if (status[lectureId] == LOADING) return
        status[lectureId] = LOADING
        scope.launch {
            val r = runCatching {
                val srt = MoodleClient.of(MoodleStore(app).baseUrl).downloadCaptions(PanoptoRecording(id, l.panoptoTitle ?: "", l.panoptoUrl ?: Panopto.HOST))
                if (Panopto.parseSrt(srt).isEmpty()) error("Captions are empty")
                val f = File(app.filesDir, "panopto/$lectureId.srt").apply { parentFile?.mkdirs(); writeText(srt) }
                app.repo.updateLecture(lectureId) { it.copy(panoptoSrtPath = f.absolutePath) }
            }
            status[lectureId] = r.fold({ OK }, { if (it is PanoptoAuthRequired) AUTH else "error:${it.message ?: "Error"}" })
        }
    }
}

/** Panopto has its own SAML session: open the viewer once so its cookies land in CookieManager, then refetch. */
@Composable
fun PanoptoLoginScreen(app: LectureApp, lectureId: String, onDone: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val l = app.repo.lecture(lectureId) ?: return
    AppScaffold(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_sign_in_to_panopto), onBack = onDone) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            MoodleLoginView(MoodleStore(ctx.applicationContext).baseUrl, startUrl = l.panoptoUrl ?: Panopto.HOST,
                isDone = { u -> u.contains("panopto", true) && (u.contains("Viewer.aspx", true) || u.contains("Embed.aspx", true) || u.contains("/Sessions/List.aspx", true)) },
                sessionReady = { Panopto.hasSessionCookie() }) {
                PanoptoFetch.status.remove(lectureId); PanoptoFetch.start(app, lectureId); onDone()
            }
        }
    }
}
