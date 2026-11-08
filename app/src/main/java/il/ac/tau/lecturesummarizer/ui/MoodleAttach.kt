package il.ac.tau.lecturesummarizer.ui

import il.ac.tau.lecturesummarizer.data.MAX_COURSE_CONTEXT
import il.ac.tau.lecturesummarizer.data.allContext
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
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
fun MoodleAttachScreen(app: LectureApp, lectureId: String, onBack: () -> Unit, courseId: String? = null) {
    val ctx = LocalContext.current
    val store = remember { MoodleStore(ctx.applicationContext) }
    val client = remember { MoodleClient.of(store.baseUrl) }
    // courseId mode: attach course subject materials (syllabus/glossary…) instead of lecture slides.
    val lecture = if (courseId == null) app.repo.lecture(lectureId) ?: return else null
    val course = app.repo.course(courseId ?: lecture!!.courseId)
    val kind = lecture?.let { MoodleMatch.kindFromTitle(it.title) } ?: il.ac.tau.lecturesummarizer.calendar.ClassKind.LECTURE
    val mapKey = "${course?.courseNumber ?: course?.id}:${kind.name}"
    val dayMonth = Regex("""(\d{1,2})\.(\d{1,2})\.\d{4}""").find(lecture?.title.orEmpty())?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
    var state by remember { mutableStateOf<MState>(MState.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    var pickAny by remember { mutableStateOf(false) }
    val recTitle = remember { if (lecture == null) null else store.lectureRecording(lectureId) }
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
        // Imported from Panopto: only that Moodle course (no picker) — unless the user tapped androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.moodle_change).
        if (!pickAny && lecture != null) store.lectureCourse(lectureId)?.let { id -> courses.firstOrNull { it.id == id }?.let { openCourse(it); return@LaunchedEffect } }
        store.mappedCourse(mapKey)?.let { id -> courses.firstOrNull { it.id == id }?.let { openCourse(it); return@LaunchedEffect } }
        val ranked = MoodleMatch.rank(courses, course?.courseNumber, kind, course?.name)
        val pick = MoodleMatch.confident(ranked)
        if (pick != null) { store.mapCourse(mapKey, pick.id); openCourse(pick) }
        else state = MState.PickCourse(ranked.map { it.course } + courses.filterNot { c -> ranked.any { it.course.id == c.id } })
    }

    AppScaffold(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.moodle_attach), onBack = onBack, actions = {
        if (state is MState.Files || state is MState.PickCourse) IconButton(onClick = { store.mapCourse(mapKey, -1); pickAny = true; reload++ }) { Icon(Icons.Default.SwapHoriz, androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.moodle_change)) }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (val s = state) {
                MState.Loading -> SkeletonLoader(modifier=Modifier.padding(16.dp))
                MState.Login -> MoodleLoginView(store.baseUrl) { reload++ }
                is MState.Error -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ErrorText(s.msg); Button(onClick = { client.invalidate(); reload++ }) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.retry_action)) }
                }
                is MState.PickCourse -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key="MoodleAttach:static:1"){ Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.moodle_which), style = MaterialTheme.typography.titleMedium)
                        Text("${course?.name.orEmpty()} ${course?.courseNumber?.let { "· ${ltr(it)}" }.orEmpty()} ${classKindLabel(kind)}", style = MaterialTheme.typography.bodySmall) }
                    items(s.options, key = { it.id }) { c ->
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth().clickable { store.mapCourse(mapKey, c.id); scope.launch { openCourse(c) } }) {
                            Column(Modifier.padding(12.dp)) { Text(c.fullname); Text(c.shortname, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                is MState.Files -> FilePicker(s, store, dayMonth, client, recTitle) { files ->
                    val uris = files.map { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", it) }
                    if (courseId != null) {
                        var added = 0
                        app.repo.updateCourse(courseId) { c ->
                            val all = (c.allContext + uris.map { it.toString() }.filterNot { it in c.allContext }).take(MAX_COURSE_CONTEXT)
                            added = all.size - c.allContext.size
                            c.copy(contextPdfUri = all.firstOrNull(), extraContext = all.drop(1))
                        }
                        Toast.makeText(ctx, ctx.getString(il.ac.tau.lecturesummarizer.R.string.background_added, added, MAX_COURSE_CONTEXT), Toast.LENGTH_SHORT).show(); onBack()
                    } else {
                        addSlides(app, lectureId, uris)
                        Toast.makeText(ctx, ctx.getString(il.ac.tau.lecturesummarizer.R.string.files_attached, files.size), Toast.LENGTH_SHORT).show(); onBack()
                    }
                }
            }
        }
    }
}

