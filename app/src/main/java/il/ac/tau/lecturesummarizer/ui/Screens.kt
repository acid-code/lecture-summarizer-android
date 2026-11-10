package il.ac.tau.lecturesummarizer.ui

import il.ac.tau.lecturesummarizer.R

import androidx.compose.foundation.combinedClickable

import il.ac.tau.lecturesummarizer.data.allSlides
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
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
import il.ac.tau.lecturesummarizer.data.MAX_COURSE_CONTEXT
import il.ac.tau.lecturesummarizer.data.allContext
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Slideshow
import il.ac.tau.lecturesummarizer.upload.JobPollWorker
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
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
import il.ac.tau.lecturesummarizer.calendar.CalendarResolver
import il.ac.tau.lecturesummarizer.calendar.TauClass
import il.ac.tau.lecturesummarizer.upload.UploadWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

// ---------- Courses ----------

@Composable
fun CoursesScreen(app: LectureApp, onOpen: (String) -> Unit, onSettings: () -> Unit, onRecordLecture: (String) -> Unit = {}) {
    val courses by app.repo.courses.collectAsStateWithLifecycle()
    val all by app.repo.lectures.collectAsStateWithLifecycle()
    val unread by app.unread.ids.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<il.ac.tau.lecturesummarizer.data.Course?>(null) }
    var courseActions by remember{mutableStateOf<il.ac.tau.lecturesummarizer.data.Course?>(null)}
    var deleting by remember { mutableStateOf<il.ac.tau.lecturesummarizer.data.Course?>(null) }
    val ctx = LocalContext.current
    var picks by remember { mutableStateOf<List<TauClass>>(emptyList()) }
    var pendingNew by remember { mutableStateOf<Pair<il.ac.tau.lecturesummarizer.data.Course, TauClass>?>(null) }
    fun startIn(course: il.ac.tau.lecturesummarizer.data.Course, c: TauClass) {
        val date = java.text.SimpleDateFormat("d.M.yyyy", java.util.Locale.US).format(Date())
        val title = if (c.offScheme) ctx.getString(il.ac.tau.lecturesummarizer.R.string.generated_event, date) else when (c.kind) {
            il.ac.tau.lecturesummarizer.calendar.ClassKind.LECTURE -> ctx.getString(il.ac.tau.lecturesummarizer.R.string.generated_lecture, date)
            il.ac.tau.lecturesummarizer.calendar.ClassKind.RECITATION -> ctx.getString(il.ac.tau.lecturesummarizer.R.string.generated_recitation, date)
            il.ac.tau.lecturesummarizer.calendar.ClassKind.UNKNOWN -> date
        }
        val l = app.repo.addLecture(course.id, title); picks = emptyList(); onRecordLecture(l.id)
    }
    fun startFor(c: TauClass) {
        picks = emptyList()
        if (c.offScheme) {
            val before = app.repo.courses.value.map { it.id }.toSet()
            val course = app.repo.findOrCreateCourse(c.courseName, null)
            // Newly created from a free-form event title: let the user rename it before recording.
            if (course.id !in before) { pendingNew = course to c; return }
            startIn(course, c); return
        }
        startIn(app.repo.findOrCreateCourse(c.courseName, c.courseNumber), c)
    }
    fun resolve() {
        val found = CalendarResolver.currentClasses(ctx, app.settings.calendarIds)
        when (found.size) {
            0 -> android.widget.Toast.makeText(ctx, if (CalendarResolver.hasPermission(ctx)) ctx.getString(il.ac.tau.lecturesummarizer.R.string.calendar_no_current) else ctx.getString(il.ac.tau.lecturesummarizer.R.string.calendar_access_off), android.widget.Toast.LENGTH_LONG).show()
            1 -> startFor(found[0])
            else -> picks = found
        }
    }
    val calPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resolve() }
    AppScaffold(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_notebooks), logo = true,
        actions = {
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.settings_title)) }
        },
        fab = {
            ExtendedFloatingActionButton(onClick={adding=true},icon={Icon(Icons.Default.Add,null)},text={Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_new_notebook))})
        }
    ) { pad ->
        val (online, recheck) = rememberOnline()
        if (courses.isEmpty()) Column(Modifier.padding(pad).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (!online) OfflineState(recheck)
            EmptyState(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.es_courses_title),
                androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.es_courses_body)) {
                CalendarImportButton(app)
                TextButton(onClick = { adding = true }) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.manual_course)) }
            }
        }
        else Column(Modifier.padding(pad)) {
            if (!online) OfflineState(recheck)
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            androidx.compose.foundation.lazy.grid.GridCells.Adaptive(150.dp), Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(courses.size, key = { courses[it].id }) { i ->
                val c = courses[i]
                val n = all.count { it.courseId == c.id }
                val newCount = all.count { it.courseId == c.id && it.id in unread }
                Column {
                    Box(Modifier.fillMaxWidth()) {
                    NotebookCover(c.name, androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.course_lecture_count, n) + (if (c.allContext.isNotEmpty()) androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.background_count, c.allContext.size) else ""),
                        c.coverEmoji(), c.coverColorValue(), patternOf(c.pattern), Modifier.fillMaxWidth(),footer={CourseUpdated(ctx,c.id)},onLongClick={courseActions=c}) { onOpen(c.id) }
                    if (newCount > 0) UnreadCountBadge(newCount, Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp))
                    }
                }
            }
        }
        }
    }
    if (adding) NotebookEditor(null, onDismiss = { adding = false }) { name, e, col, pat ->
        app.repo.addCourse(name, e, col, pat); adding = false
    }
    if (picks.isNotEmpty()) AlertDialog(onDismissRequest = { picks = emptyList() }, title = { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_which_class_are_you_recording)) },
        text = { Column { picks.forEach { c -> TextButton(onClick = { startFor(c) }, modifier = Modifier.fillMaxWidth()) {
            Text(listOfNotNull(c.courseName, classKindLabel(c.kind).takeIf { c.kind != il.ac.tau.lecturesummarizer.calendar.ClassKind.UNKNOWN }, c.room).joinToString(" · ")) } } } },
        confirmButton = {}, dismissButton = { TextButton(onClick = { picks = emptyList() }) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_cancel)) } })
    pendingNew?.let { (course, cls) -> NotebookEditor(course, onDismiss = {
        pendingNew = null; app.repo.course(course.id)?.let { startIn(it, cls) }
    }) { name, e, col, pat ->
        app.repo.updateCourse(course.id) { it.copy(name = name, emoji = e, color = col, pattern = pat) }
        pendingNew = null; app.repo.course(course.id)?.let { startIn(it, cls) }
    } }
    deleting?.let { c -> DeleteCourseDialog(c.name, all.count { it.courseId == c.id }, { deleting = null }) { deleting = null; deleteCourseEverywhere(app, ctx, c.id) } }
    courseActions?.let{c->CourseActionsSheet(c.name,{courseActions=null},{courseActions=null;editing=c},{courseActions=null;deleting=c})}
    editing?.let { c -> NotebookEditor(c, onDismiss = { editing = null }) { name, e, col, pat ->
        app.repo.updateCourse(c.id) { it.copy(name = name, emoji = e, color = col, pattern = pat) }; editing = null
    } }
}

