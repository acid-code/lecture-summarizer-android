package il.ac.tau.lecturesummarizer.ui

import il.ac.tau.lecturesummarizer.data.allContext
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.data.ApiProvider
import il.ac.tau.lecturesummarizer.data.Lecture
import il.ac.tau.lecturesummarizer.data.LectureState
import il.ac.tau.lecturesummarizer.data.allSlides
import il.ac.tau.lecturesummarizer.data.audioMime
import il.ac.tau.lecturesummarizer.upload.JobPollWorker
import il.ac.tau.lecturesummarizer.upload.UploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/*
 * Button hierarchy (one style per role, verb-first labels, never one control doing two things):
 *   Filled   = the main next step          (“Upload & summarize”, “Open study PDF”)
 *   Tonal    = secondary positive action   (“Listen & read transcript”, “Share PDF”)
 *   Outlined = adds/changes/fixes          (“Upload slides”, “Import audio”, “Save PDF”, “Retry job”, “Re-summarize”)
 *   Text     = low-emphasis / navigation   (“Edit materials”, “Re-download PDF”)
 */
private val SLIDE_MIMES = arrayOf("application/pdf",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/vnd.ms-powerpoint")

@Composable
private fun BtnContent(icon: ImageVector?, text: String) {
    icon?.let { Icon(it, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
    Text(text, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}
@Composable fun PrimaryAction(text: String, icon: ImageVector?, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    Button(onClick, modifier.heightIn(min = 52.dp), enabled = enabled) { BtnContent(icon, text) }
@Composable fun TonalAction(text: String, icon: ImageVector?, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    FilledTonalButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled) { BtnContent(icon, text) }
@Composable fun OutlineAction(text: String, icon: ImageVector?, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    OutlinedButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled) { BtnContent(icon, text) }

@Composable
fun SectionCard(title: String, icon: ImageVector, trailing: (@Composable () -> Unit)? = null, onHeaderClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().then(if (onHeaderClick != null) Modifier.clickable(onClick = onHeaderClick) else Modifier), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            content()
        }
    }
}

// ---------- Materials (uploaded / attached files) ----------

data class Material(val key: String, val name: String, val detail: String, val icon: ImageVector, val uri: Uri, val mime: String, val removable: Boolean)

fun materialsOf(ctx: android.content.Context, l: Lecture): List<Material> {
    val legacyUploaded = l.uploadedSlides.isEmpty() && l.jobId != null
    val docs = l.allSlides.map { s ->
        val name = displayName(ctx, s); val u = Uri.parse(s)
        val mime = runCatching { ctx.contentResolver.getType(u) }.getOrNull() ?: if (name.endsWith(".pdf", true)) "application/pdf" else "application/octet-stream"
        val up = s in l.uploadedSlides || legacyUploaded
        Material(s, name, (if (mime.contains("pdf")) "PDF" else "Slides") + if (up) " · uploaded" else " · not uploaded yet",
            if (mime.contains("pdf")) Icons.Default.PictureAsPdf else Icons.Default.Slideshow, u, mime, true)
    }
    val audio = l.segments.sortedBy { it.seq }.mapNotNull { seg ->
        val f = File(seg.path); if (!f.exists()) return@mapNotNull null
        Material("seg-${seg.seq}", "Audio part ${seg.seq + 1}", "${formatElapsed(seg.durationMs)} · ${if (seg.uploaded) "uploaded" else "not uploaded yet"}",
            Icons.Default.GraphicEq, FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f), audioMime(f), false)
    }
    return docs + audio
}

@Composable
fun MaterialsList(items: List<Material>, onSave: (Material) -> Unit, onRemove: ((Material) -> Unit)? = null) {
    val ctx = LocalContext.current
    if (items.isEmpty()) Text("No files yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    items.forEach { m ->
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()
            .clickable { safeStart(ctx, Intent(Intent.ACTION_VIEW).setDataAndType(m.uri, m.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }) {
            Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(m.icon, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(m.name, style = MaterialTheme.typography.bodyLarge)
                    Text(m.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onSave(m) }) { Icon(Icons.Default.Download, "Save ${m.name}") }
                if (onRemove != null && m.removable) IconButton(onClick = { onRemove(m) }) { Icon(Icons.Default.Close, "Remove ${m.name}") }
            }
        }
    }
    if (items.isNotEmpty()) Text("Tap a file to open it.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun persist(ctx: android.content.Context, uri: Uri) = runCatching {
    ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

private fun addSlides(app: LectureApp, lectureId: String, uris: List<Uri>) = app.repo.updateLecture(lectureId) { x ->
    val new = uris.map { it.toString() }.filterNot { it in x.allSlides }
    if (x.slidesUri == null && new.isNotEmpty()) x.copy(slidesUri = new.first(), extraSlides = x.extraSlides + new.drop(1))
    else x.copy(extraSlides = x.extraSlides + new)
}

private fun removeSlide(app: LectureApp, lectureId: String, s: String) = app.repo.updateLecture(lectureId) { x ->
    val rest = x.allSlides - s
    x.copy(slidesUri = rest.firstOrNull(), extraSlides = rest.drop(1), uploadedSlides = x.uploadedSlides - s)
}

/** Delete a lecture: cancel its workers, delete it on the server (jobs + files), then remove it from the phone. */
fun deleteLectureEverywhere(app: LectureApp, ctx: android.content.Context, lectureId: String, onDone: () -> Unit) {
    val l = app.repo.lecture(lectureId) ?: return onDone()
    val wm = androidx.work.WorkManager.getInstance(ctx)
    wm.cancelUniqueWork("upload-$lectureId"); wm.cancelUniqueWork("poll-$lectureId")
    if (il.ac.tau.lecturesummarizer.recording.RecorderService.state.value.let { it.recording && it.lectureId == lectureId })
        il.ac.tau.lecturesummarizer.recording.RecorderService.stop(ctx)
    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
        val res = l.remoteId?.let { rid -> runCatching { ApiProvider.get(app.settings).deleteLecture(rid) } }
        app.repo.deleteLecture(lectureId)
        launch(Dispatchers.Main) {
            Toast.makeText(ctx, if (res?.isFailure == true) "Deleted on phone — server cleanup failed: ${res.exceptionOrNull()?.message}" else "Lecture deleted", Toast.LENGTH_LONG).show()
            onDone()
        }
    }
}

@Composable
fun DeleteLectureDialog(title: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    androidx.compose.material3.AlertDialog(onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Delete lecture?") },
        text = { Text("“$title” will be deleted from this phone and the server: recording, materials, transcript and study PDF. This can't be undone.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

// ---------- Attach & upload ----------

@Composable
fun AttachUploadScreen(app: LectureApp, lectureId: String, onBack: () -> Unit, onUploaded: () -> Unit, onListen: () -> Unit = {}, onRecord: () -> Unit = {}) {
    val ctx = LocalContext.current
    val all by app.repo.lectures.collectAsStateWithLifecycle()
    val l = all.firstOrNull { it.id == lectureId } ?: return
    val slidesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { persist(ctx, it) }; addSlides(app, lectureId, uris)
    }
    val contextPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { persist(ctx, it); app.repo.updateLecture(lectureId) { x -> x.copy(contextPdfUri = it.toString()) } }
    }
    val scope = rememberCoroutineScope()
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch(Dispatchers.IO) {
            uris.forEach { uri -> runCatching { importAudio(app, lectureId, uri, ctx) }.onFailure { e ->
                app.repo.updateLecture(lectureId) { it.copy(error = "Import failed: ${e.message}") } } }
        }
    }
    val download = rememberDownloader()
    val courseCtx = app.repo.course(l.courseId)?.contextPdfUri
    val hasMaterials = l.allSlides.isNotEmpty() || l.contextPdfUri != null || app.repo.course(l.courseId)?.allContext?.isNotEmpty() == true
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) DeleteLectureDialog(l.title, { confirmDelete = false }) { confirmDelete = false; deleteLectureEverywhere(app, ctx, lectureId, onBack) }
    AppScaffold("Materials", onBack = onBack, actions = { IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete lecture") } }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(l.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("${l.segments.size} audio part${if (l.segments.size == 1) "" else "s"} · ${formatElapsed(l.segments.sumOf { it.durationMs })} · ${l.markers.size} markers · ${l.allSlides.size} slide file${if (l.allSlides.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if ((l.state == LectureState.RECORDED || l.state == LectureState.RECORDING) && l.segments.any { File(it.path).exists() }) {
                Button(onClick = onRecord, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("▶ Resume recording (after a break)") }
                Text("Stopped by mistake during a break? Resume adds new segments to this same lecture.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SectionCard("Files for this lecture", Icons.Default.Folder) {
                MaterialsList(materialsOf(ctx, l), onSave = { download(it.uri, it.name, it.mime) },
                    onRemove = { m -> removeSlide(app, lectureId, m.key) })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlineAction("Add slides / PDFs", Icons.Default.NoteAdd, Modifier.weight(1f)) { slidesPicker.launch(SLIDE_MIMES) }
                    OutlineAction("Import audio", Icons.Default.LibraryMusic, Modifier.weight(1f)) { audioPicker.launch(arrayOf("audio/*", "video/mp4")) }
                }
                if (l.segments.any { File(it.path).exists() }) TextButton(onClick = onListen) { BtnContent(Icons.Default.Headphones, "Listen to recording") }
            }
            SectionCard("Course context PDF", Icons.Default.MenuBook) {
                Text(l.contextPdfUri?.let { displayName(ctx, it) }
                    ?: courseCtx?.let { "Using the notebook's PDF: ${displayName(ctx, it)}" } ?: "None — attach one on the notebook screen",
                    style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlineAction(if (l.contextPdfUri == null) "Use another PDF" else "Replace PDF", Icons.Default.SwapHoriz) { contextPicker.launch(arrayOf("application/pdf")) }
                    (l.contextPdfUri ?: courseCtx)?.let { u -> TextButton(onClick = { openDoc(ctx, u) }) { Text("Open PDF") } }
                }
            }
            if (app.settings.mockMode) Text("Mock mode is ON — nothing leaves the phone.", color = MaterialTheme.colorScheme.tertiary)
            l.error?.let { ErrorText("Last error: $it (will retry)") }
            PrimaryAction(if (l.segments.isEmpty()) "Summarize from slides" else "Upload & summarize", Icons.Default.CloudUpload, Modifier.fillMaxWidth(),
                enabled = l.segments.isNotEmpty() || hasMaterials) {
                if (l.state == LectureState.DRAFT) app.repo.updateLecture(lectureId) { it.copy(state = LectureState.RECORDED) }
                UploadWorker.enqueue(ctx, lectureId); onUploaded()
            }
            Text(when {
                l.segments.isNotEmpty() && hasMaterials -> "Summary uses the recording + slides together."
                l.segments.isNotEmpty() -> "Summary from the recording. Add slides / PDFs for better terms and formulas."
                hasMaterials -> "No recording needed — a study summary PDF is written from the slides / PDFs alone. Audio is optional."
                else -> "Add slides / PDFs, or record / import audio."
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------- Lecture (status + materials + study PDF + listen) ----------

@Composable
fun JobScreen(app: LectureApp, lectureId: String, onBack: () -> Unit, onAttach: () -> Unit = {}, onListen: () -> Unit = {}) {
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
                app.repo.updateLecture(lectureId) { it.copy(stage = st.stage, progress = st.progress, error = st.error, stageDetail = st.detail) }
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

    val download = rememberDownloader()
    // "Upload slides" after the fact: add decks to the lecture and send them right away (then Re-summarize uses them).
    var uploading by remember { mutableStateOf(false) }
    var reprocessing by remember { mutableStateOf(false) }
    val lateSlides = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        uris.forEach { persist(ctx, it) }; addSlides(app, lectureId, uris)
        val remote = l.remoteId ?: return@rememberLauncherForActivityResult
        uploading = true
        scope.launch(Dispatchers.IO) {
            val api = ApiProvider.get(app.settings)
            val res = runCatching {
                uris.forEach { u ->
                    val s = u.toString()
                    ctx.contentResolver.openInputStream(u)?.let { api.uploadSlides(remote, displayName(ctx, s), ctx.contentResolver.getType(u) ?: "application/pdf", it) }
                    app.repo.updateLecture(lectureId) { x -> x.copy(uploadedSlides = (if (x.uploadedSlides.isEmpty()) x.allSlides - uris.map { it.toString() }.toSet() else x.uploadedSlides) + s) }
                }
            }
            uploading = false
            launch(Dispatchers.Main) {
                Toast.makeText(ctx, res.fold({ "Uploaded — tap Re-summarize to include them" }, { "Upload failed: ${it.message}" }), Toast.LENGTH_LONG).show()
            }
        }
    }
    val processing = l.state == LectureState.UPLOADING || l.state == LectureState.PROCESSING
    val materials = materialsOf(ctx, l)

    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) DeleteLectureDialog(l.title, { confirmDelete = false }) { confirmDelete = false; deleteLectureEverywhere(app, ctx, lectureId, onBack) }
    AppScaffold("Lecture", onBack = onBack, actions = { IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete lecture") } }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // 1) Notebook header + status
            val course = app.repo.course(l.courseId)
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    course?.let { Text("${it.coverEmoji()}  ${it.name}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                    // Title + state on the left, compact ids stacked on the right (no extra row).
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(l.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        }
                        if (l.jobId != null || l.remoteId != null) Column(horizontalAlignment = Alignment.End) {
                            l.jobId?.let { CopyableId("Job", it) }
                            l.remoteId?.let { CopyableId("Lecture", it) }
                        }
                    }
                    // Status row spans the full card width: compact chip + detail filling the remaining space.
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SuggestionChip(onClick = {}, label = { Text(stateLabel(l.state), style = MaterialTheme.typography.labelMedium) },
                            modifier = Modifier.height(28.dp))
                        (l.stageDetail ?: l.stage)?.takeIf { processing }?.let {
                            Text(if (l.stageDetail != null) it else "Stage: $it", modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                    if (processing) {
                        LinearProgressIndicator(progress = { l.progress / 100f }, modifier = Modifier.fillMaxWidth())
                        Text((if (l.segments.isEmpty()) "Slides-only summary" else "Uploaded audio parts: ${l.segments.count { it.uploaded }} / ${l.segments.size}") + " · you'll get a notification when it's done.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    l.error?.let { ErrorText(it) }
                }
            }

            // 2) Materials
            val matPrefs = remember { ctx.getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE) }
            var matOpen by remember(lectureId) { mutableStateOf(matPrefs.getBoolean("materials_open_$lectureId", false)) }
            SectionCard("Materials", Icons.Default.Folder,
                onHeaderClick = { matOpen = !matOpen; matPrefs.edit().putBoolean("materials_open_$lectureId", matOpen).apply() },
                trailing = { Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${materials.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(if (matOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (matOpen) "Collapse" else "Expand", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                } }) {
              if (matOpen) {
                MaterialsList(materials, onSave = { download(it.uri, it.name, it.mime) })
                if (l.remoteId != null) OutlineAction(if (uploading) "Uploading…" else "Upload slides", Icons.Default.UploadFile, Modifier.fillMaxWidth(), enabled = !uploading) { lateSlides.launch(SLIDE_MIMES) }
                if (materials.any { it.icon == Icons.Default.GraphicEq }) OutlineAction("Share audio", Icons.Default.Share, Modifier.fillMaxWidth()) { shareRecording(ctx, l.segments) }
              }
            }

            // 3) Study PDF
            val pdf = l.pdfPath?.let(::File)?.takeIf { it.exists() }
            SectionCard("Study PDF", Icons.Default.AutoStories) {
                if (pdf == null) Text(if (processing) "Being written… it appears here when ready." else if (l.state == LectureState.FAILED) "Not created — see the error above, then Retry job." else "Not ready yet.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", pdf)
                    PrimaryAction("Open study PDF", Icons.AutoMirrored.Filled.OpenInNew, Modifier.fillMaxWidth()) {
                        safeStart(ctx, Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TonalAction("Share PDF", Icons.Default.Share, Modifier.weight(1f)) {
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf")
                                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share PDF"))
                        }
                        OutlineAction("Save PDF", Icons.Default.Download, Modifier.weight(1f)) {
                            download(uri, "${l.title.ifBlank { "lecture" }} - summary.pdf", "application/pdf")
                        }
                    }
                }
            }

            // 4) Listen / transcript
            val audio = l.segments.count { File(it.path).exists() }
            if (audio > 0 || l.remoteId != null) SectionCard("Listen & transcript", Icons.Default.Headphones) {
                Text(if (audio > 0) "Play the recording with a synced, tappable transcript." else "Read the transcript (audio was not kept on this phone).",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TonalAction("Listen & read transcript", Icons.Default.PlayArrow, Modifier.fillMaxWidth(), onClick = onListen)
            }

            // 5) Secondary actions
            val jid = l.jobId
            val canRetryJob = l.state == LectureState.FAILED && jid != null && l.segments.all { it.uploaded } && l.remoteId != null
            val canResummarize = jid != null && (l.state == LectureState.DONE || l.state == LectureState.FAILED)
            if (l.state == LectureState.FAILED || canResummarize) SectionCard("Fix or redo", Icons.Default.Build) {
                if (l.state == LectureState.FAILED) {
                    // Upload finished and a server job exists → server-side failure: resume via /retry (keeps finished stages).
                    if (canRetryJob) OutlineAction("Retry job", Icons.Default.Refresh, Modifier.fillMaxWidth()) {
                        scope.launch {
                            runCatching { ApiProvider.get(app.settings).retryJob(jid!!) }
                                .onSuccess { st -> app.repo.updateLecture(lectureId) { it.copy(state = LectureState.PROCESSING, stage = st.stage, progress = st.progress, error = null) }; JobPollWorker.enqueue(ctx, lectureId) }
                                .onFailure { e -> app.repo.updateLecture(lectureId) { it.copy(error = "Retry failed: ${e.message}") } }
                        }
                    } else OutlineAction("Retry upload", Icons.Default.CloudUpload, Modifier.fillMaxWidth()) { UploadWorker.enqueue(ctx, lectureId) }
                }
                if (canResummarize) {
                    TonalAction(if (reprocessing) "Reprocessing…" else if (l.segments.isEmpty()) "Reprocess summary from slides" else "Reprocess audio & summary", Icons.Default.Replay, Modifier.fillMaxWidth(), enabled = !reprocessing) {
                        reprocessing = true
                        scope.launch(Dispatchers.IO) {
                            val api = ApiProvider.get(app.settings)
                            val msg = runCatching {
                                var r = api.reprocessJob(jid!!)
                                val local = l.segments.sortedBy { it.seq }.filter { File(it.path).exists() }
                                if (r.status == null && r.audioMissing && l.remoteId != null && local.isNotEmpty() && local.size == l.segments.size) {
                                    // Server deleted the raw audio after the last run, but the phone still has it: re-upload, then full redo.
                                    local.forEach { api.uploadSegment(l.remoteId!!, it, File(it.path)) }
                                    r = api.reprocessJob(jid)
                                }
                                if (r.status == null && r.audioMissing) r = api.reprocessJob(jid, fallback = true)
                                val st = r.status ?: error(r.error ?: "server refused")
                                app.repo.updateLecture(lectureId) { it.copy(state = LectureState.PROCESSING, stage = st.stage, progress = st.progress, error = null, pdfPath = null) }
                                JobPollWorker.enqueue(ctx, lectureId)
                                if (r.mode == "full") "Reprocessing: new transcription + summary"
                                else "Audio is no longer on the server or phone — re-summarizing from the existing transcript instead"
                            }.getOrElse { e -> app.repo.updateLecture(lectureId) { it.copy(error = "Reprocess failed: ${e.message}") }; "Reprocess failed: ${e.message}" }
                            reprocessing = false
                            launch(Dispatchers.Main) { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
                        }
                    }
                    Text("Reprocess redoes everything — transcription (ASR), term repair, summary and PDF — from the uploaded audio and materials. If the audio is gone it falls back to Re-summarize.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlineAction("Re-summarize", Icons.Default.AutoAwesome, Modifier.fillMaxWidth()) {
                        scope.launch {
                            runCatching { ApiProvider.get(app.settings).resummarizeJob(jid!!) }
                                .onSuccess { st -> app.repo.updateLecture(lectureId) { it.copy(state = LectureState.PROCESSING, stage = st.stage, progress = st.progress, error = null, pdfPath = null) }; JobPollWorker.enqueue(ctx, lectureId) }
                                .onFailure { e -> app.repo.updateLecture(lectureId) { it.copy(error = "Re-summarize failed: ${e.message}") } }
                        }
                    }
                    Text("Re-summarize rewrites the summary + PDF from the existing transcript and current slides (no new transcription).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row {
                    if (l.state == LectureState.DONE) TextButton(onClick = { app.repo.updateLecture(lectureId) { it.copy(state = LectureState.PROCESSING) } }) { Text("Re-download PDF") }
                    if (l.state == LectureState.FAILED) TextButton(onClick = onAttach) { Text("Edit materials") }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
