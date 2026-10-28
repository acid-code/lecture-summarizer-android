package il.ac.tau.lecturesummarizer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import android.media.MediaMetadataRetriever
import android.provider.OpenableColumns
import android.widget.Toast
import il.ac.tau.lecturesummarizer.data.Segment
import il.ac.tau.lecturesummarizer.upload.JobPollWorker
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    val all by app.repo.lectures.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    AppScaffold("Courses",
        actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") } },
        fab = { FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "Add course") } }
    ) { pad ->
        if (courses.isEmpty()) Empty(pad, "No courses yet. Tap + to add one (Hebrew names are fine).")
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(courses, key = { it.id }) { c ->
                val n = all.count { it.courseId == c.id }
                NotebookCard(c.name, "$n lecture${if (n == 1) "" else "s"}" + (if (c.contextPdfUri != null) " · subject PDF ✓" else ""),
                    coverColor(c.name)) { onOpen(c.id) }
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
    val ctx = LocalContext.current
    val courses by app.repo.courses.collectAsStateWithLifecycle()
    val course = courses.firstOrNull { it.id == courseId }
    val subjectPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { ctx.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            app.repo.updateCourse(courseId) { c -> c.copy(contextPdfUri = it.toString()) }
        }
    }
    AppScaffold(course?.name ?: "Lectures", onBack = onBack,
        fab = { FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "New lecture") } }
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Course subject PDF", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("Syllabus / glossary / previous summaries. Sent with every lecture in this course as context.",
                            style = MaterialTheme.typography.bodySmall)
                        Text(course?.contextPdfUri?.let { displayName(ctx, it) } ?: "None attached",
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { subjectPicker.launch(arrayOf("application/pdf")) }) {
                                Text(if (course?.contextPdfUri == null) "Attach PDF" else "Replace")
                            }
                            course?.contextPdfUri?.let { u -> TextButton(onClick = { openDoc(ctx, u) }) { Text("Open") } }
                            if (course?.contextPdfUri != null) TextButton(onClick = {
                                app.repo.updateCourse(courseId) { it.copy(contextPdfUri = null) }
                            }) { Text("Remove") }
                        }
                    }
                }
            }
            if (lectures.isEmpty()) item { Text("No lectures. Tap + to start a new lecture part.", Modifier.padding(16.dp)) }
            items(lectures, key = { it.id }) { l ->
                NotebookCard(l.title,
                    "${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(l.recordedAtMs))} · " +
                        "${formatElapsed(l.segments.sumOf { it.durationMs })} · ${l.markers.size} markers",
                    coverColor(course?.name ?: l.courseId), badge = stateLabel(l.state)) {
                    when (l.state) {
                        LectureState.DRAFT, LectureState.RECORDING -> onRecord(l.id)
                        LectureState.RECORDED -> onAttach(l.id)
                        else -> onJob(l.id)
                    }
                }
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
                } else TextButton(onClick = onDone) { Text("Have an audio file instead? Import it →") }
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
    val scope = rememberCoroutineScope()
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { importAudio(app, lectureId, uri, ctx) }.onFailure { e ->
                app.repo.updateLecture(lectureId) { it.copy(error = "Import failed: ${e.message}") }
            }
        }
    }
    val courseCtx = app.repo.course(l.courseId)?.contextPdfUri
    AppScaffold("Attach & upload", onBack = onBack) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(l.title, style = MaterialTheme.typography.titleLarge)
            Text("${l.segments.size} audio segments · ${formatElapsed(l.segments.sumOf { it.durationMs })} · ${l.markers.size} markers")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { audioPicker.launch(arrayOf("audio/*", "video/mp4")) }) { Text("Import audio file") }
                if (l.segments.isNotEmpty()) TextButton(onClick = { shareRecording(ctx, l.segments) }) { Text("Share recording") }
            }
            HorizontalDivider()
            Text("Lecture slides (optional, PDF/PPTX)", style = MaterialTheme.typography.titleSmall)
            Text(l.slidesUri?.let { displayName(ctx, it) } ?: "None")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    slidesPicker.launch(arrayOf("application/pdf",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                        "application/vnd.ms-powerpoint"))
                }) { Text("Choose slides") }
                l.slidesUri?.let { u -> TextButton(onClick = { openDoc(ctx, u) }) { Text("Open") } }
            }
            Text("Context PDF for this lecture (optional)", style = MaterialTheme.typography.titleSmall)
            Text(l.contextPdfUri?.let { displayName(ctx, it) }
                ?: courseCtx?.let { "Using course subject PDF: ${displayName(ctx, it)}" } ?: "None (attach a course subject PDF on the course screen)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { contextPicker.launch(arrayOf("application/pdf")) }) { Text("Override for this lecture") }
                (l.contextPdfUri ?: courseCtx)?.let { u -> TextButton(onClick = { openDoc(ctx, u) }) { Text("Open") } }
            }
            HorizontalDivider()
            if (app.settings.mockMode) Text("Mock mode is ON — nothing leaves the phone.", color = MaterialTheme.colorScheme.tertiary)
            l.error?.let { ErrorText("Last error: $it (will retry)") }
            Button(enabled = l.segments.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = {
                UploadWorker.enqueue(ctx, lectureId); onUploaded()
            }) { Text("Upload & summarize") }
        }
    }
}