// ---------- Lectures ----------

@Composable
fun LecturesScreen(
    app: LectureApp, courseId: String, onBack: () -> Unit,
    onRecord: (String) -> Unit, onAttach: (String) -> Unit, onJob: (String) -> Unit, onNew: (String) -> Unit = onRecord, onPanopto: (String) -> Unit = {}, onMoodleCourse: () -> Unit = {},
) {
    val retryScope = rememberCoroutineScope()
    val all by app.repo.lectures.collectAsStateWithLifecycle()
    val lectures = all.filter { it.courseId == courseId }.sortedByDescending { it.recordedAtMs }
    val unread by app.unread.ids.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val courses by app.repo.courses.collectAsStateWithLifecycle()
    val course = courses.firstOrNull { it.id == courseId }
    val subjectPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { runCatching { ctx.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        if (uris.isNotEmpty()) app.repo.updateCourse(courseId) { c ->
            val all = (c.allContext + uris.map { it.toString() }.filterNot { it in c.allContext }).take(MAX_COURSE_CONTEXT)
            c.copy(contextPdfUri = all.firstOrNull(), extraContext = all.drop(1))
        }
    }
    val download = rememberDownloader()
    var editCover by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<il.ac.tau.lecturesummarizer.data.Lecture?>(null) }
    var moveLecture by remember { mutableStateOf<il.ac.tau.lecturesummarizer.data.Lecture?>(null) }
    moveLecture?.let{l->LectureMoveDialog(app,l){moveLecture=null}}
    toDelete?.let { d -> DeleteLectureDialog(d.title, { toDelete = null }) { toDelete = null; deleteLectureEverywhere(app, ctx, d.id) {} } }
    AppScaffold(course?.name ?: androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.lectures_title), onBack = onBack,
        fab = { ExtendedFloatingActionButton(onClick={adding=true},icon={Icon(Icons.Default.Add,null)},text={Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.add_lecture))}) }

    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            course?.let { c -> item(key="Screens:static:1"){ CourseHeader(c, androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.course_lecture_count, lectures.size)) { editCover = true };CourseUpdated(ctx,c.id) };item(key="Screens:static:2"){ CourseSourcesButton(app,c) } }
            item(key="Screens:static:3"){
                var backgroundOpen by remember{mutableStateOf(false)}
                OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val files = course?.allContext.orEmpty()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_course_background_materials), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            TextButton(onClick={backgroundOpen=!backgroundOpen}){Text(if(backgroundOpen)androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.collapse)else androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.expand))}
                            Text(ltr("${files.size}/$MAX_COURSE_CONTEXT"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if(backgroundOpen){
                        Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.context_body, MAX_COURSE_CONTEXT),
                            style = MaterialTheme.typography.bodySmall)
                        MaterialsList(files.map { s ->
                            val name = displayName(ctx, s); val u = Uri.parse(s)
                            val mime = runCatching { ctx.contentResolver.getType(u) }.getOrNull() ?: if (name.endsWith(".pdf", true)) "application/pdf" else "application/octet-stream"
                            Material(s, name, if (mime.contains("pdf")) "PDF" else androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.file_slides), if (mime.contains("pdf")) Icons.Default.PictureAsPdf else Icons.Default.Slideshow, u, mime, true)
                        }, onSave = { download(it.uri, it.name, it.mime) }, onRemove = { m ->
                            app.repo.updateCourse(courseId) { c -> val rest = c.allContext - m.key; c.copy(contextPdfUri = rest.firstOrNull(), extraContext = rest.drop(1)) }
                        })
                        OutlineAction(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_add_pdf), Icons.Default.NoteAdd, Modifier.fillMaxWidth(), enabled = files.size < MAX_COURSE_CONTEXT) {
                            subjectPicker.launch(arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.presentationml.presentation"))
                        }
                        OutlineAction(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_attach_from_moodle), Icons.Default.School, Modifier.fillMaxWidth(), enabled = files.size < MAX_COURSE_CONTEXT) { onMoodleCourse() }
                        if (files.size >= MAX_COURSE_CONTEXT) Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.context_max, MAX_COURSE_CONTEXT),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (lectures.isEmpty()) item(key="Screens:static:4"){
                EmptyState(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.es_lectures_title),
                    androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.es_lectures_body)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onPanopto(courseId) }) {
                            Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.es_lectures_panopto)) }
                        Button(onClick = {
                            val date = java.text.SimpleDateFormat("d.M.yyyy", java.util.Locale.US).format(Date())
                            onRecord(app.repo.addLecture(courseId, ctx.getString(il.ac.tau.lecturesummarizer.R.string.generated_lecture, date)).id)
                        }) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.es_lectures_record)) }
                    }
                }
            }
            items(lectures, key = { it.id }) { l ->
                NotebookCard(l.title,
                    "${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(l.recordedAtMs))} · " +
                        "${formatElapsed(l.displayDurationMs)}" +
                        (l.stageDetail?.takeUnless { l.state != LectureState.DONE && il.ac.tau.lecturesummarizer.upload.isTerminalStageText(it) }?.takeIf { l.state == LectureState.PROCESSING || l.state == LectureState.UPLOADING || l.liveJob }?.let { " · $it" } ?: ""),
                    course?.coverColorValue() ?: coverColor(l.courseId), status = { LectureStatusChip(l, onRetry = { retryScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { il.ac.tau.lecturesummarizer.upload.retryLecture(app, l.id) }.onFailure { app.repo.updateLecture(l.id) { it.copy(error = app.getString(il.ac.tau.lecturesummarizer.R.string.retry_failed)) } } } }) }, pattern = patternOf(course?.pattern), onDelete = { toDelete = l }, onMove={moveLecture=l}, isNew = l.id in unread) {
                    when (l.state) {
                        LectureState.DRAFT -> if (l.allSlides.isNotEmpty() || l.fromPanopto) onAttach(l.id) else onNew(l.id)
                        LectureState.RECORDING -> onRecord(l.id)
                        LectureState.RECORDED -> onAttach(l.id)  // Materials screen offers androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.record_resume)
                        else -> onJob(l.id)
                    }
                }
            }
        }
    }
    if(adding)AddLectureSheet(onDismiss={adding=false},onRecord={adding=false;val title=ctx.getString(il.ac.tau.lecturesummarizer.R.string.generated_lecture,DateFormat.getDateInstance().format(Date()));onRecord(app.repo.addLecture(courseId,title).id)},onPanopto={adding=false;onPanopto(courseId)},onUpload={uris->adding=false;val id=app.repo.addLecture(courseId,ctx.getString(il.ac.tau.lecturesummarizer.R.string.imported_files_title)).id;uris.forEach{persist(ctx,it)};retryScope.launch{runCatching{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){uris.forEach{uri->if(audioLike(ctx,uri))importAudio(app,id,uri,ctx)else addSlides(app,id,listOf(uri))}}}.onFailure{app.repo.updateLecture(id){it.copy(error=ctx.getString(il.ac.tau.lecturesummarizer.R.string.import_failed))}};onAttach(id)}})
    if (editCover && course != null) NotebookEditor(course, onDismiss = { editCover = false }) { name, e, col, pat ->
        app.repo.updateCourse(courseId) { it.copy(name = name, emoji = e, color = col, pattern = pat) }; editCover = false
    }
}

