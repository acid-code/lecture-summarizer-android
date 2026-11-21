package il.ac.tau.lecturesummarizer.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.data.*
import il.ac.tau.lecturesummarizer.study.Concept
import il.ac.tau.lecturesummarizer.study.StudyStore
import il.ac.tau.lecturesummarizer.study.cleanConcepts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ExamScreen(app: LectureApp, courseId: String, onBack: () -> Unit, onTutor: (String) -> Unit, onMemory: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val courses by app.repo.courses.collectAsStateWithLifecycle()
    val lectures by app.repo.lectures.collectAsStateWithLifecycle()
    val course = courses.firstOrNull { it.id == courseId }
    val local = lectures.filter { it.courseId == courseId }
    val remote = course?.remoteId
    var status by remember { mutableStateOf<ExamStatus?>(null) }
    var excluded by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pdfReady by remember { mutableStateOf(false) }
    val pdfFile = remember(courseId) { File(app.repo.pdfDir(), "exam-$courseId.pdf") }

    fun densLabel(d: String) = when (d) {
        "detailed" -> ctx.getString(R.string.level_detailed)
        "standard" -> ctx.getString(R.string.level_standard)
        "compact" -> ctx.getString(R.string.level_compact)
        else -> d
    }

    LaunchedEffect(remote) {
        if (remote == null) { status = ExamStatus(emptyList()); loading = false; return@LaunchedEffect }
        loading = true
        error = null
        runCatching { withContext(Dispatchers.IO) { ApiProvider.get(app.settings).examStatus(remote) } }
            .onSuccess {
                status = it
                pdfReady = it.ready && pdfFile.isFile
                loading = false
                // Download cached PDF off the critical path so the checklist appears immediately.
                if (it.ready && !pdfFile.isFile) {
                    runCatching { withContext(Dispatchers.IO) { ApiProvider.get(app.settings).downloadExamPdf(remote, pdfFile) } }
                        .onSuccess { pdfReady = pdfFile.isFile }
                }
            }
            .onFailure { error = it.message; status = ExamStatus(emptyList()); loading = false }
    }

    val sources = status?.sources.orEmpty()
    val included = sources.filter { it.lectureId !in excluded }

    AppScaffold(stringResource(R.string.exam_title), onBack = onBack) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(R.string.exam_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (remote == null) item { Text(stringResource(R.string.exam_need_sync), color = MaterialTheme.colorScheme.error) }
            if (error != null) item { Text(error!!, color = MaterialTheme.colorScheme.error) }
            if (loading) item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.exam_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!loading && sources.isEmpty() && remote != null && error == null) {
                item { Text(stringResource(R.string.exam_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(sources, key = { it.lectureId }) { s ->
                val on = s.lectureId !in excluded
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(on, { checked -> excluded = if (checked) excluded - s.lectureId else excluded + s.lectureId })
                    Column(Modifier.weight(1f)) {
                        Text(bidi(s.title), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${kindLabel(s.kind)} · ${densLabel(s.density)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if ((status?.missingDetailed ?: 0) > 0) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.exam_missing_detailed, status!!.missingDetailed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val missing = included.mapNotNull { src ->
                            if (src.density == "detailed") return@mapNotNull null
                            local.firstOrNull { it.remoteId == src.lectureId }?.id
                        }
                        OutlinedButton(
                            enabled = !busy && missing.isNotEmpty(),
                            onClick = {
                                missing.forEach { il.ac.tau.lecturesummarizer.slides.SummaryRefreshWorker.enqueueDensity(app, it, "detailed") }
                                Toast.makeText(ctx, ctx.getString(R.string.exam_upgrade_queued), Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.exam_upgrade_detailed)) }
                    }
                }
            }
            item {
                Button(
                    enabled = !busy && remote != null && included.isNotEmpty(),
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            try {
                                val api = ApiProvider.get(app.settings)
                                api.buildExam(remote!!, excluded.toList(), force = true)
                                withContext(Dispatchers.IO) { api.downloadExamPdf(remote, pdfFile) }
                                pdfReady = pdfFile.isFile
                                status = api.examStatus(remote)
                                Toast.makeText(ctx, ctx.getString(R.string.exam_ready), Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                error = e.message
                            } finally { busy = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.PictureAsPdf, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (pdfReady) R.string.exam_rebuild else R.string.exam_generate))
                }
            }
            if (pdfReady) {
                item {
                    OutlinedButton(
                        onClick = {
                            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", pdfFile)
                            safeStart(ctx, Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.exam_open_pdf)) }
                }
            }
            item {
                val tutorLecture = included.firstNotNullOfOrNull { src ->
                    local.firstOrNull { it.remoteId == src.lectureId && it.remoteId != null }
                } ?: local.firstOrNull { it.remoteId != null }
                OutlinedButton(
                    enabled = tutorLecture?.remoteId != null,
                    onClick = {
                        val anchor = tutorLecture ?: return@OutlinedButton
                        scope.launch {
                            val seeds = mergeExamConcepts(app, included, local, remote)
                            ExamTutorSeeds.put(anchor.id, seeds.take(8))
                            onTutor(anchor.id)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.ChatBubbleOutline, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.exam_tutor))
                }
            }
            item {
                OutlinedButton(
                    enabled = included.isNotEmpty(),
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                ExamMemoryCache.put(courseId, mergeExamConcepts(app, included, local, remote))
                                onMemory()
                            } catch (e: Exception) {
                                error = e.message
                            } finally { busy = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.GridView, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.exam_memory))
                }
            }
        }
    }
}

@Composable
private fun kindLabel(kind: String): String = when (kind) {
    "lecture" -> stringResource(R.string.kind_lecture)
    "recitation" -> stringResource(R.string.kind_recitation)
    else -> stringResource(R.string.kind_class)
}

private suspend fun mergeExamConcepts(
    app: LectureApp,
    included: List<ExamSource>,
    local: List<Lecture>,
    remote: String?,
): List<Concept> = withContext(Dispatchers.IO) {
    val store = StudyStore(app)
    val api = ApiProvider.get(app.settings)
    val concepts = mutableListOf<Concept>()
    for (src in included) {
        val localId = local.firstOrNull { it.remoteId == src.lectureId }?.id
        val cached = localId?.let { store.cached(it, src.density) }?.entries
        if (!cached.isNullOrEmpty()) concepts += cached
        else if (src.lectureId.isNotBlank()) {
            runCatching { api.glossary(src.lectureId, src.density.takeIf { it in setOf("detailed", "standard", "compact") }) }.getOrNull()?.let { concepts += it }
        }
    }
    if (remote != null) {
        runCatching {
            val pack = api.examPackJson(remote)
            val pairs = pack?.optJSONArray("memory_pairs")
            if (pairs != null) {
                for (i in 0 until pairs.length()) {
                    val o = pairs.optJSONObject(i) ?: continue
                    concepts += Concept(o.optString("term"), o.optString("definition"))
                }
            }
        }
    }
    cleanConcepts(concepts).take(200)
}

/** In-memory course flashcard deck for the exam hub session. */
object ExamMemoryCache {
    private var id: String? = null
    private var concepts: List<Concept> = emptyList()
    fun put(courseId: String, c: List<Concept>) { id = courseId; concepts = c }
    fun get(courseId: String): List<Concept> = if (id == courseId) concepts else emptyList()
}

/** One-shot suggestion chips when opening tutor from the exam hub. */
object ExamTutorSeeds {
    private var lectureId: String? = null
    private var concepts: List<Concept> = emptyList()
    fun put(id: String, c: List<Concept>) { lectureId = id; concepts = c }
    fun take(id: String): List<Concept> {
        if (lectureId != id) return emptyList()
        val out = concepts
        lectureId = null
        concepts = emptyList()
        return out
    }
}

@Composable
fun ExamMemoryScreen(app: LectureApp, courseId: String, onBack: () -> Unit) {
    val concepts = remember(courseId) { ExamMemoryCache.get(courseId) }
    AppScaffold(stringResource(R.string.exam_memory), onBack = onBack) { pad ->
        if (concepts.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.exam_memory_empty))
            }
        } else {
            Box(Modifier.padding(pad).fillMaxSize()) {
                MemoryGameContent(stringResource(R.string.exam_memory), concepts, version = "exam-$courseId-${concepts.size}")
            }
        }
    }
}