// ---------- Job status + PDF ----------

@Composable
fun JobScreen(app: LectureApp, lectureId: String, onBack: () -> Unit, onAttach: () -> Unit = {}) {
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
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(l.title, style = MaterialTheme.typography.titleLarge)
            Text("State: ${stateLabel(l.state)}" + (l.stage?.let { " · stage: $it" } ?: ""))
            if (l.state == LectureState.PROCESSING) Text("You'll get a notification when it's done — you can leave this screen.",
                style = MaterialTheme.typography.bodySmall)
            if (l.state == LectureState.UPLOADING || l.state == LectureState.PROCESSING) {
                LinearProgressIndicator(progress = { l.progress / 100f }, modifier = Modifier.fillMaxWidth())
                val up = l.segments.count { it.uploaded }
                Text("Uploaded segments: $up / ${l.segments.size}")
            }
            l.error?.let { ErrorText(it) }
            if (l.state == LectureState.FAILED) {
                // Upload finished and a server job exists → the failure is server-side (e.g. Gemini 503):
                // resume that job via POST /v1/jobs/{id}/retry instead of re-uploading / re-processing.
                val jobId = l.jobId
                val uploaded = l.segments.isNotEmpty() && l.segments.all { it.uploaded }
                if (jobId != null && uploaded) Button(onClick = {
                    scope.launch {
                        runCatching { ApiProvider.get(app.settings).retryJob(jobId) }
                            .onSuccess { st -> app.repo.updateLecture(lectureId) { it.copy(state = LectureState.PROCESSING, stage = st.stage, progress = st.progress, error = null) }; JobPollWorker.enqueue(ctx, lectureId) }
                            .onFailure { e -> app.repo.updateLecture(lectureId) { it.copy(error = "Retry failed: ${e.message}") } }
                    }
                }) { Text("Retry processing") }
                else Button(onClick = { UploadWorker.enqueue(ctx, lectureId) }) { Text("Retry upload") }
            }
            l.pdfPath?.takeIf { File(it).exists() }?.let { path ->
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(path))
                ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("📘 Study summary ready", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Button(modifier = Modifier.fillMaxWidth().height(56.dp), onClick = {
                            safeStart(ctx, Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }) { Text("Open study PDF") }
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf")
                                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share PDF"))
                        }) { Text("Share / save PDF") }
                    }
                }
            }
            l.slidesUri?.let { u -> OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { openDoc(ctx, u) }) { Text("Open lecture slides") } }
            val audio = l.segments.count { File(it.path).exists() }
            if (audio > 0) OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { shareRecording(ctx, l.segments) }) {
                Text("Share / save recording ($audio file${if (audio == 1) "" else "s"})")
            }
            if (l.state == LectureState.FAILED) TextButton(onClick = { onAttach() }) { Text("Edit attachments / add audio") }
            if (l.state == LectureState.DONE) TextButton(onClick = {
                scope.launch { app.repo.updateLecture(lectureId) { it.copy(state = LectureState.PROCESSING) } }
            }) { Text("Re-check / re-download") }
        }
    }
}

