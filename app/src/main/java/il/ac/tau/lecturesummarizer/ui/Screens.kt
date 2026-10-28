package il.ac.tau.lecturesummarizer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.data.ApiProvider
import il.ac.tau.lecturesummarizer.data.LectureState
import il.ac.tau.lecturesummarizer.recording.RecorderService
import il.ac.tau.lecturesummarizer.upload.UploadWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

// ---------- Courses ----------

@Composable
fun CoursesScreen(app: LectureApp, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val courses by app.repo.courses.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    AppScaffold("Courses",
        actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") } },
        fab = { FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "Add course") } }
    ) { pad ->
        if (courses.isEmpty()) Empty(pad, "No courses yet. Tap + to add one (Hebrew names are fine).")
        LazyColumn(Modifier.padding(pad)) {
            items(courses, key = { it.id }) { c ->
                ListItem(headlineContent = { Text(c.name) }, modifier = Modifier.clickable { onOpen(c.id) })
                HorizontalDivider()
            }
        }
    }
    if (adding) NameDialog("New course", "Course name", onDismiss = { adding = false }) {
        app.repo.addCourse(it); adding = false
    }
}

// ---------- Lectures ----------

@Composable
fun LecturesScreen(
    app: LectureApp, courseId: String, onBack: () -> Unit,
    onRecord: (String) -> Unit, onAttach: (String) -> Unit, onJob: (String) -> Unit,
) {
    val all by app.repo.lectures.collectAsStateWithLifecycle()
    val lectures = all.filter { it.courseId == courseId }.sortedByDescending { it.recordedAtMs }
    var adding by remember { mutableStateOf(false) }
    AppScaffold(app.repo.course(courseId)?.name ?: "Lectures", onBack = onBack,
        fab = { FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "New lecture") } }
    ) { pad ->
        if (lectures.isEmpty()) Empty(pad, "No lectures. Tap + to start a new lecture part.")
        LazyColumn(Modifier.padding(pad)) {
            items(lectures, key = { it.id }) { l ->
                ListItem(
                    headlineContent = { Text(l.title) },
                    supportingContent = {
                        Text("${DateFormat.getDateTimeInstance().format(Date(l.recordedAtMs))} · ${l.state} · " +
                            "${l.segments.size} seg · ${l.markers.size} markers")
                    },
                    modifier = Modifier.clickable {
                        when (l.state) {
                            LectureState.DRAFT, LectureState.RECORDING -> onRecord(l.id)
                            LectureState.RECORDED, LectureState.FAILED -> onAttach(l.id)
                            else -> onJob(l.id)
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
    if (adding) NameDialog("New lecture part", "Title (e.g. Lecture 5 — part 1)", onDismiss = { adding = false }) {
        val l = app.repo.addLecture(courseId, it); adding = false; onRecord(l.id)
    }
}

// ---------- Record ----------

@Composable
fun RecordScreen(app: LectureApp, lectureId: String, onBack: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val rec by RecorderService.state.collectAsStateWithLifecycle()
    val lecture by app.repo.lectures.collectAsStateWithLifecycle()
    val l = lecture.firstOrNull { it.id == lectureId }
    val active = rec.recording && rec.lectureId == lectureId
    val otherActive = rec.recording && rec.lectureId != lectureId

    val perms = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) RecorderService.start(ctx, lectureId) else denied = true
    }

    AppScaffold(l?.title ?: "Record", onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val prior = l?.segments?.sumOf { it.durationMs } ?: 0
            Text(formatElapsed(if (active) rec.elapsedMs else prior), fontSize = 56.sp)
            Text(if (active) "Recording · segment ${rec.segmentSeq + 1} · ${rec.markerCount} markers"
                 else "Stopped · ${l?.segments?.size ?: 0} segments · ${l?.markers?.size ?: 0} markers")
            Text("Recording continues with the screen off. Parts are 45–50 min; audio is saved in 10-min segments.",
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            if (otherActive) Text("Another lecture is recording. Stop it first.", color = MaterialTheme.colorScheme.error)
            if (denied) Text("Microphone permission is required.", color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            if (!active) {
                Button(enabled = !otherActive, onClick = {
                    val missing = perms.any { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
                    if (missing) launcher.launch(perms) else RecorderService.start(ctx, lectureId)
                }, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                    Text(if ((l?.segments?.size ?: 0) > 0) "Resume recording" else "Start recording")
                }
                if ((l?.segments?.size ?: 0) > 0) OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text("Done → attach & upload")
                }
            } else {
                FilledTonalButton(onClick = { RecorderService.marker(ctx) }, modifier = Modifier.fillMaxWidth().height(96.dp)) {
                    Text("Next slide / topic", fontSize = 22.sp)
                }
                Button(onClick = { RecorderService.stop(ctx) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Stop") }
            }
        }
    }
}

// ---------- Attach & upload ----------

@Composable
fun AttachUploadScreen(app: LectureApp, lectureId: String, onBack: () -> Unit, onUploaded: () -> Unit) {
    val ctx = LocalContext.current
    val all by app.repo.lectures.collectAsStateWithLifecycle()
    val l = all.firstOrNull { it.id == lectureId } ?: return
    fun persist(uri: Uri) = runCatching {
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val slidesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { persist(it); app.repo.updateLecture(lectureId) { x -> x.copy(slidesUri = it.toString()) } }
    }
    val contextPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { persist(it); app.repo.updateLecture(lectureId) { x -> x.copy(contextPdfUri = it.toString()) } }
    }
    AppScaffold("Attach & upload", onBack = onBack) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(l.title, style = MaterialTheme.typography.titleLarge)
            Text("${l.segments.size} audio segments · ${formatElapsed(l.segments.sumOf { it.durationMs })} · ${l.markers.size} markers")
            HorizontalDivider()
            Text("Lecture slides (optional, PDF/PPTX)", style = MaterialTheme.typography.titleSmall)
            Text(l.slidesUri?.let { Uri.parse(it).lastPathSegment } ?: "None")
            OutlinedButton(onClick = {
                slidesPicker.launch(arrayOf("application/pdf",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/vnd.ms-powerpoint"))
            }) { Text("Choose slides") }
            Text("Course context PDF (optional — syllabus, glossary, previous summaries)", style = MaterialTheme.typography.titleSmall)
            Text(l.contextPdfUri?.let { Uri.parse(it).lastPathSegment } ?: "None")
            OutlinedButton(onClick = { contextPicker.launch(arrayOf("application/pdf")) }) { Text("Choose context PDF") }
            HorizontalDivider()
            if (app.settings.mockMode) Text("Mock mode is ON — nothing leaves the phone.", color = MaterialTheme.colorScheme.tertiary)
            l.error?.let { Text("Last error: $it (will retry)", color = MaterialTheme.colorScheme.error) }
            Button(enabled = l.segments.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = {
                UploadWorker.enqueue(ctx, lectureId); onUploaded()
            }) { Text("Upload & summarize") }
        }
    }
}

// ---------- Job status + PDF ----------

@Composable
fun JobScreen(app: LectureApp, lectureId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val all by app.repo.lectures.collectAsStateWithLifecycle()
    val l = all.firstOrNull { it.id == lectureId } ?: return
    val scope = rememberCoroutineScope()

    // Poll job status while processing; fetch the PDF when done.
    LaunchedEffect(l.jobId, l.state) {
        val jobId = l.jobId ?: return@LaunchedEffect
        if (l.state != LectureState.PROCESSING) return@LaunchedEffect
        val api = ApiProvider.get(app.settings)
        while (true) {
            val st = runCatching { api.jobStatus(jobId) }.getOrNull()
            if (st != null) {
                app.repo.updateLecture(lectureId) { it.copy(stage = st.stage, progress = st.progress, error = st.error) }
                if (st.state == "done") {
                    val dest = File(app.repo.pdfDir(), "$lectureId.pdf")
                    runCatching { api.downloadSummaryPdf(l.remoteId!!, dest) }
                        .onSuccess { app.repo.updateLecture(lectureId) { it.copy(state = LectureState.DONE, pdfPath = dest.absolutePath, progress = 100) } }
                        .onFailure { e -> app.repo.updateLecture(lectureId) { it.copy(error = e.message) } }
                    break
                }
                if (st.state == "failed") { app.repo.updateLecture(lectureId) { it.copy(state = LectureState.FAILED) }; break }
            }
            delay(if (app.settings.mockMode) 1000 else 5000)
        }
    }

    AppScaffold("Status", onBack = onBack) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(l.title, style = MaterialTheme.typography.titleLarge)
            Text("State: ${l.state}" + (l.stage?.let { " · stage: $it" } ?: ""))
            if (l.state == LectureState.UPLOADING || l.state == LectureState.PROCESSING) {
                LinearProgressIndicator(progress = { l.progress / 100f }, modifier = Modifier.fillMaxWidth())
                val up = l.segments.count { it.uploaded }
                Text("Uploaded segments: $up / ${l.segments.size}")
            }
            l.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (l.state == LectureState.FAILED) {
                // Upload finished and a server job exists → the failure is server-side (e.g. Gemini 503):
                // resume that job via POST /v1/jobs/{id}/retry instead of re-uploading / re-processing.
                val jobId = l.jobId
                val uploaded = l.segments.isNotEmpty() && l.segments.all { it.uploaded }
                if (jobId != null && uploaded) Button(onClick = {
                    scope.launch {
                        runCatching { ApiProvider.get(app.settings).retryJob(jobId) }
                            .onSuccess { st -> app.repo.updateLecture(lectureId) { it.copy(state = LectureState.PROCESSING, stage = st.stage, progress = st.progress, error = null) } }
                            .onFailure { e -> app.repo.updateLecture(lectureId) { it.copy(error = "Retry failed: ${e.message}") } }
                    }
                }) { Text("Retry processing") }
                else Button(onClick = { UploadWorker.enqueue(ctx, lectureId) }) { Text("Retry upload") }
            }
            l.pdfPath?.let { path ->
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(path))
                Button(modifier = Modifier.fillMaxWidth(), onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }) { Text("Open study PDF") }
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf")
                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share PDF"))
                }) { Text("Share PDF") }
            }
            if (l.state == LectureState.DONE) TextButton(onClick = {
                scope.launch { app.repo.updateLecture(lectureId) { it.copy(state = LectureState.PROCESSING) } }
            }) { Text("Re-check / re-download") }
        }
    }
}

// ---------- Settings ----------

@Composable
fun SettingsScreen(app: LectureApp, onBack: () -> Unit) {
    var url by remember { mutableStateOf(app.settings.baseUrl) }
    var token by remember { mutableStateOf(app.settings.apiToken) }
    var mock by remember { mutableStateOf(app.settings.mockMode) }
    AppScaffold("Settings", onBack = {
        app.settings.baseUrl = url; app.settings.apiToken = token; app.settings.mockMode = mock; onBack()
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(url, { url = it }, label = { Text("API BASE_URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it }, label = { Text("API token (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(mock, { mock = it }); Spacer(Modifier.width(12.dp)); Text("Mock mode (offline, fake processing)")
            }
            Text("Privacy: recordings stay in app-private storage until you upload them to YOUR API above. " +
                "The app talks to no other server and never calls an AI model directly.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------- helpers ----------

@Composable
private fun Empty(pad: PaddingValues, text: String) {
    Box(Modifier.padding(pad).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center)
    }
}

@Composable
private fun NameDialog(title: String, label: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onOk(text.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