// ---------- Record ----------

@Composable
fun RecordScreen(app: LectureApp, lectureId: String, onBack: () -> Unit, onDone: () -> Unit, autoResume: Boolean = false, onMoodle: () -> Unit = {}) {
    val ctx = LocalContext.current
    val captionSession = remember { il.ac.tau.lecturesummarizer.captions.CaptionSession(ctx) }
    var showCaptionAgree by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { captionSession.release() } }
    // Attach materials while recording (same as "Upload slides"/Moodle on Materials; sent with the final upload).
    val livePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        uris.forEach { persist(ctx, it) }; addSlides(app, lectureId, uris)
        Toast.makeText(ctx, ctx.getString(il.ac.tau.lecturesummarizer.R.string.files_attached, uris.size), Toast.LENGTH_SHORT).show()
    }
    val rec by RecorderService.state.collectAsStateWithLifecycle()
    val lecture by app.repo.lectures.collectAsStateWithLifecycle()
    val l = lecture.firstOrNull { it.id == lectureId }
    val active = rec.recording && rec.lectureId == lectureId
    val paused = active && rec.paused
    val otherActive = rec.recording && rec.lectureId != lectureId
    val localeRtl = LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    LaunchedEffect(active, paused, l?.id) {
        if (!active) return@LaunchedEffect
        if (paused) captionSession.onRecordingPaused()
        else {
            val gates = captionSession.prepareGates()
            when {
                gates.needsAgreement -> showCaptionAgree = true
                gates.needsDownload -> Unit
                captionSession.state.value.run == il.ac.tau.lecturesummarizer.captions.CaptionRunState.Off ||
                    captionSession.state.value.run == il.ac.tau.lecturesummarizer.captions.CaptionRunState.Paused -> {
                    if (captionSession.state.value.run == il.ac.tau.lecturesummarizer.captions.CaptionRunState.Paused)
                        captionSession.onRecordingResumed()
                    else captionSession.start(l, localeRtl)
                }
            }
        }
    }
    LaunchedEffect(active) {
        if (!active) captionSession.onRecordingStopped()
    }
    if (showCaptionAgree) {
        CaptionsAgreementDialog(
            onAgree = { showCaptionAgree = false; captionSession.agreeAndDownload() },
            onDismiss = { showCaptionAgree = false },
        )
    }

    val perms = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) RecorderService.start(ctx, lectureId) else denied = true
    }
    // Auto-start once for a brand-new empty lecture (mic / calendar-arm flow).
    var autoStarted by androidx.compose.runtime.saveable.rememberSaveable(lectureId) { mutableStateOf(false) }
    val isEmpty = l != null && l.segments.isEmpty()
    LaunchedEffect(lectureId, isEmpty) {
        if (autoStarted || !isEmpty || rec.recording) return@LaunchedEffect
        autoStarted = true
        val missing = perms.any { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (missing) launcher.launch(perms) else RecorderService.start(ctx, lectureId)
    }

    // "Record another": start a new segment right away, and when it stops go back to Materials (Moodle attach).
    var anotherStarted by androidx.compose.runtime.saveable.rememberSaveable(lectureId) { mutableStateOf(false) }
    LaunchedEffect(autoResume, l != null) {
        if (!autoResume || anotherStarted || l == null || rec.recording) return@LaunchedEffect
        anotherStarted = true; autoStarted = true
        val missing = perms.any { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (missing) launcher.launch(perms) else RecorderService.start(ctx, lectureId)
    }
    var wasActive by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) wasActive = true
        else if (wasActive) { wasActive = false; onDone() }
    }

    val courses by app.repo.courses.collectAsStateWithLifecycle()
    var moving by remember { mutableStateOf(false) }
    if(moving&&l!=null)LectureMoveDialog(app,l){moving=false}

    AppScaffold(l?.title ?: androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.record_title), onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val prior = l?.segments?.sumOf { it.durationMs } ?: 0
            Text(formatElapsed(if (active) rec.elapsedMs else prior), fontSize = 60.sp, fontWeight = FontWeight.Light,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = if (active && !paused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text(if (paused) androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.record_paused, l?.segments?.size ?: 0)
                 else if (active) androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.record_active, rec.segmentSeq+1)
                 else androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.record_stopped, l?.segments?.size ?: 0))
            Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_recording_continues_with_the_screen_off_audio_is_saved_in_10_minu),
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            if (active) CaptionControlRow(captionSession, Modifier.fillMaxWidth())
            l?.let { cur -> TextButton(onClick = { moving = true }) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.record_course_change, bidi(courses.firstOrNull { it.id == cur.courseId }?.name ?: "?"))) } }
            if(active && l!=null)FilledTonalButton(onClick={app.repo.updateLecture(l.id){it.copy(markers=it.markers+il.ac.tau.lecturesummarizer.data.Marker(rec.elapsedMs/1000,ctx.getString(il.ac.tau.lecturesummarizer.R.string.important_marker)))}},colors=ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.tertiaryContainer,contentColor=MaterialTheme.colorScheme.onTertiaryContainer)){Icon(Icons.Default.Star,null);Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.important_marker))}
            if (otherActive) Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_another_lecture_is_recording_stop_it_first), color = MaterialTheme.colorScheme.error)
            if (denied) Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_microphone_permission_is_required), color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            if (!active) {
                Button(enabled = !otherActive, onClick = {
                    val missing = perms.any { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
                    if (missing) launcher.launch(perms) else RecorderService.start(ctx, lectureId)
                }, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                    Text(if ((l?.segments?.size ?: 0) > 0) androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.record_resume) else androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.record_start))
                }
                if ((l?.segments?.size ?: 0) > 0) OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_next_add_materials_and_upload))
                } else TextButton(onClick = onDone) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_no_recording_add_slides_or_import_audio)) }
            } else if (paused) {
                Button(onClick = { RecorderService.resume(ctx) }, modifier = Modifier.fillMaxWidth().height(96.dp)) {
                    Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_resume_recording), fontSize = 22.sp)
                }
                OutlinedButton(onClick = { RecorderService.stop(ctx) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_end_lecture_and_prepare_upload))
                }
            } else {
                Button(onClick = { RecorderService.pause(ctx) }, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                    Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_pause_for_a_break), fontSize = 20.sp)
                }
                Button(onClick = { RecorderService.stop(ctx) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_stop)) }
            }
        }
    }
}