@Composable
private fun FilePicker(s: MState.Files, store: MoodleStore, dayMonth: Pair<Int, Int>?, client: MoodleClient, recTitle: String? = null, onAttach: (List<java.io.File>) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Pre-select PDF/PPTX that match the imported Panopto recording (e.g. "Lecture 3" → lecture3.pdf).
    val sel = remember(s) { mutableStateListOf<Long>().apply {
        addAll(s.sections.flatMap { it.files }.filter { !store.isUsed(it.cmid) && MoodleMatch.matchesRecording(it.name, recTitle) }.map { it.cmid })
    } }
    var busy by remember { mutableStateOf(false) }
    // Show the actual Moodle file name (e.g. "Lecture3_genetics.pdf"), not the activity/section title.
    val fileNames = remember(s) { androidx.compose.runtime.mutableStateMapOf<Long, String>() }
    LaunchedEffect(s) {
        s.sections.flatMap { it.files }.forEach { f ->
            launch(kotlinx.coroutines.Dispatchers.IO) { client.fileName(f)?.let { fileNames[f.cmid] = it } }
        }
    }
    val total = s.sections.sumOf { it.files.size }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key="MoodleAttach:static:2"){
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🎓", fontSize = 28.sp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.course.fullname, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.moodle_counts, total, s.sections.size), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                        }
                    }
                }
            }
            if (s.sections.isEmpty()) item(key="MoodleAttach:static:3"){ Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.moodle_empty), Modifier.padding(top = 12.dp)) }
            s.sections.forEach { sec ->
                item(key = "h:" + sec.title + sec.files.firstOrNull()?.cmid) {
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("📂", fontSize = 18.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(sec.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val n = sec.files.count { it.cmid in sel }
                        Text(if (n > 0) "$n/${sec.files.size}" else "${sec.files.size}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(sec.files, key = { it.cmid }) { f ->
                    val used = store.isUsed(f.cmid); val cached = store.cached(f.cmid) != null
                    val fn = fileNames[f.cmid] ?: store.cached(f.cmid)?.name?.substringAfter('_')
                    val type = MoodleFileType.of(fn ?: f.name, f.module)
                    val selected = f.cmid in sel
                    val suggested = !used && !cached && (MoodleMatch.suggested(fileNames[f.cmid] ?: f.name, dayMonth) || MoodleMatch.suggested(f.name, dayMonth)
                        || MoodleMatch.matchesRecording(fileNames[f.cmid] ?: f.name, recTitle))
                    val toggle = { if (selected) sel.remove(f.cmid) else sel.add(f.cmid); Unit }
                    Surface(onClick = toggle, shape = RoundedCornerShape(16.dp),
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                        modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(type.color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                                Text(type.emoji, fontSize = 24.sp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(fn ?: f.name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    FileBadge(fileTypeLabel(type), type.color.copy(alpha = 0.15f), type.color)
                                    if (used) FileBadge(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.file_used), MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                                    if (cached) StatusChip(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.file_cached))
                                    if (suggested) StatusChip(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.file_suggested),MaterialTheme.colorScheme.primary)
                                }
                                if (fn == null) Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.moodle_loading_name), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Checkbox(selected, { if (it) sel.add(f.cmid) else sel.remove(f.cmid) })
                        }
                    }
                }
            }
        }
        Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (sel.isNotEmpty()) {
                    TextButton(onClick = { sel.clear() }, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.moodle_clear)) }
                    Spacer(Modifier.width(8.dp))
                }
                Button(enabled = sel.isNotEmpty() && !busy, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp), onClick = {
                    busy = true
                    scope.launch {
                        val all = s.sections.flatMap { it.files }.filter { it.cmid in sel }
                        val res = withContext(Dispatchers.IO) {
                            all.map { f -> runCatching {
                                store.cached(f.cmid) ?: client.download(f, store.cacheDir).also { store.putCached(f.cmid, it) }
                            }.onSuccess { store.markUsed(f.cmid) } }
                        }
                        busy = false
                        res.mapNotNull { it.exceptionOrNull() }.firstOrNull()?.let { Toast.makeText(ctx, ctx.getString(il.ac.tau.lecturesummarizer.R.string.moodle_download_failed), Toast.LENGTH_LONG).show() }
                        val ok = res.mapNotNull { it.getOrNull() }
                        if (ok.isNotEmpty()) onAttach(ok)
                    }
                }) {
                    if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)); Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.moodle_downloading)) }
                    else Text(if (sel.isEmpty()) androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.moodle_select) else androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.files_attach_count, sel.size))
                }
            }
        }
    }
}

