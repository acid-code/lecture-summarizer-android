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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val work by remember(l.id){androidx.work.WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow("slides-match-${l.id}")}.collectAsStateWithLifecycle(initialValue=emptyList())
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
            l.allSlides.forEach { Text(bidi(displayName(ctx, it)), style = MaterialTheme.typography.bodyMedium) }
            if (auto && l.allSlides.isNotEmpty()) {
                StatusChip(stringResource(R.string.slides_auto), MaterialTheme.colorScheme.primary)
                StatusChip(stringResource(R.string.slides_possible), MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { why = true }) { Text(stringResource(R.string.slides_why)) }
            }
            if (l.allSlides.isEmpty()) {
                val phase=remember(tick,l.id){choices.phase(l.id)}
                val (online,_)=rememberOnline()
                val metered=(app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered
                val battery=app.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
                val lowBattery= !battery.isCharging && battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) in 0..14
                val queued=work.any{it.state==androidx.work.WorkInfo.State.ENQUEUED || it.state==androidx.work.WorkInfo.State.BLOCKED} || phase=="queued"
                val stateText=when {
                    !il.ac.tau.lecturesummarizer.autosync.AutoSyncStore(app).autoAttach -> R.string.slides_disabled
                    queued && (!online || metered) -> R.string.slides_wait_wifi
                    queued && lowBattery -> R.string.slides_wait_battery
                    queued -> R.string.slides_queued
                    phase=="checking" -> R.string.slides_checking
                    phase=="partial" -> R.string.slides_partial
                    phase=="auth" -> R.string.slides_signin
                    phase=="error" -> R.string.slides_search_error
                    phase=="no_course" -> R.string.slides_choose_course
                    phase=="no_match" -> R.string.slides_missing
                    phase=="disabled" -> R.string.slides_disabled
                    else -> R.string.slides_not_checked
                }
                if(phase!="suggestions" || candidates.isEmpty())Text(stringResource(stateText),color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(phase=="auth")TextButton(onClick=onMoodle){Text(stringResource(R.string.connect))}
                if(l.state==LectureState.DONE)TextButton(enabled=il.ac.tau.lecturesummarizer.autosync.AutoSyncStore(app).autoAttach,onClick={il.ac.tau.lecturesummarizer.slides.SlideMatchWorker.enqueue(app,l.id,again=true)}){Text(stringResource(R.string.slides_check_again))}
                if(candidates.isNotEmpty()) {
                    Text(stringResource(R.string.slides_suggest), style = MaterialTheme.typography.titleSmall)
                    candidates.take(3).forEach { candidate ->
                        Text(bidi(candidate.getString("name")),maxLines=2)
                        StatusChip(stringResource(R.string.slides_possible))
                        Text(stringResource(R.string.slides_provenance, candidate.optInt("termCount",candidate.optJSONArray("terms")?.length() ?: 0)),style=MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(enabled = !busy && l.state !in setOf(LectureState.UPLOADING, LectureState.PROCESSING), onClick = {
                                val file = MoodleStore(app).let{it.cached(candidate.getLong("id"),it.lectureBase(l.id))}
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
                    val freshness=il.ac.tau.lecturesummarizer.slides.SummaryFreshness(app)
                    val before=freshness.checkpoint(l.id)
                    choices.manual(l.id)
                    val afterGeneration=freshness.checkpoint(l.id).generation
                    app.repo.updateLecture(l.id) { it.copy(slidesUri = null, extraSlides = emptyList(), uploadedSlides = emptyList()) }
                    scope.launch {
                        val result = withTimeoutOrNull(8_000) { snackbar.showSnackbar(removedText, undoText, duration = SnackbarDuration.Indefinite) }
                        snackbar.currentSnackbarData?.dismiss()
                        if (result == SnackbarResult.ActionPerformed){app.repo.updateLecture(l.id) { it.copy(slidesUri = old.firstOrNull(), extraSlides = old.drop(1)) };freshness.restore(l.id,before,afterGeneration)}
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
                val file = MoodleStore(app).let{it.cached(candidate.getLong("id"),it.lectureBase(l.id))}
                if (file != null) changed(FileProvider.getUriForFile(app, app.packageName + ".files", file).toString()) else onMoodle()
            }) { Text(bidi(candidate.getString("name")),maxLines=2) } }
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
            il.ac.tau.lecturesummarizer.slides.SummaryRefreshWorker.enqueue(app,l.id)
            busy=false

        }) { Text(stringResource(R.string.slides_update)) } }, dismissButton = { TextButton(onClick = { offer = false }) { Text(stringResource(R.string.skip)) } })
}
