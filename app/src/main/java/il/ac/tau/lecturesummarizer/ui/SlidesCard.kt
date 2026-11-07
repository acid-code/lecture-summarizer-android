package il.ac.tau.lecturesummarizer.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.data.*
import il.ac.tau.lecturesummarizer.moodle.MoodleStore
import il.ac.tau.lecturesummarizer.slides.SlideChoices
import il.ac.tau.lecturesummarizer.upload.JobPollWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlidesCard(app: LectureApp, l: Lecture, onMoodle: () -> Unit) {
    val ctx = LocalContext.current
    val choices = remember { SlideChoices(app) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val prefs = app.getSharedPreferences("slide_choices", android.content.Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> tick++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val auto = remember(tick) { choices.isAuto(l.id) }
    val candidates = remember(tick, l.id) { runCatching {
        val a = JSONArray(choices.candidates(l.id) ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.getLong("id").toString() !in choices.rejected(l.id) }
    }.getOrDefault(emptyList()) }
    var why by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var offer by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val undoText = stringResource(R.string.undo)
    val removedText = stringResource(R.string.slides_removed)
    fun changed(uri: String?) {
        choices.manual(l.id)
        app.repo.updateLecture(l.id) { it.copy(slidesUri = uri, extraSlides = emptyList(), uploadedSlides = emptyList()) }
        if (l.jobId != null && l.remoteId != null) offer = true
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { persist(ctx, uri); changed(uri.toString()) }
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.slides_title), style = MaterialTheme.typography.titleMedium)
            l.allSlides.forEach { Text(displayName(ctx, it), style = MaterialTheme.typography.bodyMedium) }
            if (auto && l.allSlides.isNotEmpty()) {
                StatusChip(stringResource(R.string.slides_auto), MaterialTheme.colorScheme.primary)
                StatusChip(stringResource(R.string.slides_high), MaterialTheme.colorScheme.primary)
                TextButton(onClick = { why = true }) { Text(stringResource(R.string.slides_why)) }
            }
            if (l.allSlides.isEmpty()) {
                if (candidates.isEmpty()) Text(stringResource(if (choices.phase(l.id) in setOf("queued", "checking")) R.string.slides_checking else R.string.slides_missing), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    Text(stringResource(R.string.slides_suggest), style = MaterialTheme.typography.titleSmall)
                    candidates.take(3).forEach { candidate ->
                        Text(candidate.getString("name"))
                        StatusChip(stringResource(R.string.slides_possible))
                        Row {
                            TextButton(enabled = !busy && l.state !in setOf(LectureState.UPLOADING, LectureState.PROCESSING), onClick = {
                                val file = MoodleStore(app).cached(candidate.getLong("id"))
                                if (file != null) changed(FileProvider.getUriForFile(app, app.packageName + ".files", file).toString()) else onMoodle()
                            }) { Text(stringResource(R.string.slides_attach)) }
                            TextButton(onClick = { choices.reject(l.id, candidate.getLong("id")) }) { Text(stringResource(R.string.slides_reject)) }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled = !busy && l.state !in setOf(LectureState.UPLOADING, LectureState.PROCESSING), onClick = { picker = true }) { Text(stringResource(if (l.allSlides.isEmpty()) R.string.slides_upload else R.string.slides_swap)) }
                if (l.allSlides.isNotEmpty()) TextButton(enabled = !busy && l.state !in setOf(LectureState.UPLOADING, LectureState.PROCESSING), onClick = {
                    val old = l.allSlides
                    choices.manual(l.id)
                    app.repo.updateLecture(l.id) { it.copy(slidesUri = null, extraSlides = emptyList(), uploadedSlides = emptyList()) }
                    scope.launch {
                        val result = withTimeoutOrNull(8_000) { snackbar.showSnackbar(removedText, undoText, duration = SnackbarDuration.Indefinite) }
                        snackbar.currentSnackbarData?.dismiss()
                        if (result == SnackbarResult.ActionPerformed) app.repo.updateLecture(l.id) { it.copy(slidesUri = old.firstOrNull(), extraSlides = old.drop(1)) }
                        else if (l.jobId != null && l.remoteId != null) offer = true
                    }
                }) { Text(stringResource(R.string.slides_remove)) }
                if (l.allSlides.isEmpty()) TextButton(onClick = onMoodle) { Text(stringResource(R.string.slides_moodle)) }
            }
            SnackbarHost(snackbar)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error) Text(stringResource(R.string.slides_error), color = MaterialTheme.colorScheme.error)
        }
    }
    if (picker) ModalBottomSheet(onDismissRequest = { picker = false }) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.slides_swap), style = MaterialTheme.typography.titleLarge)
            candidates.take(5).forEach { candidate -> TextButton(onClick = {
                picker = false
                val file = MoodleStore(app).cached(candidate.getLong("id"))
                if (file != null) changed(FileProvider.getUriForFile(app, app.packageName + ".files", file).toString()) else onMoodle()
            }) { Text(candidate.getString("name")) } }
            OutlinedButton(onClick = { picker = false; onMoodle() }) { Text(stringResource(R.string.slides_moodle)) }
            OutlinedButton(onClick = { picker = false; filePicker.launch(SLIDE_MIMES) }) { Text(stringResource(R.string.slides_upload)) }
        }
    }
    if (why) ModalBottomSheet(onDismissRequest = { why = false }) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.slides_why), style = MaterialTheme.typography.titleLarge)
            choices.reasons(l.id).take(3).forEach { reason -> Text(when {
                reason == "same_course" -> stringResource(R.string.slides_same_course)
                reason == "lecture_number" -> stringResource(R.string.slides_number)
                reason.startsWith("terms:") -> stringResource(R.string.slides_terms, reason.substringAfter(':').toIntOrNull() ?: 0)
                else -> ""
            }) }
        }
    }
    if (offer) AlertDialog(onDismissRequest = { offer = false }, title = { Text(stringResource(R.string.slides_update_question)) },
        text = { Text(stringResource(R.string.slides_no_asr)) }, confirmButton = { TextButton(onClick = {
            offer = false; busy = true; error = false
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val current = app.repo.lecture(l.id) ?: return@withContext
                        val api = ApiProvider.get(app.settings)
                        if (current.allSlides.isEmpty()) api.clearSlides(current.remoteId!!)
                        current.allSlides.forEachIndexed { index, raw ->
                            val uri = Uri.parse(raw)
                            ctx.contentResolver.openInputStream(uri)!!.use { api.uploadSlides(current.remoteId!!, displayName(ctx, raw),
                                ctx.contentResolver.getType(uri) ?: "application/pdf", it, replaceAll = index == 0) }
                        }
                        val status = api.resummarizeJob(current.jobId!!)
                        app.repo.updateLecture(l.id) { it.copy(uploadedSlides = it.allSlides, jobId = status.jobId ?: it.jobId,
                            state = LectureState.PROCESSING, stage = null, stageDetail = null, progress = status.progress, pdfPath = null, error = null) }
                        JobPollWorker.enqueue(app, l.id)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { error = true }
                finally { busy = false }
            }
        }) { Text(stringResource(R.string.slides_update)) } }, dismissButton = { TextButton(onClick = { offer = false }) { Text(stringResource(R.string.skip)) } })
}