/** Long server errors (e.g. Gemini 429 JSON) are truncated to ~280 chars with a Show more toggle. */
@Composable
private fun ErrorText(msg: String, max: Int = 280) {
    var open by remember(msg) { mutableStateOf(false) }
    val long = msg.length > max
    Text(if (long && !open) msg.take(max) + "…" else msg, color = MaterialTheme.colorScheme.error)
    if (long) TextButton(onClick = { open = !open }) { Text(if (open) "Show less" else "Show more") }
}

// ---------- shared UI helpers ----------

private val covers = listOf(0xFF1E5AA8, 0xFFB23A48, 0xFF2E7D5B, 0xFF7B4FA0, 0xFFD07A1F, 0xFF00796B, 0xFF5D4037, 0xFF3949AB)
fun coverColor(key: String) = Color(covers[Math.floorMod(key.hashCode(), covers.size)])

fun stateLabel(s: LectureState) = when (s) {
    LectureState.DRAFT -> "Draft"; LectureState.RECORDING -> "● Recording"; LectureState.RECORDED -> "Ready to upload"
    LectureState.UPLOADING -> "Uploading"; LectureState.PROCESSING -> "Processing"; LectureState.DONE -> "✓ Summary"
    LectureState.FAILED -> "Failed"
}

/** Notebook-cover style card: colored spine + title. */
@Composable
fun NotebookCard(title: String, subtitle: String, color: Color, badge: String? = null, onClick: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(10.dp)) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(18.dp).fillMaxHeight().background(color))
            Box(Modifier.width(3.dp).fillMaxHeight().background(color.copy(alpha = 0.4f)))
            Column(Modifier.weight(1f).background(color.copy(alpha = 0.10f)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
                badge?.let {
                    Text(it, Modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 10.dp, vertical = 2.dp),
                        color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

fun safeStart(ctx: android.content.Context, i: Intent) {
    try { ctx.startActivity(i) } catch (e: android.content.ActivityNotFoundException) {
        Toast.makeText(ctx, "No app found to open this file", Toast.LENGTH_SHORT).show()
    }
}

/** Open a picked document (content:// URI from the picker) in a viewer app. */
fun openDoc(ctx: android.content.Context, uriStr: String) {
    val uri = Uri.parse(uriStr)
    val mime = ctx.contentResolver.getType(uri) ?: "application/pdf"
    safeStart(ctx, Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}

fun displayName(ctx: android.content.Context, uriStr: String): String = runCatching {
    ctx.contentResolver.query(Uri.parse(uriStr), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
}.getOrNull() ?: Uri.parse(uriStr).lastPathSegment ?: "file"

fun shareRecording(ctx: android.content.Context, segments: List<Segment>) {
    val files = segments.sortedBy { it.seq }.map { File(it.path) }.filter { it.exists() }
    if (files.isEmpty()) return
    val uris = ArrayList(files.map { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", it) })
    val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    ctx.startActivity(Intent.createChooser(send.setType("audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share recording"))
}

/** Copy a picked audio file into the lecture's recordings dir and add it as the next segment. */
fun importAudio(app: LectureApp, lectureId: String, uri: Uri, ctx: android.content.Context) {
    val name = displayName(ctx, uri.toString())
    val ext = name.substringAfterLast('.', "m4a").lowercase().takeIf { it.length in 2..5 } ?: "m4a"
    val l = app.repo.lecture(lectureId) ?: return
    val seq = (l.segments.maxOfOrNull { it.seq } ?: -1) + 1
    val dest = File(app.repo.recordingsDir(lectureId), "seg_%03d.%s".format(seq, ext))
    ctx.contentResolver.openInputStream(uri)!!.use { inp -> dest.outputStream().use { inp.copyTo(it) } }
    val dur = runCatching {
        MediaMetadataRetriever().run { setDataSource(dest.absolutePath)
            val d = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L; release(); d }
    }.getOrDefault(0L)
    val started = (l.segments.maxOfOrNull { it.startedAtMs + it.durationMs } ?: l.recordedAtMs)
    app.repo.updateLecture(lectureId) { x ->
        x.copy(segments = x.segments + Segment(seq, dest.absolutePath, started, dur),
            state = if (x.state == LectureState.DRAFT) LectureState.RECORDED else x.state, error = null)
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