@Composable
private fun FileBadge(text: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color) {
    Text(text, Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall, color = fg, fontWeight = FontWeight.Medium, maxLines = 1)
}

/**
 * Moodle (or Panopto) sign-in WebView with autofill:
 *  - remembered university username (MoodleStore.username, prefs; never the password) is injected into the IdP form if empty;
 *  - the username typed on submit is captured and remembered;
 *  - WebView is marked important-for-autofill so Android Autofill / Google Password Manager can save + fill the password.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
internal fun MoodleLoginView(base: String, startUrl: String = "$base/login/index.php",
                             isDone: (String) -> Boolean = { url -> android.net.Uri.parse(url).host == android.net.Uri.parse(base).host && !url.contains("/login") && !url.contains("saml2") },
                             /** Extra signed-in probe (e.g. Panopto auth cookie present) checked on every navigation and periodically. */
                             sessionReady: (() -> Boolean)? = null,
                             onLoggedIn: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { MoodleStore(ctx.applicationContext) }
    var done by remember { mutableStateOf(false) }
    val finish = { if (!done) { CookieManager.getInstance().flush(); done = true; onLoggedIn() } }
    if (sessionReady != null) LaunchedEffect(Unit) {
        while (!done) { if (runCatching { sessionReady() }.getOrDefault(false)) finish(); kotlinx.coroutines.delay(1500) }
    }
    Column(Modifier.fillMaxSize()) {
        Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.moodle_signin_help),
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
        if (sessionReady != null) TextButton(onClick = { finish() }, Modifier.padding(horizontal = 4.dp)) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.moodle_signed_in)) }
        AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                settings.javaScriptCanOpenWindowsAutomatically = true
                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_YES
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                addJavascriptInterface(object {
                    @android.webkit.JavascriptInterface fun username(u: String) { if (u.isNotBlank()) store.username = u }
                }, "LsAndroid")
                fun check(url: String?): Boolean {
                    if (done || url == null) return done
                    if (isDone(url) || (sessionReady?.let { runCatching { it() }.getOrDefault(false) } == true)) { post { finish() }; return true }
                    return false
                }
                webViewClient = object : WebViewClient() {
                    override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) { check(url) }
                    override fun onPageFinished(v: WebView, url: String) {
                        if (check(url)) return
                        // Only touch IdP/Moodle login forms — never inject into Panopto pages (it filled the Viewer search box).
                        if (url.contains("panopto", true)) return
                        val u = org.json.JSONObject.quote(store.username.orEmpty())
                        v.evaluateJavascript(LOGIN_JS.replace("__USER__", u), null)
                    }
                }
                loadUrl(startUrl)
            }
        })
    }
}

/** Fill the IdP username field if empty; remember what the user submits. Password is never touched. */
private const val LOGIN_JS = """(function(){var u=__USER__;
var f=document.querySelector('input[name=username],input[name=user],input[name=j_username],input[id*=user i],input[autocomplete=username]')
 ||document.querySelector('input[type=text],input[type=email]');
if(!f)return; f.setAttribute('autocomplete','username');
var p=document.querySelector('input[type=password]'); if(p)p.setAttribute('autocomplete','current-password');
if(u&&!f.value){f.value=u;f.dispatchEvent(new Event('input',{bubbles:true}));f.dispatchEvent(new Event('change',{bubbles:true}));}
var form=f.form; if(form&&!form.__ls){form.__ls=1;form.addEventListener('submit',function(){try{LsAndroid.username(f.value)}catch(e){}});}
})();"""
