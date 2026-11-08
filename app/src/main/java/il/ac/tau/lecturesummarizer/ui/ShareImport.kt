package il.ac.tau.lecturesummarizer.ui

import android.net.Uri
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
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.data.allSlides
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun isDoc(ctx: android.content.Context, u: Uri): Boolean {
    val mime = runCatching { ctx.contentResolver.getType(u) }.getOrNull() ?: ""
    val n = displayName(ctx, u.toString()).lowercase()
    return mime.contains("pdf") || mime.contains("presentation") || n.endsWith(".pdf") || n.endsWith(".pptx")
}

/** Share-sheet wizard: pick/create course → pick/create lecture → import shared files → open the lecture. */
@Composable
fun ShareImportScreen(app: LectureApp, uris: List<Uri>, onCancel: () -> Unit, onDone: (String) -> Unit) {
    val ctx = LocalContext.current
    val courses by app.repo.courses.collectAsStateWithLifecycle()
    val lectures by app.repo.lectures.collectAsStateWithLifecycle()
    var courseId by remember { mutableStateOf<String?>(null) }
    var newName by remember(courseId) { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun importInto(lectureId: String) {
        busy = ctx.getString(il.ac.tau.lecturesummarizer.R.string.shared_importing, uris.size)
        scope.launch {
            val errs = withContext(Dispatchers.IO) {
                val docs = mutableListOf<String>(); val errs = mutableListOf<String>()
                uris.forEach { u ->
                    runCatching {
                        if (isDoc(ctx, u)) {
                            val dir = File(ctx.filesDir, "shared/$lectureId").apply { mkdirs() }
                            val f = File(dir, displayName(ctx, u.toString()).replace('/', '_'))
                            ctx.contentResolver.openInputStream(u)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
                            docs += FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f).toString()
                        } else importAudio(app, lectureId, u, ctx)
                    }.onFailure { errs += "${displayName(ctx, u.toString())}: ${it.message}" }
                }
                if (docs.isNotEmpty()) il.ac.tau.lecturesummarizer.slides.SlideChoices(app).manual(lectureId)
                if (docs.isNotEmpty()) app.repo.updateLecture(lectureId) { x ->
                    val new = docs.filterNot { it in x.allSlides }
                    if (x.slidesUri == null && new.isNotEmpty()) x.copy(slidesUri = new.first(), extraSlides = x.extraSlides + new.drop(1))
                    else x.copy(extraSlides = x.extraSlides + new)
                }
                errs
            }
            if (errs.isNotEmpty()) app.repo.updateLecture(lectureId) { it.copy(error = ctx.getString(il.ac.tau.lecturesummarizer.R.string.import_failed)) }
            busy = null; onDone(lectureId)
        }
    }

    val cid = courseId
    AppScaffold(if (cid == null) androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.share_course) else androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.share_lecture), onBack = { if (cid != null) courseId = null else onCancel() }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.shared_file_list,uris.size,uris.take(3).joinToString { bidi(displayName(ctx,it.toString())) } + if(uris.size>3)"…"else ""),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            busy?.let { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text(it) }; return@Column }
            Text(if (cid == null) androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.share_step1) else androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.share_step2, bidi(app.repo.course(cid)?.name.orEmpty())), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newName, { newName = it }, label = { Text(if (cid == null) androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.new_course_name) else androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.new_lecture_title)) }, singleLine = true, modifier = Modifier.weight(1f))
                Button(enabled = newName.isNotBlank(), onClick = {
                    if (cid == null) courseId = app.repo.addCourse(newName.trim()).id
                    else importInto(app.repo.addLecture(cid, newName.trim()).id)
                }) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.create)) }
            }
            HorizontalDivider()
            val rows = if (cid == null) courses.map { it.id to ((it.emoji?.let { e -> "$e " } ?: "") + it.name) }
                else lectures.filter { it.courseId == cid }.sortedByDescending { it.recordedAtMs }.map { it.id to it.title }
            if (rows.isEmpty()) Text(if (cid == null) androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.share_no_courses) else androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.share_no_lectures), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(rows, key = { it.first }) { (id, label) ->
                    ListItem(headlineContent = { Text(label) },
                        leadingContent = { Icon(if (cid == null) Icons.Default.School else Icons.Default.Mic, null) },
                        modifier = Modifier.fillMaxWidth().clickable { if (cid == null) courseId = id else importInto(id) })
                }
            }
        }
    }
}