@Composable
fun CopyableId(label: String, value: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Row(modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$label ${ltr(value.take(8))}", style = MaterialTheme.typography.labelSmall, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(modifier = Modifier.size(28.dp), onClick = {
            val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
            Toast.makeText(ctx, ctx.getString(il.ac.tau.lecturesummarizer.R.string.id_copied), Toast.LENGTH_SHORT).show()
        }) { Icon(androidx.compose.material.icons.Icons.Filled.ContentCopy, contentDescription = androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.id_copy, label), modifier = Modifier.size(16.dp)) }
    }
}

/** Long server errors (e.g. Gemini 429 JSON) are truncated to ~280 chars with a Show more toggle. */
@Composable
internal fun ErrorText(msg: String, max: Int = 280) {
    var open by remember(msg) { mutableStateOf(false) }
    val long = msg.length > max
    Text(if (long && !open) msg.take(max) + "…" else msg, color = MaterialTheme.colorScheme.error)
    if (long) TextButton(onClick = { open = !open }) { Text(if (open) androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.show_less) else androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.show_more)) }
}

// ---------- shared UI helpers ----------

private val covers = listOf(0xFF1E5AA8, 0xFFB23A48, 0xFF2E7D5B, 0xFF7B4FA0, 0xFFD07A1F, 0xFF00796B, 0xFF5D4037, 0xFF3949AB)
fun coverColor(key: String) = Color(covers[Math.floorMod(key.hashCode(), covers.size)])

