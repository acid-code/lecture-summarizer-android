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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Share
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
    var editing by remember { mutableStateOf<il.ac.tau.lecturesummarizer.data.Course?>(null) }
    AppScaffold("Notebooks", logo = true,
        actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") } },
        fab = { ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New notebook") }) }
    ) { pad ->
        if (courses.isEmpty()) Column(Modifier.padding(pad).fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(il.ac.tau.lecturesummarizer.R.drawable.ic_logo), null, Modifier.size(96.dp))
            Spacer(Modifier.height(16.dp))
            Text("Lecture Summarizer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("No notebooks yet.\nCreate one per course — pick an icon, a color and a cover style.", textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            androidx.compose.foundation.lazy.grid.GridCells.Adaptive(150.dp), Modifier.padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            items(courses.size, key = { courses[it].id }) { i ->
                val c = courses[i]
                val n = all.count { it.courseId == c.id }
                Column {
                    NotebookCover(c.name, "$n lecture${if (n == 1) "" else "s"}" + (if (c.contextPdfUri != null) " · PDF ✓" else ""),
                        c.coverEmoji(), c.coverColorValue(), patternOf(c.pattern), Modifier.fillMaxWidth()) { onOpen(c.id) }
                    TextButton(onClick = { editing = c }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Customize", style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
    if (adding) NotebookEditor(null, onDismiss = { adding = false }) { name, e, col, pat ->
        app.repo.addCourse(name, e, col, pat); adding = false
    }
    editing?.let { c -> NotebookEditor(c, onDismiss = { editing = null }) { name, e, col, pat ->
        app.repo.updateCourse(c.id) { it.copy(name = name, emoji = e, color = col, pattern = pat) }; editing = null
    } }
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
    var editCover by remember { mutableStateOf(false) }
    AppScaffold(course?.name ?: "Lectures", onBack = onBack,
        fab = { ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New lecture") }) }
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            course?.let { c -> item { CourseHeader(c, "${lectures.size} lecture${if (lectures.size == 1) "" else "s"}") { editCover = true } } }
            item {
                OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Course subject PDF", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("Syllabus / glossary / previous summaries. Sent with every lecture in this course as context.",
                            style = MaterialTheme.typography.bodySmall)
                        Text(course?.contextPdfUri?.let { displayName(ctx, it) } ?: "None attached",
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { subjectPicker.launch(arrayOf("application/pdf")) }) {
                                Text(if (course?.contextPdfUri == null) "Attach PDF" else "Replace PDF")
                            }
                            course?.contextPdfUri?.let { u -> TextButton(onClick = { openDoc(ctx, u) }) { Text("Open PDF") } }
                            if (course?.contextPdfUri != null) TextButton(onClick = {
                                app.repo.updateCourse(courseId) { it.copy(contextPdfUri = null) }
                            }) { Text("Remove PDF") }
                        }
                    }
                }
            }
            if (lectures.isEmpty()) item { Text("🎙️  No lectures yet.\nTap “New lecture” to record, or import an audio file.", Modifier.fillMaxWidth().padding(32.dp),
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(lectures, key = { it.id }) { l ->
                NotebookCard(l.title,
                    "${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(l.recordedAtMs))} · " +
                        "${formatElapsed(l.segments.sumOf { it.durationMs })} · ${l.markers.size} markers",
                    course?.coverColorValue() ?: coverColor(l.courseId), badge = stateLabel(l.state), pattern = patternOf(course?.pattern)) {
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
    if (editCover && course != null) NotebookEditor(course, onDismiss = { editCover = false }) { name, e, col, pat ->
        app.repo.updateCourse(courseId) { it.copy(name = name, emoji = e, color = col, pattern = pat) }; editCover = false
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
            Text(formatElapsed(if (active) rec.elapsedMs else prior), fontSize = 60.sp, fontWeight = FontWeight.Light,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
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
                    Text("Next: add materials & upload")
                } else TextButton(onClick = onDone) { Text("Have an audio file instead? Import audio →") }
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


@Composable
fun CopyableId(label: String, value: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Row(modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$label ${value.take(8)}", style = MaterialTheme.typography.labelSmall, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(modifier = Modifier.size(28.dp), onClick = {
            val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
            Toast.makeText(ctx, "$label id copied", Toast.LENGTH_SHORT).show()
        }) { Icon(androidx.compose.material.icons.Icons.Filled.ContentCopy, contentDescription = "Copy $label id", modifier = Modifier.size(16.dp)) }
    }
}

/** Long server errors (e.g. Gemini 429 JSON) are truncated to ~280 chars with a Show more toggle. */
@Composable
internal fun ErrorText(msg: String, max: Int = 280) {
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

/** Lecture row: inherits a lighter version of the course cover (pale pattern + colored spine). */
@Composable
fun NotebookCard(title: String, subtitle: String, color: Color, badge: String? = null, pattern: CoverPattern = CoverPattern.SOLID, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Box(Modifier.height(IntrinsicSize.Min)) {
            CoverBackground(color, pattern, light = true, modifier = Modifier.matchParentSize())
            Row {
                Box(Modifier.width(6.dp).fillMaxHeight().background(color))
                Column(Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    badge?.let {
                        Text(it, Modifier.padding(top = 2.dp).clip(RoundedCornerShape(50)).background(color).padding(horizontal = 10.dp, vertical = 3.dp),
                            color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
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
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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


/** SAF "Save as…" (ACTION_CREATE_DOCUMENT): user picks a location, then the source URI's bytes are copied there. */
private class CreateDoc : androidx.activity.result.contract.ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: android.content.Context, input: Pair<String, String>) =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(input.second).putExtra(Intent.EXTRA_TITLE, input.first)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
}

@Composable
fun rememberDownloader(): (Uri, String, String) -> Unit {
    val ctx = LocalContext.current
    var src by remember { mutableStateOf<Uri?>(null) }
    val launcher = rememberLauncherForActivityResult(CreateDoc()) { dest ->
        val from = src; src = null
        if (dest == null || from == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            ctx.contentResolver.openInputStream(from)!!.use { i -> ctx.contentResolver.openOutputStream(dest)!!.use { o -> i.copyTo(o) } }
        }.isSuccess
        Toast.makeText(ctx, if (ok) "Saved" else "Save failed", Toast.LENGTH_SHORT).show()
    }
    return { uri, name, mime -> src = uri; launcher.launch(name to mime) }
}
