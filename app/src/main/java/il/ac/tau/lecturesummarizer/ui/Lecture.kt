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
internal val SLIDE_MIMES = arrayOf("application/pdf",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/vnd.ms-powerpoint")

@Composable
private fun BtnContent(icon: ImageVector?, text: String) {
    icon?.let { Icon(it, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
    Text(text, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}
@Composable fun PrimaryAction(text: String, icon: ImageVector?, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    Button(hapticAction(onClick), modifier.heightIn(min = 52.dp), enabled = enabled) { BtnContent(icon, text) }
@Composable fun TonalAction(text: String, icon: ImageVector?, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    FilledTonalButton(hapticAction(onClick), modifier.heightIn(min = 48.dp), enabled = enabled) { BtnContent(icon, text) }
@Composable fun OutlineAction(text: String, icon: ImageVector?, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    OutlinedButton(hapticAction(onClick), modifier.heightIn(min = 48.dp), enabled = enabled) { BtnContent(icon, text) }

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
        Material(s, name, ctx.getString(il.ac.tau.lecturesummarizer.R.string.material_state, if(mime.contains("pdf"))"PDF"else ctx.getString(il.ac.tau.lecturesummarizer.R.string.file_slides), ctx.getString(il.ac.tau.lecturesummarizer.R.string.material_uploaded) .takeIf{up}.orEmpty().ifEmpty{ctx.getString(il.ac.tau.lecturesummarizer.R.string.material_not_uploaded)}),
            if (mime.contains("pdf")) Icons.Default.PictureAsPdf else Icons.Default.Slideshow, u, mime, true)
    }
    val audio = l.segments.sortedBy { it.seq }.mapNotNull { seg ->
        val f = File(seg.path); if (!f.exists()) return@mapNotNull null
        Material("seg-${seg.seq}", ctx.getString(il.ac.tau.lecturesummarizer.R.string.audio_part, seg.seq+1), ctx.getString(il.ac.tau.lecturesummarizer.R.string.material_state, ltr(formatElapsed(seg.durationMs)), ctx.getString(il.ac.tau.lecturesummarizer.R.string.material_uploaded).takeIf{seg.uploaded}.orEmpty().ifEmpty{ctx.getString(il.ac.tau.lecturesummarizer.R.string.material_not_uploaded)}),
            Icons.Default.GraphicEq, runCatching { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f) }.getOrNull() ?: return@mapNotNull null, audioMime(f), false)
    }
    return docs + audio
}

@Composable
fun MaterialsList(items: List<Material>, onSave: (Material) -> Unit, onRemove: ((Material) -> Unit)? = null) {
    val ctx = LocalContext.current
    if (items.isEmpty()) Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_no_files_yet), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    items.forEach { m ->
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()
            .clickable { safeStart(ctx, Intent(Intent.ACTION_VIEW).setDataAndType(m.uri, m.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }) {
            Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(m.icon, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(bidi(m.name), style = MaterialTheme.typography.bodyLarge)
                    Text(m.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onSave(m) }) { Icon(Icons.Default.Download, androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.material_save, bidi(m.name))) }
                if (onRemove != null && m.removable) IconButton(onClick = { onRemove(m) }) { Icon(Icons.Default.Close, androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.material_remove, bidi(m.name))) }
            }
        }
    }
    if (items.isNotEmpty()) Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_tap_a_file_to_open_it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal fun persist(ctx: android.content.Context, uri: Uri) = runCatching {
    ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

internal fun addSlides(app: LectureApp, lectureId: String, uris: List<Uri>) {
    if (uris.isNotEmpty()) il.ac.tau.lecturesummarizer.slides.SlideChoices(app).manual(lectureId)
    app.repo.updateLecture(lectureId) { x ->
        val new = uris.map { it.toString() }.filterNot { it in x.allSlides }
        if (x.slidesUri == null && new.isNotEmpty()) x.copy(slidesUri = new.first(), extraSlides = x.extraSlides + new.drop(1))
        else x.copy(extraSlides = x.extraSlides + new)
    }
    // Manual Materials update → offer to re-summarize (only matters once a summary job exists).
    MaterialsUpdates.mark(lectureId, uris.map { it.toString() })
}

/** Manually attached materials not yet folded into a summary, per lecture (in-memory; drives the "Re-summarize?" offer). */
object MaterialsUpdates {
    val pending = kotlinx.coroutines.flow.MutableStateFlow<Map<String, List<String>>>(emptyMap())
    fun mark(id: String, uris: List<String>) { if (uris.isNotEmpty()) pending.value = pending.value + (id to ((pending.value[id] ?: emptyList()) + uris).distinct()) }
    fun clear(id: String) { pending.value = pending.value - id }
}

/** "Materials updated — re-summarize?" Shown once per batch of manual attaches when a finished/failed summary job exists and nothing is processing. */
@Composable
internal fun ResummarizeOffer(app: LectureApp, l: Lecture) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pend by MaterialsUpdates.pending.collectAsStateWithLifecycle()
    val uris = pend[l.id].orEmpty()
    val busy = l.state == LectureState.UPLOADING || l.state == LectureState.PROCESSING
    val hasJob = l.jobId != null && l.remoteId != null && (l.state == LectureState.DONE || l.state == LectureState.FAILED)
    LaunchedEffect(uris, busy, hasJob) {
        if (uris.isEmpty()) return@LaunchedEffect
        if (busy) { MaterialsUpdates.clear(l.id); Toast.makeText(ctx, ctx.getString(il.ac.tau.lecturesummarizer.R.string.materials_busy), Toast.LENGTH_LONG).show() }
        else if (!hasJob) MaterialsUpdates.clear(l.id)  // no summary yet → new files go in with the regular upload
    }
    if (uris.isEmpty() || busy || !hasJob) return
    AlertDialog(onDismissRequest = { MaterialsUpdates.clear(l.id) },
        icon = { Icon(Icons.Default.AutoAwesome, null) },
        title = { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.slides_update_question)) },
        text = { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.slides_no_asr)) },
        confirmButton = { Button(onClick = {
            MaterialsUpdates.clear(l.id)
            // Not rememberCoroutineScope: clearing pending removes this dialog from composition and cancels that scope
            // mid-request ("StandaloneCoroutine was cancelled"). Run detached like the other lecture actions.
            il.ac.tau.lecturesummarizer.slides.SummaryRefreshWorker.enqueue(app,l.id)

        }) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.slides_update)) } },
        dismissButton = { TextButton(onClick = { MaterialsUpdates.clear(l.id) }) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.skip)) } })
}