@Composable
fun stateLabel(s:LectureState)=androidx.compose.ui.res.stringResource(when(s){
LectureState.DRAFT->il.ac.tau.lecturesummarizer.R.string.st_draft
LectureState.RECORDING->il.ac.tau.lecturesummarizer.R.string.st_recording
LectureState.RECORDED->il.ac.tau.lecturesummarizer.R.string.st_waiting
LectureState.UPLOADING->il.ac.tau.lecturesummarizer.R.string.uploading_slides
LectureState.PROCESSING->il.ac.tau.lecturesummarizer.R.string.st_summarizing
LectureState.DONE->il.ac.tau.lecturesummarizer.R.string.st_done
LectureState.FAILED->il.ac.tau.lecturesummarizer.R.string.st_failed_offline
})

/** Lecture row: inherits a lighter version of the course cover (pale pattern + colored spine). */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun NotebookCard(title: String, subtitle: String, color: Color, badge: String? = null, status: (@Composable () -> Unit)? = null, pattern: CoverPattern = CoverPattern.SOLID, onDelete: (() -> Unit)? = null, onMove:(()->Unit)?=null, isNew: Boolean = false, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().combinedClickable(onClick=onClick,onLongClick=onMove), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Box(Modifier.height(IntrinsicSize.Min)) {
            CoverBackground(color, pattern, light = true, modifier = Modifier.matchParentSize())
            Row {
                Box(Modifier.width(6.dp).fillMaxHeight().background(color))
                Column(Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isNew) NewDot(Modifier.padding(end = 8.dp))
                        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (isNew) Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.new_badge), Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50)).background(UnreadRed).padding(horizontal = 8.dp, vertical = 2.dp),
                            color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    badge?.let {
                        Text(it, Modifier.padding(top = 2.dp).clip(RoundedCornerShape(50)).background(color).padding(horizontal = 10.dp, vertical = 3.dp),
                            color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                    status?.let { Box(Modifier.padding(top = 2.dp)) { it() } }
                }
                if(onMove!=null){
                    var menu by remember{mutableStateOf(false)}
                    Box(Modifier.align(Alignment.CenterVertically)){
                        IconButton(onClick={menu=true}){Icon(Icons.Default.MoreVert,androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.more_actions))}
                        DropdownMenu(menu,{menu=false}){
                            DropdownMenuItem(text={Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.move_to_course))},onClick={menu=false;onMove()})
                            onDelete?.let{remove->DropdownMenuItem(text={Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.delete_lecture))},onClick={menu=false;remove()})}
                        }
                    }
                }else onDelete?.let { IconButton(onClick = it, modifier = Modifier.align(Alignment.CenterVertically)) {
                    Icon(Icons.Default.Delete, androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.delete_lecture), tint = MaterialTheme.colorScheme.onSurfaceVariant) } }
            }
        }
    }
}

