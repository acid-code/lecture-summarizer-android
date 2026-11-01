package il.ac.tau.lecturesummarizer.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.calendar.ClassKind
import il.ac.tau.lecturesummarizer.moodle.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface MState {
    data object Loading : MState
    data object Login : MState
    data class PickCourse(val options: List<MoodleCourse>) : MState
    data class Files(val course: MoodleCourse, val sections: List<MoodleSection>) : MState
    data class Error(val msg: String) : MState
}

/** Attach from Moodle: WebView login (cookie session) → course (auto-matched from calendar number) → multi-select files → attach. */
@Composable
fun MoodleAttachScreen(app: LectureApp, lectureId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { MoodleStore(ctx.applicationContext) }
    val client = remember { MoodleClient(store.baseUrl) }
    val lecture = app.repo.lecture(lectureId) ?: return
    val course = app.repo.course(lecture.courseId)
    val kind = MoodleMatch.kindFromTitle(lecture.title)
    val mapKey = "${course?.courseNumber ?: course?.id}:${kind.name}"
    val dayMonth = Regex("""(\d{1,2})\.(\d{1,2})\.\d{4}""").find(lecture.title)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
    var state by remember { mutableStateOf<MState>(MState.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    suspend fun openCourse(c: MoodleCourse) {
        state = MState.Loading
        state = withContext(Dispatchers.IO) {
            runCatching { MState.Files(c, client.sections(c.id)) }.getOrElse { if (it is MoodleAuthRequired) MState.Login else MState.Error(it.message ?: "Error") }
        }
    }

    LaunchedEffect(reload) {
        state = MState.Loading
        val res = withContext(Dispatchers.IO) { runCatching { client.courses() } }
        val courses = res.getOrElse { state = if (it is MoodleAuthRequired) MState.Login else MState.Error(it.message ?: "Error"); return@LaunchedEffect }
        store.mappedCourse(mapKey)?.let { id -> courses.firstOrNull { it.id == id }?.let { openCourse(it); return@LaunchedEffect } }
        val ranked = MoodleMatch.rank(courses, course?.courseNumber, kind, course?.name)
        val pick = MoodleMatch.confident(ranked)
        if (pick != null) { store.mapCourse(mapKey, pick.id); openCourse(pick) }
        else state = MState.PickCourse(ranked.map { it.course } + courses.filterNot { c -> ranked.any { it.course.id == c.id } })
    }

    AppScaffold("Attach from Moodle", onBack = onBack, actions = {
        if (state is MState.Files || state is MState.PickCourse) IconButton(onClick = { store.mapCourse(mapKey, -1); reload++ }) { Icon(Icons.Default.SwapHoriz, "Change course") }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (val s = state) {
                MState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                MState.Login -> MoodleLoginView(store.baseUrl) { reload++ }
                is MState.Error -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ErrorText(s.msg); Button(onClick = { reload++ }) { Text("Retry") }
                }
                is MState.PickCourse -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text("Which Moodle course? · באיזה קורס?", style = MaterialTheme.typography.titleMedium)
                        Text("${course?.name.orEmpty()} ${course?.courseNumber?.let { "· $it" }.orEmpty()} ${kind.he}", style = MaterialTheme.typography.bodySmall) }
                    items(s.options, key = { it.id }) { c ->
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth().clickable { store.mapCourse(mapKey, c.id); scope.launch { openCourse(c) } }) {
                            Column(Modifier.padding(12.dp)) { Text(c.fullname); Text(c.shortname, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                is MState.Files -> FilePicker(s, store, dayMonth, client) { files ->
                    addSlides(app, lectureId, files.map { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", it) })
                    Toast.makeText(ctx, "Attached ${files.size} file(s)", Toast.LENGTH_SHORT).show(); onBack()
                }
            }
        }
    }
}

@Composable
private fun FilePicker(s: MState.Files, store: MoodleStore, dayMonth: Pair<Int, Int>?, client: MoodleClient, onAttach: (List<java.io.File>) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sel = remember { mutableStateListOf<Long>() }
    var busy by remember { mutableStateOf(false) }
    // Show the actual Moodle file name (e.g. "Lecture3_genetics.pdf"), not the activity/section title.
    val fileNames = remember(s) { androidx.compose.runtime.mutableStateMapOf<Long, String>() }
    LaunchedEffect(s) {
        s.sections.flatMap { it.files }.forEach { f ->
            launch(kotlinx.coroutines.Dispatchers.IO) { client.fileName(f)?.let { fileNames[f.cmid] = it } }
        }
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
            item { Text(s.course.fullname, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            if (s.sections.isEmpty()) item { Text("No files in this course.", Modifier.padding(top = 12.dp)) }
            s.sections.forEach { sec ->
                item { Text(sec.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) }
                items(sec.files, key = { it.cmid }) { f ->
                    val used = store.isUsed(f.cmid); val cached = store.cached(f.cmid) != null
                    Row(Modifier.fillMaxWidth().clickable { if (f.cmid in sel) sel.remove(f.cmid) else sel.add(f.cmid) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(f.cmid in sel, { if (it) sel.add(f.cmid) else sel.remove(f.cmid) })
                        val fn = fileNames[f.cmid] ?: store.cached(f.cmid)?.name?.substringAfter('_')
                        Column(Modifier.weight(1f)) {
                            Text(fn ?: f.name)
                            if (fn == null) Text("Loading file name…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (used || cached) AssistChip({}, { Text(if (used) "Used · cached" else "Cached") })
                        else if (MoodleMatch.suggested(fileNames[f.cmid] ?: f.name, dayMonth) || MoodleMatch.suggested(f.name, dayMonth)) AssistChip({}, { Text("Suggested") })
                    }
                }
            }
        }
        Button(enabled = sel.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp), onClick = {
            busy = true
            scope.launch {
                val all = s.sections.flatMap { it.files }.filter { it.cmid in sel }
                val res = withContext(Dispatchers.IO) {
                    all.map { f -> runCatching {
                        store.cached(f.cmid) ?: client.download(f, store.cacheDir).also { store.putCached(f.cmid, it) }
                    }.onSuccess { store.markUsed(f.cmid) } }
                }
                busy = false
                res.mapNotNull { it.exceptionOrNull() }.firstOrNull()?.let { Toast.makeText(ctx, it.message ?: "Download failed", Toast.LENGTH_LONG).show() }
                val ok = res.mapNotNull { it.getOrNull() }
                if (ok.isNotEmpty()) onAttach(ok)
            }
        }) { Text(if (busy) "Downloading…" else "Attach ${sel.size} · צרף") }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MoodleLoginView(base: String, onLoggedIn: () -> Unit) {
    var done by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Text("Sign in to Moodle (TAU). The app keeps only the session cookie — never your password.",
            Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptCookie(true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView, url: String) {
                        val host = android.net.Uri.parse(base).host
                        if (!done && android.net.Uri.parse(url).host == host && !url.contains("/login") && !url.contains("saml2")) {
                            CookieManager.getInstance().flush(); done = true; onLoggedIn()
                        }
                    }
                }
                loadUrl("$base/login/index.php")
            }
        })
    }
}