private fun removeSlide(app: LectureApp, lectureId: String, s: String) {
    il.ac.tau.lecturesummarizer.slides.SlideChoices(app).manual(lectureId)
    app.repo.updateLecture(lectureId) { x ->
    val rest = x.allSlides - s
    x.copy(slidesUri = rest.firstOrNull(), extraSlides = rest.drop(1), uploadedSlides = x.uploadedSlides - s)
    }
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
            Toast.makeText(ctx, if (res?.isFailure == true) ctx.getString(il.ac.tau.lecturesummarizer.R.string.delete_partial) else ctx.getString(il.ac.tau.lecturesummarizer.R.string.lecture_deleted), Toast.LENGTH_LONG).show()
            onDone()
        }
    }
}

/** Delete a notebook: stop its workers/recording, delete each lecture + the course on the server, then remove locally. */
fun deleteCourseEverywhere(app: LectureApp, ctx: android.content.Context, courseId: String, onDone: () -> Unit = {}) {
    val c = app.repo.course(courseId) ?: return onDone()
    val ls = app.repo.lectures.value.filter { it.courseId == courseId }
    val wm = androidx.work.WorkManager.getInstance(ctx)
    ls.forEach { l ->
        wm.cancelUniqueWork("upload-${l.id}"); wm.cancelUniqueWork("poll-${l.id}")
        if (il.ac.tau.lecturesummarizer.recording.RecorderService.state.value.let { it.recording && it.lectureId == l.id })
            il.ac.tau.lecturesummarizer.recording.RecorderService.stop(ctx)
    }
    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
        val api = runCatching { ApiProvider.get(app.settings) }.getOrNull()
        var failed: Throwable? = null
        if (api != null) {
            ls.forEach { l -> l.remoteId?.let { rid -> runCatching { api.deleteLecture(rid) }.onFailure { failed = it } } }
            c.remoteId?.let { rid -> runCatching { api.deleteCourse(rid) }.onFailure { failed = it } }
        }
        app.repo.deleteCourse(courseId)
        launch(Dispatchers.Main) {
            Toast.makeText(ctx, if (failed != null) ctx.getString(il.ac.tau.lecturesummarizer.R.string.delete_partial) else ctx.getString(il.ac.tau.lecturesummarizer.R.string.course_deleted), Toast.LENGTH_LONG).show()
            onDone()
        }
    }
}

@Composable
fun DeleteCourseDialog(name: String, lectureCount: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    androidx.compose.material3.AlertDialog(onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_delete_notebook)) },
        text = { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.delete_course_explanation, bidi(name),lectureCount)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_delete_notebook_026), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_cancel)) } })
}

@Composable
fun DeleteLectureDialog(title: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    androidx.compose.material3.AlertDialog(onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_delete_lecture)) },
        text = { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.delete_lecture_explanation, bidi(title))) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_cancel)) } })
}

// ---------- Attach & upload ----------