val UnreadRed = Color(0xFFE53935)

/** Red count bubble on a course cover: lectures with a finished summary not opened yet. */
@Composable
fun UnreadCountBadge(n: Int, modifier: Modifier = Modifier) =
    Box(modifier.defaultMinSize(minWidth = 24.dp, minHeight = 24.dp).clip(RoundedCornerShape(50)).background(UnreadRed).border(2.dp, MaterialTheme.colorScheme.surface, RoundedCornerShape(50))
        .padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Text(if (n > 99) "99+" else "$n", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }

/** Pulsing red dot on a lecture whose (re)summary finished and wasn't opened yet. */
@Composable
fun NewDot(modifier: Modifier = Modifier) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "newdot")
    val a by t.animateFloat(initialValue = 0.45f, targetValue = 1f, animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween<Float>(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
    Box(modifier.size(12.dp).graphicsLayer { alpha = a }.clip(androidx.compose.foundation.shape.CircleShape).background(UnreadRed))
}

fun safeStart(ctx: android.content.Context, i: Intent) {
    try { ctx.startActivity(i) } catch (e: android.content.ActivityNotFoundException) {
        Toast.makeText(ctx, ctx.getString(il.ac.tau.lecturesummarizer.R.string.file_no_app), Toast.LENGTH_SHORT).show()
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
    ctx.startActivity(Intent.createChooser(send.setType("audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), ctx.getString(il.ac.tau.lecturesummarizer.R.string.share_recording)))
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

/**
 * User settings (drafts/app-polish/settings.md): language, summary levels, connections, auto-sync, notifications, about.
 * Server URL / API token / device id / Mock live under the hidden "מפתחים" section (debug builds only; never in release).
 */
@Composable
fun SettingsScreen(app: LectureApp, onBack: () -> Unit, onMoodle: () -> Unit = {}, onPanopto: () -> Unit = {}) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf(app.settings.baseUrl) }
    var token by remember { mutableStateOf(app.settings.apiToken) }
    var mock by remember { mutableStateOf(app.settings.mockMode) }
    val dev = app.settings.showDeveloper
    var calendarFlag by remember { mutableStateOf(app.settings.calendarRulesEnabled) }
    val save = { if (dev) { app.settings.baseUrl = url; app.settings.apiToken = token; if (il.ac.tau.lecturesummarizer.BuildConfig.DEBUG) app.settings.mockMode = mock } }
    androidx.activity.compose.BackHandler { save(); onBack() }
    AppScaffold(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.settings), onBack = { save(); onBack() }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ConnectionsSection(onMoodle, onPanopto)
            CaptionsSettingsSection()
            SummaryDensitySection(app)
            CalendarSourceSection(app)
            il.ac.tau.lecturesummarizer.ui.AutoSyncSettingsSection(app)
            NotificationsSection()
            LanguageSection()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.calendar_rules_flag), Modifier.weight(1f))
                    Switch(calendarFlag, { calendarFlag = it; app.settings.calendarRulesEnabled = it })
                }
            CalendarRulesSection(app, calendarFlag)
            // About / version. Release has no developer-menu entry point.
            HorizontalDivider()
            SectionTitle(il.ac.tau.lecturesummarizer.R.string.set_about)
            val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
            Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.set_version, version),
                Modifier.fillMaxWidth().padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
            Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.set_privacy), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (dev) {
                HorizontalDivider()
                SectionTitle(il.ac.tau.lecturesummarizer.R.string.dev_title)
                OutlinedTextField(url, { url = it }, label = { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.dev_base_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(token, { token = it }, label = { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.dev_token)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                val clip = androidx.compose.ui.platform.LocalClipboardManager.current
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.dev_device, app.settings.deviceId.take(8)), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(app.settings.deviceId)) }) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.dev_copy)) }
                }
                // Mock is debug-only (Settings.mockMode is forced false in release).
                if (il.ac.tau.lecturesummarizer.BuildConfig.DEBUG) Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(mock, { mock = it }); Spacer(Modifier.width(12.dp)); Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.dev_mock))
                }

            }
        }
    }
}

