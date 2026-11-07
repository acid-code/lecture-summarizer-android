package il.ac.tau.lecturesummarizer.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.data.ApiProvider
import il.ac.tau.lecturesummarizer.data.LectureState
import il.ac.tau.lecturesummarizer.data.TranscriptImport
import il.ac.tau.lecturesummarizer.moodle.*
import il.ac.tau.lecturesummarizer.upload.JobKick
import il.ac.tau.lecturesummarizer.upload.JobPollWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface PState {
    data object Loading : PState
    data object Login : PState
    data class PanoptoLogin(val url: String, val course: MoodleCourse, val rec: PanoptoRecording) : PState
    data class PickCourse(val options: List<MoodleCourse>) : PState
    data class Recordings(val course: MoodleCourse, val recs: List<PanoptoRecording>) : PState
    data class Importing(val title: String) : PState
    data class Error(val msg: String) : PState
}

/**
 * Import from Panopto: Moodle WebView session → course → recordings in the course's Panopto side block →
 * GenerateSRT captions → POST /v1/lectures/{id}/transcript/import → process (no audio upload, no ASR).
 */
@Composable
fun PanoptoImportScreen(app: LectureApp, lectureId: String, onBack: () -> Unit, onStarted: () -> Unit,
                        newEntry: Boolean = false, onRecordInstead: () -> Unit = {}) {
    val ctx = LocalContext.current
    val store = remember { MoodleStore(ctx.applicationContext) }
    val client = remember { MoodleClient.of(store.baseUrl) }
    val lecture = app.repo.lecture(lectureId) ?: return
    val course = app.repo.course(lecture.courseId)
    val kind = MoodleMatch.kindFromTitle(lecture.title)
    val mapKey = "${course?.courseNumber ?: course?.id}:${kind.name}"
    var state by remember { mutableStateOf<PState>(PState.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    var forcePick by remember { mutableStateOf(false) }
    val recCounts = remember { mutableStateMapOf<Long, Int>() }
    var siblings by remember { mutableStateOf<List<MoodleCourse>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val capStatus = remember { mutableStateMapOf<String, CaptionStatus>() }
    var onlyWithCaptions by remember { mutableStateOf(false) }
    // No-captions rows: does DeliveryInfo expose a stream we can pull audio from? (true = selectable audio fallback)
    val audioOk = remember { mutableStateMapOf<String, Boolean?>() }
    var confirmAudio by remember { mutableStateOf<Pair<MoodleCourse, PanoptoRecording>?>(null) }

    fun fail(e: Throwable): PState = when (e) {
        is MoodleAuthRequired -> PState.Login
        else -> PState.Error(e.message ?: "Error")
    }

    suspend fun openCourse(c: MoodleCourse) {
        state = PState.Loading
        state = withContext(Dispatchers.IO) { runCatching { PState.Recordings(c, client.panoptoRecordings(c.id).also { recCounts[c.id] = it.size }) }.getOrElse(::fail) }
    }

    suspend fun import(course: MoodleCourse, rec: PanoptoRecording) {
        if (newEntry) {
            // New entry from Panopto: pin source + Moodle course, fetch captions in the background and go straight to
            // Materials so slides (file / Moodle, filtered to this course) can be attached meanwhile.
            store.setLectureSource(lectureId, course.id, rec.title)
            app.repo.updateLecture(lectureId) { it.copy(title = if (it.title == PANOPTO_PLACEHOLDER || it.title.isBlank()) Panopto.lectureTitle(rec.title) else it.title, panoptoId = rec.deliveryId, panoptoTitle = rec.title, panoptoUrl = rec.viewerUrl, panoptoSrtPath = null, error = null) }
            PanoptoFetch.start(app, lectureId)
            onStarted(); return
        }
        state = PState.Importing(rec.title)
        state = withContext(Dispatchers.IO) {
            runCatching {
                val srtText = client.downloadCaptions(rec)
                val segs = Panopto.parseSrt(srtText)
                if (segs.isEmpty()) error("Captions are empty")
                // Mark the lecture as Panopto-sourced (labels, Upload, Reprocess) and cache the captions locally.
                val srtFile = java.io.File(app.filesDir, "panopto/$lectureId.srt").apply { parentFile?.mkdirs(); writeText(srtText) }
                app.repo.updateLecture(lectureId) { it.copy(panoptoId = rec.deliveryId, panoptoTitle = rec.title, panoptoUrl = rec.viewerUrl, panoptoSrtPath = srtFile.absolutePath, panoptoDurationMs = (segs.maxOf { s -> s.t1 } * 1000).toLong()) }
                val api = ApiProvider.get(app.settings)
                val repo = app.repo
                val c = repo.course(lecture.courseId) ?: error("Course missing")
                val courseRemote = c.remoteId ?: api.createCourse(c.name).also { rid -> repo.updateCourse(c.id) { it.copy(remoteId = rid) } }
                val l = repo.lecture(lectureId)!!
                val remote = l.remoteId ?: api.createLecture(courseRemote, l.title, l.recordedAtMs).also { rid -> repo.updateLecture(lectureId) { it.copy(remoteId = rid) } }
                val lang = Panopto.languageOf(segs)
                store.setLectureSource(lectureId, course.id, rec.title)
                JobKick.kick(lectureId)
                val job = api.importTranscript(remote, TranscriptImport("panopto", rec.deliveryId, rec.title, lang, segs))
                    ?: error("Server didn't start a captions job")
                repo.updateLecture(lectureId) { it.copy(jobId = job, liveJob = false, state = LectureState.PROCESSING, error = null, stage = null, stageDetail = null, progress = 0) }
                JobPollWorker.enqueue(ctx.applicationContext, lectureId, replace = true)
                null
            }.getOrElse { e -> if (e is PanoptoAuthRequired) PState.PanoptoLogin(e.viewerUrl ?: Panopto.HOST, course, rec) else fail(e) }
        } ?: run { Toast.makeText(ctx, "Imported captions · התחיל סיכום", Toast.LENGTH_SHORT).show(); onStarted(); return }
    }

    fun importAudio(course: MoodleCourse, rec: PanoptoRecording, wifiOnly: Boolean) {
        store.setLectureSource(lectureId, course.id, rec.title)
        app.repo.updateLecture(lectureId) { it.copy(title = if (it.title == PANOPTO_PLACEHOLDER || it.title.isBlank()) Panopto.lectureTitle(rec.title) else it.title,
            panoptoId = rec.deliveryId, panoptoTitle = rec.title, panoptoUrl = rec.viewerUrl, panoptoSrtPath = null, panoptoAudio = true,
            segments = emptyList(), error = null) }
        il.ac.tau.lecturesummarizer.panopto.PanoptoAudioWorker.enqueue(ctx.applicationContext, lectureId, wifiOnly)
        Toast.makeText(ctx, if (wifiOnly && il.ac.tau.lecturesummarizer.panopto.PanoptoAudioWorker.onCellular(ctx)) "Audio download waits for Wi‑Fi · ממתין ל-Wi‑Fi" else "Downloading audio · מוריד אודיו", Toast.LENGTH_SHORT).show()
        onStarted()
    }

    confirmAudio?.let { (c, r) ->
        val cell = remember { il.ac.tau.lecturesummarizer.panopto.PanoptoAudioWorker.onCellular(ctx) }
        AlertDialog(onDismissRequest = { confirmAudio = null },
            title = { Text("No captions — use audio? · אין כתוביות") },
            text = { Text("The phone downloads the lowest-quality video (~300 MB per hour of lecture), keeps only the audio (~15 MB/hour) and sends it for transcription + summary." +
                (if (cell) "\n\nYou're on mobile data. Wait for Wi‑Fi, or use mobile data anyway." else "") +
                "\n\nהטלפון יוריד את הווידאו באיכות הנמוכה, ישמור רק את השמע וישלח לתמלול.") },
            confirmButton = { TextButton(onClick = { confirmAudio = null; importAudio(c, r, wifiOnly = true) }) { Text(if (cell) "On Wi‑Fi · ב-Wi‑Fi" else "Download audio · הורד") } },
            dismissButton = { Row {
                if (cell) TextButton(onClick = { confirmAudio = null; importAudio(c, r, wifiOnly = false) }) { Text("Use mobile data") }
                TextButton(onClick = { confirmAudio = null }) { Text("Cancel") }
            } })
    }

    // No-captions rows → probe DeliveryInfo for a usable stream (≤4 parallel).
    val noCapKey = (state as? PState.Recordings)?.recs?.filter { capStatus[it.deliveryId] == CaptionStatus.NONE || capStatus[it.deliveryId] == CaptionStatus.UNKNOWN }?.map { it.deliveryId }
    LaunchedEffect(noCapKey) {
        val recs = (state as? PState.Recordings)?.recs ?: return@LaunchedEffect
        val sem = Semaphore(4)
        recs.filter { it.deliveryId in (noCapKey ?: emptyList()) && it.deliveryId !in audioOk }.forEach { r ->
            audioOk[r.deliveryId] = client.cachedAudioAvailable(r.deliveryId)
            if (audioOk[r.deliveryId] == null) launch(Dispatchers.IO) { sem.withPermit { audioOk[r.deliveryId] = withTimeoutOrNull(20_000) { client.audioAvailable(r) } ?: false } }
        }
    }

    // Probe caption availability in the background (list shows immediately; ≤6 parallel, 15 s each).
    val recsKey = (state as? PState.Recordings)?.recs
    LaunchedEffect(recsKey) {
        val recs = recsKey ?: return@LaunchedEffect
        // Known HAS/NONE answers show instantly; only unknown ones are probed.
        recs.forEach { r -> client.cachedCaptionStatus(r.deliveryId)?.let { capStatus[r.deliveryId] = it } }
        val todo = recs.filter { client.cachedCaptionStatus(it.deliveryId) == null }
        if (todo.isEmpty()) return@LaunchedEffect
        client.resetPanoptoSession()
        val sem = Semaphore(6)
        todo.forEach { r -> capStatus[r.deliveryId] = CaptionStatus.CHECKING }
        todo.forEach { r ->
            launch(Dispatchers.IO) {
                sem.withPermit {
                    capStatus[r.deliveryId] = withTimeoutOrNull(15_000) { client.captionStatus(r) } ?: CaptionStatus.UNKNOWN
                }
            }
        }
    }

    LaunchedEffect(reload) {
        state = PState.Loading
        val courses = withContext(Dispatchers.IO) { runCatching { client.courses() } }.getOrElse { state = fail(it); return@LaunchedEffect }
        val ranked = MoodleMatch.rank(courses, course?.courseNumber, kind, course?.name)
        // All Moodle courses sharing the 8-digit prefix (…01 lecture, …09 recitation, …).
        siblings = MoodleMatch.siblings(courses, course?.courseNumber)
        val pickList = (siblings + ranked.map { it.course } + courses).distinctBy { it.id }
        if (forcePick) {
            forcePick = false
            state = PState.PickCourse(pickList)
            // Probe recording counts for the same-prefix siblings so the picker shows them.
            siblings.filter { it.id !in recCounts }.forEach { c -> launch(Dispatchers.IO) { runCatching { client.panoptoRecordings(c.id).size }.getOrNull()?.let { recCounts[c.id] = it } } }
            return@LaunchedEffect
        }
        // A course the user explicitly picked always wins.
        store.mappedCourse("$mapKey:manual")?.let { id -> courses.firstOrNull { it.id == id }?.let { openCourse(it); return@LaunchedEffect } }
        // Auto mappings are re-evaluated when sibling groups exist (an old auto pick may be the empty group).
        if (siblings.size <= 1) store.mappedCourse(mapKey)?.let { id -> courses.firstOrNull { it.id == id }?.let { openCourse(it); return@LaunchedEffect } }
        if (siblings.size > 1) {
            // Several groups share the prefix: default to the one that actually has Panopto recordings,
            // then the most recordings. Group suffixes differ per course, so no 01/09 assumption here.
            val counts = withContext(Dispatchers.IO) {
                siblings.map { c -> c to (runCatching { client.panoptoRecordings(c.id).size }.getOrNull() ?: -1) }
            }
            counts.forEach { (c, n) -> if (n >= 0) recCounts[c.id] = n }
            val best = counts.sortedWith(compareByDescending<Pair<MoodleCourse, Int>> { it.second > 0 }
                .thenByDescending { it.second }).first().first
            store.mapCourse(mapKey, best.id); openCourse(best); return@LaunchedEffect
        }
        val pick = MoodleMatch.confident(ranked)
        if (pick != null) { store.mapCourse(mapKey, pick.id); openCourse(pick) }
        else state = PState.PickCourse(pickList)
    }

    fun pickAnother() { store.mapCourse(mapKey, -1); store.mapCourse("$mapKey:manual", -1); forcePick = true; reload++ }

    AppScaffold(if (newEntry) "Choose Panopto recording" else "Import from Panopto", onBack = onBack, actions = {
        if (state is PState.Recordings || state is PState.PickCourse) IconButton(onClick = { pickAnother() }) { Icon(Icons.Default.SwapHoriz, "Change course") }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (val s = state) {
                PState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is PState.Importing -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text("Fetching captions… ${s.title}")
                }
                PState.Login -> MoodleLoginView(store.baseUrl) { reload++ }
                // Panopto has its own SAML session; open the viewer once so tau.cloud.panopto.eu cookies land in CookieManager.
                is PState.PanoptoLogin -> MoodleLoginView(store.baseUrl, startUrl = s.url,
                    isDone = { u -> u.contains("tau.cloud.panopto.eu", true) && u.contains("Viewer.aspx", true) }) { scope.launch { import(s.course, s.rec) } }
                is PState.Error -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ErrorText(s.msg); Button(onClick = { client.invalidate(); reload++ }) { Text("Retry") }
                    if (newEntry) OutlinedButton(onClick = onRecordInstead) { Text("Record instead · הקלט במקום") }
                }
                is PState.PickCourse -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text("Which Moodle course? · באיזה קורס?", style = MaterialTheme.typography.titleMedium) }
                    items(s.options, key = { it.id }) { c ->
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth().clickable { store.mapCourse(mapKey, c.id); store.mapCourse("$mapKey:manual", c.id); scope.launch { openCourse(c) } }) {
                            Column(Modifier.padding(12.dp)) {
                                Text(c.fullname)
                                val n = recCounts[c.id]
                                Text(c.shortname + (if (c in siblings) (if (n == null) " · checking…" else " · $n recordings") else ""), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                is PState.Recordings -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text(s.course.fullname, style = MaterialTheme.typography.titleMedium)
                        Text("${s.recs.size} recordings · ${Panopto.BLOCK_TITLE}", style = MaterialTheme.typography.bodySmall)
                        if (siblings.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            siblings.forEach { c ->
                                val n = recCounts[c.id]
                                FilterChip(selected = c.id == s.course.id, onClick = { if (c.id != s.course.id) { store.mapCourse(mapKey, c.id); store.mapCourse("$mapKey:manual", c.id); scope.launch { openCourse(c) } } },
                                    label = { Text(c.shortname.takeLast(12) + (n?.let { " · $it" } ?: "")) })
                            }
                        }
                    }
                    if (s.recs.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("📭", style = MaterialTheme.typography.displaySmall)
                            Text("No Panopto recordings for this course · אין הקלטות Panopto לקורס הזה", style = MaterialTheme.typography.titleSmall)
                            Text("Not every course records to Panopto, or the recording isn't published yet.", style = MaterialTheme.typography.bodySmall)
                            if (newEntry) Button(onClick = onRecordInstead) { Text("Record instead · הקלט במקום") }
                            OutlinedButton(onClick = { client.invalidate(); reload++ }) { Text("Refresh") }
                            TextButton(onClick = { pickAnother() }) { Text("Wrong course? Pick another") }
                            TextButton(onClick = onBack) { Text("Back") }
                        }
                    }
                    if (s.recs.isNotEmpty()) item {
                        val none = s.recs.count { capStatus[it.deliveryId] == CaptionStatus.NONE }
                        val has = s.recs.count { capStatus[it.deliveryId] == CaptionStatus.HAS }
                        if (s.recs.any { capStatus[it.deliveryId] == CaptionStatus.AUTH }) Column {
                            Text("Sign in to Panopto to check captions · התחבר ל-Panopto כדי לבדוק כתוביות", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { scope.launch { import(s.course, s.recs.first { capStatus[it.deliveryId] == CaptionStatus.AUTH }) } }) { Text("Sign in to Panopto") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = onlyWithCaptions, onCheckedChange = { onlyWithCaptions = it })
                            Spacer(Modifier.width(8.dp))
                            Text("רק עם כתוביות · only with captions ($has ✓ / $none ✗, ${s.recs.count { audioOk[it.deliveryId] == true && capStatus[it.deliveryId] == CaptionStatus.NONE }} 🔊)", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    items(s.recs.filter { !onlyWithCaptions || capStatus[it.deliveryId] != CaptionStatus.NONE }, key = { it.deliveryId }) { r ->
                        val cs = capStatus[r.deliveryId] ?: CaptionStatus.CHECKING
                        val noCaps = cs == CaptionStatus.NONE
                        val audio = audioOk[r.deliveryId]
                        // Grey only when there are no captions AND no usable stream; audio-only rows stay selectable.
                        val disabled = noCaps && audio == false
                        val audioPath = noCaps && audio == true
                        Surface(shape = MaterialTheme.shapes.medium,
                            color = if (disabled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !disabled && !(noCaps && audio == null)) { if (audioPath || (cs == CaptionStatus.UNKNOWN && audio == true)) confirmAudio = s.course to r else scope.launch { import(s.course, r) } }) {
                            Column(Modifier.padding(12.dp)) {
                                Text("🎬 " + r.title, modifier = if (disabled) Modifier.alpha(0.45f) else Modifier)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(MoodleMatch.kindFromTitle(r.title).he, style = MaterialTheme.typography.bodySmall, modifier = if (disabled) Modifier.alpha(0.45f) else Modifier)
                                    val (label, color) = when (cs) {
                                        CaptionStatus.HAS -> "✓ יש כתוביות" to Color(0xFF2E7D32)
                                        CaptionStatus.NONE -> when (audio) {
                                            true -> "🔊 audio · אין כתוביות" to Color(0xFF1565C0)
                                            null -> "✗ אין כתוביות · checking audio…" to MaterialTheme.colorScheme.onSurfaceVariant
                                            else -> "✗ אין כתוביות ואין שמע · no captions, no stream" to Color(0xFFD32F2F)
                                        }
                                        CaptionStatus.AUTH -> "🔒 sign in to check" to MaterialTheme.colorScheme.onSurfaceVariant
                                        CaptionStatus.CHECKING -> "בודק כתוביות…" to MaterialTheme.colorScheme.onSurfaceVariant
                                        CaptionStatus.UNKNOWN -> "? couldn't check" to MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Text(label, style = MaterialTheme.typography.labelMedium, color = color)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
