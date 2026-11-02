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
    val client = remember { MoodleClient(store.baseUrl) }
    val lecture = app.repo.lecture(lectureId) ?: return
    val course = app.repo.course(lecture.courseId)
    val kind = MoodleMatch.kindFromTitle(lecture.title)
    val mapKey = "${course?.courseNumber ?: course?.id}:${kind.name}"
    var state by remember { mutableStateOf<PState>(PState.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    fun fail(e: Throwable): PState = when (e) {
        is MoodleAuthRequired -> PState.Login
        else -> PState.Error(e.message ?: "Error")
    }

    suspend fun openCourse(c: MoodleCourse) {
        state = PState.Loading
        state = withContext(Dispatchers.IO) { runCatching { PState.Recordings(c, client.panoptoRecordings(c.id)) }.getOrElse(::fail) }
    }

    suspend fun import(course: MoodleCourse, rec: PanoptoRecording) {
        if (newEntry) {
            // New entry from Panopto: pin source + Moodle course, fetch captions in the background and go straight to
            // Materials so slides (file / Moodle, filtered to this course) can be attached meanwhile.
            store.setLectureSource(lectureId, course.id, rec.title)
            app.repo.updateLecture(lectureId) { it.copy(panoptoId = rec.deliveryId, panoptoTitle = rec.title, panoptoUrl = rec.viewerUrl, panoptoSrtPath = null, error = null) }
            PanoptoFetch.start(app, lectureId)
            onStarted(); return
        }
        state = PState.Importing(rec.title)
        state = withContext(Dispatchers.IO) {
            runCatching {
                val segs = Panopto.parseSrt(client.downloadCaptions(rec))
                if (segs.isEmpty()) error("Captions are empty")
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
                    ?: api.startProcessing(remote)
                repo.updateLecture(lectureId) { it.copy(jobId = job, liveJob = false, state = LectureState.PROCESSING, error = null, stage = null, stageDetail = null, progress = 0) }
                JobPollWorker.enqueue(ctx.applicationContext, lectureId, replace = true)
                null
            }.getOrElse { e -> if (e is PanoptoAuthRequired) PState.PanoptoLogin(e.viewerUrl ?: Panopto.HOST, course, rec) else fail(e) }
        } ?: run { Toast.makeText(ctx, "Imported captions · התחיל סיכום", Toast.LENGTH_SHORT).show(); onStarted(); return }
    }

    LaunchedEffect(reload) {
        state = PState.Loading
        val courses = withContext(Dispatchers.IO) { runCatching { client.courses() } }.getOrElse { state = fail(it); return@LaunchedEffect }
        store.mappedCourse(mapKey)?.let { id -> courses.firstOrNull { it.id == id }?.let { openCourse(it); return@LaunchedEffect } }
        val ranked = MoodleMatch.rank(courses, course?.courseNumber, kind, course?.name)
        val pick = MoodleMatch.confident(ranked)
        if (pick != null) { store.mapCourse(mapKey, pick.id); openCourse(pick) }
        else state = PState.PickCourse(ranked.map { it.course } + courses.filterNot { c -> ranked.any { it.course.id == c.id } })
    }

    AppScaffold(if (newEntry) "Choose Panopto recording" else "Import from Panopto", onBack = onBack, actions = {
        if (state is PState.Recordings || state is PState.PickCourse) IconButton(onClick = { store.mapCourse(mapKey, -1); reload++ }) { Icon(Icons.Default.SwapHoriz, "Change course") }
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
                    ErrorText(s.msg); Button(onClick = { reload++ }) { Text("Retry") }
                    if (newEntry) OutlinedButton(onClick = onRecordInstead) { Text("🎙 Record instead · הקלט במקום") }
                }
                is PState.PickCourse -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text("Which Moodle course? · באיזה קורס?", style = MaterialTheme.typography.titleMedium) }
                    items(s.options, key = { it.id }) { c ->
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth().clickable { store.mapCourse(mapKey, c.id); scope.launch { openCourse(c) } }) {
                            Column(Modifier.padding(12.dp)) { Text(c.fullname); Text(c.shortname, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                is PState.Recordings -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text(s.course.fullname, style = MaterialTheme.typography.titleMedium)
                        Text("${s.recs.size} recordings · ${Panopto.BLOCK_TITLE}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (s.recs.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("📭", style = MaterialTheme.typography.displaySmall)
                            Text("No Panopto recordings for this course · אין הקלטות Panopto לקורס הזה", style = MaterialTheme.typography.titleSmall)
                            Text("Not every course records to Panopto, or the recording isn't published yet.", style = MaterialTheme.typography.bodySmall)
                            if (newEntry) Button(onClick = onRecordInstead) { Text("🎙 Record instead · הקלט במקום") }
                            OutlinedButton(onClick = { reload++ }) { Text("Refresh") }
                            TextButton(onClick = { store.mapCourse(mapKey, -1); reload++ }) { Text("Wrong course? Pick another") }
                            TextButton(onClick = onBack) { Text("Back") }
                        }
                    }
                    items(s.recs, key = { it.deliveryId }) { r ->
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth().clickable { scope.launch { import(s.course, r) } }) {
                            Column(Modifier.padding(12.dp)) {
                                Text("🎬 " + r.title)
                                Text(MoodleMatch.kindFromTitle(r.title).he, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