@Composable
private fun CaptionsSettingsSection() {
    val ctx = LocalContext.current
    val store = remember { il.ac.tau.lecturesummarizer.captions.CaptionModelStore(ctx) }
    var tick by remember { mutableIntStateOf(0) }
    val state = remember(tick) { store.refreshReadyState(); store.downloadState }
    val status = when (state) {
        il.ac.tau.lecturesummarizer.captions.CaptionDownloadState.Ready ->
            androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.set_captions_status_ready)
        il.ac.tau.lecturesummarizer.captions.CaptionDownloadState.Downloading ->
            androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.set_captions_status_downloading)
        il.ac.tau.lecturesummarizer.captions.CaptionDownloadState.Failed ->
            androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.set_captions_status_failed)
        else -> androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.set_captions_status_idle)
    }
    HorizontalDivider()
    SectionTitle(il.ac.tau.lecturesummarizer.R.string.set_captions)
    Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.set_captions_body),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(status, style = MaterialTheme.typography.bodyMedium)
    OutlinedButton(onClick = {
        store.freeSpace()
        Toast.makeText(ctx, ctx.getString(il.ac.tau.lecturesummarizer.R.string.set_captions_free_done), Toast.LENGTH_SHORT).show()
        tick++
    }) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.set_captions_free)) }
}

@Composable
private fun SectionTitle(res: Int) = Text(androidx.compose.ui.res.stringResource(res), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageSection() {
    val current = remember { androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags() }
    var sel by remember { mutableStateOf(current.substringBefore('-').substringBefore(',')) }
    SectionTitle(il.ac.tau.lecturesummarizer.R.string.set_language)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("" to il.ac.tau.lecturesummarizer.R.string.lang_system, "he" to il.ac.tau.lecturesummarizer.R.string.lang_he, "en" to il.ac.tau.lecturesummarizer.R.string.lang_en).forEach { (tag, label) ->
            FilterChip(selected = sel == tag, onClick = {
                sel = tag
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                    if (tag.isEmpty()) androidx.core.os.LocaleListCompat.getEmptyLocaleList() else androidx.core.os.LocaleListCompat.forLanguageTags(tag))
            }, label = { Text(androidx.compose.ui.res.stringResource(label)) })
        }
    }
}