@Composable
fun AttachUploadScreen(app: LectureApp, lectureId: String, onBack: () -> Unit, onUploaded: () -> Unit, onListen: () -> Unit = {}, onRecord: () -> Unit = {}, onRecordAnother: () -> Unit = onRecord, onMoodle: () -> Unit = {}, onPanopto: () -> Unit = {}, onPanoptoLogin: () -> Unit = {}) {
    val ctx = LocalContext.current
    val all by app.repo.lectures.collectAsStateWithLifecycle()
    val l = all.firstOrNull { it.id == lectureId } ?: return
    val srtReady = l.panoptoSrtPath?.let { File(it).exists() } == true
    LaunchedEffect(l.panoptoId, srtReady) { if (l.fromPanopto && !l.panoptoAudio && !srtReady && PanoptoFetch.status[lectureId] == null) PanoptoFetch.start(app, lectureId) }
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
                app.repo.updateLecture(lectureId) { it.copy(error = ctx.getString(il.ac.tau.lecturesummarizer.R.string.import_failed)) } } }
        }
    }
    val download = rememberDownloader()
    val courseCtx = app.repo.course(l.courseId)?.contextPdfUri
    val hasMaterials = l.allSlides.isNotEmpty() || l.contextPdfUri != null || app.repo.course(l.courseId)?.allContext?.isNotEmpty() == true
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) DeleteLectureDialog(l.title, { confirmDelete = false }) { confirmDelete = false; deleteLectureEverywhere(app, ctx, lectureId, onBack) }
    AppScaffold(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_materials), onBack = onBack, actions = { IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.delete_lecture)) } }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(bidi(l.title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.material_counts, l.segments.size,ltr(formatElapsed(l.displayDurationMs)),l.allSlides.size),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if ((l.state == LectureState.RECORDED || l.state == LectureState.RECORDING) && l.segments.any { File(it.path).exists() }) {
                Button(onClick = onRecord, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_resume_recording_after_a_break)) }
                Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_stopped_by_mistake_during_a_break_resuming_adds_segments_to_this),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (l.fromPanopto) SectionCard(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_panopto_recording), Icons.Default.OndemandVideo) {
                Text(bidi(l.panoptoTitle ?: androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.recording_label)), style = MaterialTheme.typography.bodyMedium)
                val st = PanoptoFetch.status[lectureId]
                val ast = il.ac.tau.lecturesummarizer.panopto.PanoptoAudioWorker.status[lectureId]
                when {
                    l.panoptoAudio && l.segments.isNotEmpty() -> Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.audio_extracted, ltr(formatElapsed(l.displayDurationMs))), style = MaterialTheme.typography.bodySmall)
                    l.panoptoAudio && ast != null && ast.startsWith("error:") -> {
                        ErrorText(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.action_failed))
                        if (ast.contains("sign-in")) OutlineAction(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_sign_in_to_panopto), Icons.Default.Login, Modifier.fillMaxWidth()) { onPanoptoLogin() }
                        OutlineAction(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_retry_audio_download), Icons.Default.Refresh, Modifier.fillMaxWidth()) { il.ac.tau.lecturesummarizer.panopto.PanoptoAudioWorker.enqueue(ctx.applicationContext, lectureId, wifiOnly = true) }
                    }
                    l.panoptoAudio && ast == il.ac.tau.lecturesummarizer.panopto.PanoptoAudioWorker.WAIT_WIFI -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.audio_mobile_pause), style = MaterialTheme.typography.bodySmall)
                        OutlineAction(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_continue_using_mobile_data), Icons.Default.Refresh, Modifier.fillMaxWidth()) { il.ac.tau.lecturesummarizer.panopto.PanoptoAudioWorker.enqueue(ctx.applicationContext, lectureId, wifiOnly = false) }
                        OutlineAction(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_wait_for_wi_fi), Icons.Default.Refresh, Modifier.fillMaxWidth()) { il.ac.tau.lecturesummarizer.panopto.PanoptoAudioWorker.cancelPrompt(ctx.applicationContext, lectureId) }
                    }
                    l.panoptoAudio -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.panopto_audio_downloading), style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { (ast?.toIntOrNull() ?: l.progress) / 100f }, modifier = Modifier.fillMaxWidth())
                        if (ast == null) OutlineAction(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_start_audio_download), Icons.Default.Refresh, Modifier.fillMaxWidth()) { il.ac.tau.lecturesummarizer.panopto.PanoptoAudioWorker.enqueue(ctx.applicationContext, lectureId, wifiOnly = true) }
                    }
                    srtReady -> Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_imported_captions_are_used_instead_of_transcribing_audio), style = MaterialTheme.typography.bodySmall)
                    st == PanoptoFetch.AUTH -> {
                        Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_panopto_requires_a_one_time_sign_in), style = MaterialTheme.typography.bodySmall)
                        OutlineAction(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_sign_in_to_panopto), Icons.Default.Login, Modifier.fillMaxWidth()) { onPanoptoLogin() }
                    }
                    st != null && st.startsWith("error:") -> {
                        ErrorText(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.action_failed))
                        if (st.removePrefix("error:") == il.ac.tau.lecturesummarizer.moodle.Panopto.NO_CAPTIONS)
                            OutlineAction(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_record_on_the_phone_instead), Icons.Default.Mic, Modifier.fillMaxWidth()) { onRecord() }
                        OutlineAction(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_retry_captions), Icons.Default.Refresh, Modifier.fillMaxWidth()) { PanoptoFetch.status.remove(lectureId); PanoptoFetch.start(app, lectureId) }
                    }
                    else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_importing_captions_you_can_add_slides_meanwhile), style = MaterialTheme.typography.bodySmall)
                    }
                }
                PanoptoStatusLines(l, srtReady)
                TextButton(onClick = onPanopto) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_choose_another_recording)) }
            }
            SectionCard(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_files_for_this_lecture), Icons.Default.Folder) {
                MaterialsList(materialsOf(ctx, l), onSave = { download(it.uri, it.name, it.mime) },
                    onRemove = { m -> removeSlide(app, lectureId, m.key) })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlineAction(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_add_slides_or_pdfs), Icons.Default.NoteAdd, Modifier.weight(1f)) { slidesPicker.launch(SLIDE_MIMES) }
                    OutlineAction(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_import_audio), Icons.Default.LibraryMusic, Modifier.weight(1f)) { audioPicker.launch(arrayOf("audio/*", "video/mp4")) }
                }
                if (l.segments.isNotEmpty() || l.state == LectureState.RECORDED || l.state == LectureState.RECORDING)
                    OutlineAction(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_record_another_segment_for_this_lecture), Icons.Default.Mic, Modifier.fillMaxWidth()) { onRecordAnother() }
                PrimaryAction(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_attach_from_moodle), Icons.Default.School, Modifier.fillMaxWidth()) { onMoodle() }
                if (!l.fromPanopto) OutlineAction(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_import_from_panopto), Icons.Default.OndemandVideo, Modifier.fillMaxWidth()) { onPanopto() }
                if (l.segments.any { File(it.path).exists() }) TextButton(onClick = onListen) { BtnContent(Icons.Default.Headphones, ctx.getString(il.ac.tau.lecturesummarizer.R.string.listen_recording)) }
            }
            SectionCard(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_course_context_pdf), Icons.Default.MenuBook) {
                Text(l.contextPdfUri?.let { displayName(ctx, it) }
                    ?: courseCtx?.let { ctx.getString(il.ac.tau.lecturesummarizer.R.string.background_using, bidi(displayName(ctx,it))) } ?: ctx.getString(il.ac.tau.lecturesummarizer.R.string.background_none),
                    style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlineAction(if (l.contextPdfUri == null) androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.background_choose) else androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.background_replace), Icons.Default.SwapHoriz) { contextPicker.launch(arrayOf("application/pdf")) }
                    (l.contextPdfUri ?: courseCtx)?.let { u -> TextButton(onClick = { openDoc(ctx, u) }) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_open_pdf)) } }
                }
            }
            if (app.settings.mockMode) Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_mock_mode_is_on_nothing_leaves_the_phone), color = MaterialTheme.colorScheme.tertiary)
            l.error?.let { ErrorText(androidx.compose.ui.res.stringResource(if(it==UploadWorker.INTERRUPTED)il.ac.tau.lecturesummarizer.R.string.upload_paused else il.ac.tau.lecturesummarizer.R.string.action_failed)) }
            PrimaryAction(if (l.segments.isEmpty() && !l.fromPanopto) androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.summarize_slides) else androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.upload_summarize), Icons.Default.CloudUpload, Modifier.fillMaxWidth(),
                enabled = if (l.fromPanopto && l.segments.isEmpty()) srtReady else l.segments.isNotEmpty() || hasMaterials) {
                if (l.state == LectureState.DRAFT) app.repo.updateLecture(lectureId) { it.copy(state = LectureState.RECORDED) }
                UploadWorker.enqueue(ctx, lectureId); onUploaded()
            }
            Text(when {
                l.panoptoAudio && l.segments.isEmpty() -> androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.panopto_audio_downloading)
                l.fromPanopto && l.segments.isEmpty() && !srtReady -> androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.panopto_captions_wait)
                l.fromPanopto && l.segments.isEmpty() -> if (hasMaterials) androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.source_caption_slides) else androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.source_captions)
                l.segments.isNotEmpty() && hasMaterials -> androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.source_audio_slides)
                l.segments.isNotEmpty() -> androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.source_audio)
                hasMaterials -> androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.source_files)
                else -> androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.source_add)
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (l.segments.size > 1) Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_upload_continues_in_the_background_force_stopping_the_app_may_int),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------- Lecture (status + materials + study PDF + listen) ----------

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun JobScreen(app: LectureApp, lectureId: String, onBack: () -> Unit, onAttach: () -> Unit = {}, onListen: () -> Unit = {}, onRecordAnother: () -> Unit = {}, onMoodle: () -> Unit = {}, onPanopto: () -> Unit = {}, onMemory:()->Unit={}, onTutor:()->Unit={}) {
    val ctx = LocalContext.current
    val all by app.repo.lectures.collectAsStateWithLifecycle()
    val l = all.firstOrNull { it.id == lectureId } ?: return
    // Process-lifetime scope (not rememberCoroutineScope): Retry / Re-summarize / Reprocess flip the lecture state, which
    // removes the button's card (or the screen) from composition and used to cancel the request mid-flight, so Retry
    // "stopped working". Actions now run to completion regardless of what's on screen.
    val scope = LectureActions.scope
    DisposableEffect(lectureId) {
        app.unread.openLectureId = lectureId; app.unread.markSeen(lectureId)
        onDispose { if (app.unread.openLectureId == lectureId) app.unread.openLectureId = null }
    }

    // Poll job status while processing; fetch the PDF when done.
    LaunchedEffect(l.jobId,l.state){if(l.jobId!=null && l.state==LectureState.PROCESSING)JobPollWorker.enqueue(app,lectureId)}

    val download = rememberDownloader()
    // androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.add_slides) after the fact: add decks to the lecture and send them right away (then Re-summarize uses them).
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
                Toast.makeText(ctx, res.fold({ ctx.getString(il.ac.tau.lecturesummarizer.R.string.materials_uploaded) }, { ctx.getString(il.ac.tau.lecturesummarizer.R.string.materials_upload_failed) }), Toast.LENGTH_LONG).show()
            }
        }
    }
    val jobAudioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch(Dispatchers.IO) {
            uris.forEach { uri -> runCatching { importAudio(app, lectureId, uri, ctx) }.onFailure { e ->
                app.repo.updateLecture(lectureId) { it.copy(error = ctx.getString(il.ac.tau.lecturesummarizer.R.string.import_failed)) } } }
        }
    }
    val processing = l.state == LectureState.UPLOADING || l.state == LectureState.PROCESSING
    ResummarizeOffer(app, l)
    val materials = materialsOf(ctx, l)

    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) DeleteLectureDialog(l.title, { confirmDelete = false }) { confirmDelete = false; deleteLectureEverywhere(app, ctx, lectureId, onBack) }
    var moveOpen by remember{mutableStateOf(false)}
    if(moveOpen)LectureMoveDialog(app,l){moveOpen=false}
    var overflow by remember{mutableStateOf(false)}
    var sourcesOpen by remember{mutableStateOf(false)}
    var repairOpen by remember{mutableStateOf(false)}
    AppScaffold(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_lecture),onBack=onBack,actions={
        IconButton(enabled=l.pdfPath?.let{File(it).exists()}==true,onClick={shareStudyPdf(ctx,app,l)}){Icon(Icons.Default.Share,ctx.getString(il.ac.tau.lecturesummarizer.R.string.share_pdf))}
        Box{IconButton(onClick={overflow=true}){Icon(Icons.Default.MoreVert,ctx.getString(il.ac.tau.lecturesummarizer.R.string.more_actions))}
            DropdownMenu(expanded=overflow,onDismissRequest={overflow=false}){
                DropdownMenuItem(text={Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.move_to_course))},onClick={overflow=false;moveOpen=true})
                DropdownMenuItem(text={Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_fix_or_redo))},onClick={overflow=false;repairOpen=!repairOpen;sourcesOpen=true})
                DropdownMenuItem(enabled=l.pdfPath?.let{File(it).exists()}==true,text={Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_save_pdf))},onClick={overflow=false;l.pdfPath?.let{path->download(FileProvider.getUriForFile(ctx,ctx.packageName+".files",File(path)),summaryPdfName(app.repo.course(l.courseId)?.name,l.title),"application/pdf")}})
                DropdownMenuItem(text={Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.delete_lecture),color=MaterialTheme.colorScheme.error)},onClick={overflow=false;confirmDelete=true})
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // 1) Notebook header + status
            val course = app.repo.course(l.courseId)
            OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    course?.let { Text("${it.coverEmoji()}  ${it.name}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                    // Title + state on the left, compact ids stacked on the right (no extra row).
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(bidi(l.title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        }
                        if (app.settings.showDeveloper && (l.jobId != null || l.remoteId != null)) Column(horizontalAlignment = Alignment.End) {
                            l.jobId?.let { CopyableId("Job", it) }
                            l.remoteId?.let { CopyableId("Lecture", it) }
                        }
                    }
                    // Status row spans the full card width: compact chip + detail filling the remaining space.
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LectureStatusChip(l, onRetry = { scope.launch(Dispatchers.IO) { runCatching { il.ac.tau.lecturesummarizer.upload.retryLecture(app, l.id) }.onFailure { app.repo.updateLecture(l.id) { it.copy(error = app.getString(il.ac.tau.lecturesummarizer.R.string.retry_failed)) } } } })
                        val variants=rememberVariantState(l.id)
                        if(variants.values.any{it.pending})Text(androidx.compose.ui.res.stringResource(if(variants.values.any{it.pending&&it.stage=="pdf"})il.ac.tau.lecturesummarizer.R.string.summary_variant_pdf else il.ac.tau.lecturesummarizer.R.string.summary_variant_stage),modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                        else if(app.settings.showDeveloper)(l.stageDetail ?: l.stage)?.let{Text(it,modifier=Modifier.weight(1f),maxLines=2)}

                    }
                    if (l.fromPanopto) PanoptoStatusLines(l, l.panoptoSrtPath?.let { File(it).exists() } == true)
                    if (processing) {
                        ProcessingSteps(l)
                        Text(androidx.compose.ui.res.stringResource(if(androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled())il.ac.tau.lecturesummarizer.R.string.processing_leave else il.ac.tau.lecturesummarizer.R.string.processing_return),style=MaterialTheme.typography.bodySmall)
                    }
                    l.error?.let { ErrorText(androidx.compose.ui.res.stringResource(if(it==UploadWorker.INTERRUPTED)il.ac.tau.lecturesummarizer.R.string.upload_paused else il.ac.tau.lecturesummarizer.R.string.action_failed)) }
                    // New local audio (e.g. "Record another") not yet sent: offer upload right away (state is a live flow).
                    val newSegs = l.segments.count { !it.uploaded && File(it.path).exists() }
                    val recNow = il.ac.tau.lecturesummarizer.recording.RecorderService.state.collectAsStateWithLifecycle().value.let { it.recording && it.lectureId == lectureId }
                    if (!processing && !recNow && newSegs > 0) {
                        PrimaryAction(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.new_audio_upload, newSegs), Icons.Default.CloudUpload, Modifier.fillMaxWidth()) {
                            app.repo.updateLecture(lectureId) { it.copy(error = null) }
                            UploadWorker.enqueue(ctx, lectureId)
                        }
                    }
                }
            }

            // 3) Study PDF
            val pdf = l.pdfPath?.let(::File)?.takeIf { it.exists() }
            SectionCard(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_study_pdf), Icons.Default.AutoStories) {
                SummaryFreshnessNotice(app,l)
                SummaryChangeNote(app,l)
                if(pdf==null)Text(if(processing)androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.summary_preparing)else if(l.state==LectureState.FAILED)androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.summary_not_created)else androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.summary_not_ready),style=MaterialTheme.typography.bodyMedium)
                else {
                    LectureSummaryVariants(app,l,onOpen={val uri=FileProvider.getUriForFile(ctx,ctx.packageName+".files",pdf);safeStart(ctx,Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))})

                }
            }

            // 4) Listen / transcript
            val audio = l.segments.count { File(it.path).exists() }
            if (audio > 0 || l.remoteId != null) SectionCard(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_listen_and_transcript), Icons.Default.Headphones) {
                Text(if (audio > 0) androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.listen_help) else androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.transcript_no_audio),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TonalAction(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.listen_transcript), Icons.Default.PlayArrow, Modifier.fillMaxWidth(), onClick = onListen)
            }

            if(l.remoteId!=null||pdf!=null)StudyToolsEntry(onMemory,onTutor)

            OutlinedButton(onClick={sourcesOpen=!sourcesOpen},modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Folder,null);Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.lecture_sources))}
            if(sourcesOpen){
            SlidesCard(app,l,onMoodle)

            // 2) Materials
            val matPrefs = remember { ctx.getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE) }
            var matOpen by remember(lectureId) { mutableStateOf(matPrefs.getBoolean("materials_open_$lectureId", false)) }
            SectionCard(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_materials), Icons.Default.Folder,
                onHeaderClick = { matOpen = !matOpen; matPrefs.edit().putBoolean("materials_open_$lectureId", matOpen).apply() },
                trailing = { Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ltr(materials.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(if (matOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (matOpen) androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.collapse) else androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.expand), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                } }) {
              if (matOpen) {
                MaterialsList(materials, onSave = { download(it.uri, it.name, it.mime) })
                if (l.remoteId != null) OutlineAction(if (uploading) androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.uploading_slides) else androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.add_slides), Icons.Default.UploadFile, Modifier.fillMaxWidth(), enabled = !uploading) { lateSlides.launch(SLIDE_MIMES) }
                if (materials.any { it.icon == Icons.Default.GraphicEq }) OutlineAction(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_share_audio), Icons.Default.Share, Modifier.fillMaxWidth()) { shareRecording(ctx, l.segments) }
                OutlineAction(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_record_another_segment_for_this_lecture), Icons.Default.Mic, Modifier.fillMaxWidth(), enabled = l.state != LectureState.UPLOADING) { onRecordAnother() }
                OutlineAction(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_import_audio), Icons.Default.LibraryMusic, Modifier.fillMaxWidth(), enabled = l.state != LectureState.UPLOADING) { jobAudioPicker.launch(arrayOf("audio/*", "video/mp4")) }
                OutlineAction(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_attach_from_moodle), Icons.Default.School, Modifier.fillMaxWidth()) { onMoodle() }
                OutlineAction(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_import_from_panopto), Icons.Default.OndemandVideo, Modifier.fillMaxWidth(), enabled = l.state != LectureState.UPLOADING) { onPanopto() }
              }
            }

            }
            // 5) Secondary actions
            val jid = l.jobId
            val canRetryJob = l.state == LectureState.FAILED && jid != null && l.segments.all { it.uploaded } && l.remoteId != null
            val canResummarize = jid != null && (l.state == LectureState.DONE || l.state == LectureState.FAILED)
            var confirmForceRestart by remember { mutableStateOf(false) }
            var confirmStopJob by remember { mutableStateOf(false) }
            if (l.state == LectureState.PROCESSING && jid != null) SectionCard(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.job_controls), Icons.Default.Build) {
                OutlineAction(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.job_force_restart), Icons.Default.Refresh, Modifier.fillMaxWidth()) { confirmForceRestart = true }
                OutlineAction(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.job_stop), Icons.Default.Stop, Modifier.fillMaxWidth()) { confirmStopJob = true }
            }
            if (confirmForceRestart) AlertDialog(
                onDismissRequest = { confirmForceRestart = false },
                title = { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.job_force_restart_confirm_title)) },
                text = { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.job_force_restart_confirm_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmForceRestart = false
                        scope.launch(Dispatchers.IO) {
                            runCatching { il.ac.tau.lecturesummarizer.upload.forceRestartLecture(app, lectureId) }
                                .onFailure { app.repo.updateLecture(lectureId) { it.copy(error = ctx.getString(il.ac.tau.lecturesummarizer.R.string.job_retry_failed)) } }
                        }
                    }) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.job_force_restart)) }
                },
                dismissButton = { TextButton(onClick = { confirmForceRestart = false }) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_cancel)) } },
            )
            if (confirmStopJob) AlertDialog(
                onDismissRequest = { confirmStopJob = false },
                title = { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.job_stop_confirm_title)) },
                text = { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.job_stop_confirm_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmStopJob = false
                        scope.launch(Dispatchers.IO) {
                            runCatching { il.ac.tau.lecturesummarizer.upload.stopLectureJob(app, lectureId) }
                                .onFailure { app.repo.updateLecture(lectureId) { it.copy(error = ctx.getString(il.ac.tau.lecturesummarizer.R.string.job_retry_failed)) } }
                        }
                    }) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.job_stop)) }
                },
                dismissButton = { TextButton(onClick = { confirmStopJob = false }) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_cancel)) } },
            )
            if (l.state == LectureState.FAILED || repairOpen && canResummarize) SectionCard(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_fix_or_redo), Icons.Default.Build) {
                if (l.state == LectureState.FAILED) {
                    // Upload finished and a server job exists → server-side failure: resume via /retry (keeps finished stages).
                    if (canRetryJob) OutlineAction(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_retry_processing), Icons.Default.Refresh, Modifier.fillMaxWidth()) {
                        scope.launch {
                            runCatching { il.ac.tau.lecturesummarizer.upload.JobKick.kick(lectureId); ApiProvider.get(app.settings).retryJob(jid!!) }
                                .onSuccess { st -> app.repo.updateLecture(lectureId) { it.copy(state = LectureState.PROCESSING, stage = st.stage.takeUnless { st.state == "done" || st.state == "failed" || il.ac.tau.lecturesummarizer.upload.isTerminalStageText(it) }, stageDetail = null, progress = st.progress, error = null) }; JobPollWorker.enqueue(ctx, lectureId) }
                                .onFailure { e -> app.repo.updateLecture(lectureId) { it.copy(error = ctx.getString(il.ac.tau.lecturesummarizer.R.string.job_retry_failed)) } }
                        }
                    } else OutlineAction(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_retry_upload), Icons.Default.CloudUpload, Modifier.fillMaxWidth()) {
                        app.repo.updateLecture(lectureId) { it.copy(state = LectureState.UPLOADING, error = null) }
                        UploadWorker.enqueue(ctx, lectureId, retry = true) }
                }
                if (canResummarize) {
                    TonalAction(if (reprocessing) androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.reprocessing) else if (l.segments.isEmpty() && l.fromPanopto) androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.reprocess_captions) else if (l.segments.isEmpty()) androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.reprocess_slides) else androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.reprocess_audio), Icons.Default.Replay, Modifier.fillMaxWidth(), enabled = !reprocessing) {
                        reprocessing = true
                        scope.launch(Dispatchers.IO) {
                            val api = ApiProvider.get(app.settings)
                            val msg = runCatching {
                                il.ac.tau.lecturesummarizer.upload.JobKick.kick(lectureId)
                                if (l.fromPanopto && l.segments.isEmpty()) {
                                    // Never let the server redo this as slides-only: re-import the cached Panopto captions as a fresh job.
                                    val srt = l.panoptoSrtPath?.let(::File)?.takeIf { it.exists() }
                                    if (srt != null && l.remoteId != null) {
                                        val segs = il.ac.tau.lecturesummarizer.moodle.Panopto.parseSrt(srt.readText())
                                        val job = api.importTranscript(l.remoteId!!, il.ac.tau.lecturesummarizer.data.TranscriptImport("panopto", l.panoptoId!!, l.panoptoTitle ?: l.title,
                                            il.ac.tau.lecturesummarizer.moodle.Panopto.languageOf(segs), segs)) ?: error("server didn't start a captions job")
                                        app.repo.updateLecture(lectureId) { it.copy(jobId = job, liveJob = false, state = LectureState.PROCESSING, stage = null, stageDetail = null, progress = 0, error = null) }
                                        JobPollWorker.enqueue(ctx, lectureId)
                                        return@runCatching ctx.getString(il.ac.tau.lecturesummarizer.R.string.reprocessing_caption_slides)
                                    }
                                    // No local captions: re-summarize keeps the server's Panopto transcript.
                                    val st = api.resummarizeJob(jid!!)
                                    app.repo.updateLecture(lectureId) { it.copy(state = LectureState.PROCESSING, stage = null, stageDetail = null, progress = st.progress, error = null) }
                                    JobPollWorker.enqueue(ctx, lectureId)
                                    return@runCatching ctx.getString(il.ac.tau.lecturesummarizer.R.string.reprocessing_caption_slides)
                                }
                                var r = api.reprocessJob(jid!!)
                                val local = l.segments.sortedBy { it.seq }.filter { File(it.path).exists() }
                                if (r.status == null && r.audioMissing && l.remoteId != null && local.isNotEmpty() && local.size == l.segments.size) {
                                    // Server deleted the raw audio after the last run, but the phone still has it: re-upload, then full redo.
                                    local.forEach { api.uploadSegment(l.remoteId!!, it, File(it.path)) }
                                    r = api.reprocessJob(jid)
                                }
                                if (r.status == null && r.audioMissing) r = api.reprocessJob(jid, fallback = true)
                                val st = r.status ?: error(r.error ?: "server refused")
                                app.repo.updateLecture(lectureId) { it.copy(state = LectureState.PROCESSING, stage = st.stage.takeUnless { st.state == "done" || st.state == "failed" || il.ac.tau.lecturesummarizer.upload.isTerminalStageText(it) }, stageDetail = null, progress = st.progress, error = null) }
                                JobPollWorker.enqueue(ctx, lectureId)
                                if (r.mode == "full") ctx.getString(il.ac.tau.lecturesummarizer.R.string.reprocessing_new)
                                else ctx.getString(il.ac.tau.lecturesummarizer.R.string.reprocessing_audio_missing)
                            }.getOrElse { e -> app.repo.updateLecture(lectureId) { it.copy(error = ctx.getString(il.ac.tau.lecturesummarizer.R.string.reprocess_failed)) }; ctx.getString(il.ac.tau.lecturesummarizer.R.string.reprocess_failed) }
                            reprocessing = false
                            launch(Dispatchers.Main) { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
                        }
                    }
                    Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_reprocessing_repeats_transcription_term_repair_and_summary_from_u),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    var pickDensity by remember { mutableStateOf(false) }
                    OutlineAction(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_re_summarize), Icons.Default.AutoAwesome, Modifier.fillMaxWidth()) { pickDensity = true }
                    if (pickDensity) AlertDialog(onDismissRequest = { pickDensity = false },
                        title = { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_summary_level)) },
                        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                il.ac.tau.lecturesummarizer.data.SummaryDensity.entries.forEach { d ->
                                    TextButton(onClick = {
                                        pickDensity = false
                                        il.ac.tau.lecturesummarizer.slides.SummaryRefreshWorker.enqueue(app,lectureId,d.api)
                                    }) { Text(densityLabel(d)) }
                                }
                            }
                            Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_a_different_level_is_saved_as_an_additional_summary_the_existing), style = MaterialTheme.typography.bodySmall)
                        } },
                        confirmButton = {}, dismissButton = { TextButton(onClick = { pickDensity = false }) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_cancel)) } })
                    Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_re_summarizing_uses_the_existing_transcript_and_current_slides_wi),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row {
                    if (l.state == LectureState.DONE) TextButton(onClick = { app.repo.updateLecture(lectureId) { it.copy(state = LectureState.PROCESSING) } }) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_download_pdf_again)) }
                    if (l.state == LectureState.FAILED) TextButton(onClick = onAttach) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_edit_materials)) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** "<Course> - <Lecture> - summary.pdf", filesystem-safe. Used by both Share and Save. */
internal fun summaryPdfName(course: String?, title: String?): String {
    fun clean(x: String?) = x.orEmpty().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim()
    val parts = listOf(clean(course), clean(title)).filter { it.isNotEmpty() }.distinct()
    return (parts.ifEmpty { listOf("lecture") } + "summary").joinToString(" - ").take(150) + ".pdf"
}


/** Panopto pipeline checklist: captions ✓, slides attached, job source. */
@Composable
internal fun PanoptoStatusLines(l: Lecture, srtReady: Boolean) {
    val c = MaterialTheme.colorScheme.onSurfaceVariant
    Text(if(srtReady)androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.captions_imported)else androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.captions_not_downloaded), style = MaterialTheme.typography.bodySmall, color = c)
    Text(if(l.allSlides.isNotEmpty())androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.slides_attached_count, l.allSlides.size)else androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.slides_none_optional), style = MaterialTheme.typography.bodySmall, color = c)
    Text(if(l.segments.isEmpty())androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.job_source_captions)else androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.job_source_audio), style = MaterialTheme.typography.bodySmall, color = c)
}


/** Detached scope for lecture actions that must outlive the composable that started them. */
internal object LectureActions {
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
}