@Composable
private fun ConnectionsSection(onMoodle: () -> Unit, onPanopto: () -> Unit) {
    val ctx = LocalContext.current
    var cal by remember { mutableStateOf(Connections.calendar(ctx)) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cal = it }
    var tick by remember { mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { cal = Connections.calendar(ctx); tick++ }
    val moodle = remember(tick) { Connections.moodle(ctx) }
    val panopto = remember(tick) { Connections.panopto() }
    fun disconnect(host: String) {
        val cm = android.webkit.CookieManager.getInstance()
        cm.getCookie(host)?.split(';')?.forEach { cookie ->
            cm.setCookie(host, cookie.substringBefore('=').trim() + "=; Max-Age=0; Path=/")
        }
        cm.flush(); tick++
    }
    HorizontalDivider()
    SectionTitle(il.ac.tau.lecturesummarizer.R.string.set_connections)
    ConnectionRow(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.conn_calendar), cal, onDisconnect = { safeStart(ctx, Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))) }) { perm.launch(Manifest.permission.READ_CALENDAR) }
    ConnectionRow("Moodle", moodle, onDisconnect = { disconnect(il.ac.tau.lecturesummarizer.moodle.MoodleStore(ctx).baseUrl) }, onConnect = onMoodle)
    ConnectionRow("Panopto", panopto, onDisconnect = { disconnect(il.ac.tau.lecturesummarizer.moodle.Panopto.HOST) }, onConnect = onPanopto)
}

@Composable
private fun NotificationsSection() {
    val ctx = LocalContext.current
    HorizontalDivider()
    SectionTitle(il.ac.tau.lecturesummarizer.R.string.set_notifications)
    Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.set_notifications_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick = {
        safeStart(ctx, Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName))
    }) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.set_notifications_open)) }
}

@Composable
private fun SummaryDensitySection(app: LectureApp) {
    var sel by remember { mutableStateOf(app.settings.summaryDensities) }
    HorizontalDivider()
    SectionTitle(il.ac.tau.lecturesummarizer.R.string.set_levels)
    il.ac.tau.lecturesummarizer.data.SummaryDensity.entries.forEach { d ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(d.api in sel, { on -> val v = if (on) sel + d.api else sel - d.api; if (v.isNotEmpty()) { sel = v; app.settings.summaryDensities = v } })
            Text(androidx.compose.ui.res.stringResource(when (d.api) { "compact" -> il.ac.tau.lecturesummarizer.R.string.level_compact; "detailed" -> il.ac.tau.lecturesummarizer.R.string.level_detailed; else -> il.ac.tau.lecturesummarizer.R.string.level_standard }))
        }
    }
    Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.set_levels_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CalendarSourceSection(app: LectureApp) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(CalendarResolver.hasPermission(ctx)) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var sel by remember { mutableStateOf(app.settings.calendarIds) }
    fun set(v: Set<Long>) { sel = v; app.settings.calendarIds = v }
    var expanded by remember { mutableStateOf(false) }
    HorizontalDivider()
    Row(Modifier.fillMaxWidth().clickable { expanded=!expanded }.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
        Text(androidx.compose.ui.res.stringResource(R.string.calendar_source),style=MaterialTheme.typography.titleSmall,modifier=Modifier.weight(1f))
        Icon(if(expanded)Icons.Default.ExpandLess else Icons.Default.ExpandMore,null)
    }
    androidx.compose.animation.AnimatedVisibility(expanded){Column{
    if(!granted){OutlinedButton(onClick={perm.launch(Manifest.permission.READ_CALENDAR)}){Text(androidx.compose.ui.res.stringResource(R.string.calendar_allow))}}
    else{
        val cals=remember(granted){CalendarResolver.listCalendars(ctx)}
        Row(verticalAlignment=Alignment.CenterVertically){RadioButton(sel.isEmpty(),{set(emptySet())});Text(androidx.compose.ui.res.stringResource(R.string.calendar_auto))}
        cals.forEach{c->key(c.id){Row(verticalAlignment=Alignment.CenterVertically){
            Checkbox(c.id in sel,{on->set(if(on)sel+c.id else sel-c.id)})
            Column{Text(c.name);Text(c.account,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }}}
        if(cals.isEmpty())Text(androidx.compose.ui.res.stringResource(R.string.calendar_none),style=MaterialTheme.typography.bodySmall)
    }
    }}
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
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onOk(text.trim()) }) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_cancel)) } },
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
        Toast.makeText(ctx, if (ok) ctx.getString(il.ac.tau.lecturesummarizer.R.string.file_saved) else ctx.getString(il.ac.tau.lecturesummarizer.R.string.file_save_failed), Toast.LENGTH_SHORT).show()
    }
    return { uri, name, mime -> src = uri; launcher.launch(name to mime) }
}

/** Temporary title of a lecture created by the course-level Panopto button until a recording is picked. */
const val PANOPTO_PLACEHOLDER = "Panopto recording"
