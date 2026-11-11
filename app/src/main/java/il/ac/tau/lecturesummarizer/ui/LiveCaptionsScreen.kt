package il.ac.tau.lecturesummarizer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.captions.CaptionAsrLanguage
import il.ac.tau.lecturesummarizer.captions.CaptionRunState
import il.ac.tau.lecturesummarizer.captions.CaptionSession
import il.ac.tau.lecturesummarizer.captions.CaptionUiLine
import kotlinx.coroutines.delay

@Composable
fun LiveCaptionsScreen(app: LectureApp, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val session = remember { CaptionSession(ctx) }
    val ui by session.state.collectAsStateWithLifecycle()
    val localeRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var showEnd by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        session.prepareGates()
        onDispose { session.release() }
    }

    var autoStartedAfterDownload by remember { mutableStateOf(false) }
    var wasWaitingForSpeech by remember { mutableStateOf(false) }

    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) session.start(localeRtl = localeRtl) else denied = true
    }
    fun tryStart() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            perm.launch(Manifest.permission.RECORD_AUDIO)
        } else session.start(localeRtl = localeRtl)
    }

    // Keep download % / ready state fresh; auto-start once speech model lands.
    LaunchedEffect(session) {
        while (true) {
            val s = session.prepareGates()
            if (s.needsSpeechDownload) wasWaitingForSpeech = true
            if (
                wasWaitingForSpeech &&
                !autoStartedAfterDownload &&
                s.canStart &&
                !s.needsSpeechDownload &&
                s.run == CaptionRunState.Off
            ) {
                autoStartedAfterDownload = true
                tryStart()
            }
            delay(if (s.needsSpeechDownload || s.cleanupDownloading) 400 else 1500)
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri != null) {
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(session.exportText().toByteArray(Charsets.UTF_8))
            }
        }
        session.dump()
        onBack()
    }

    if (ui.needsAgreement) {
        CaptionsAgreementDialog(
            onAgree = { session.agreeAndDownload() },
            onDismiss = onBack,
        )
    }

    if (showEnd) {
        AlertDialog(
            onDismissRequest = { showEnd = false },
            title = { Text(stringResource(R.string.captions_end_title)) },
            text = { Text(stringResource(R.string.captions_end_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showEnd = false
                    saveLauncher.launch("live-captions.txt")
                }) { Text(stringResource(R.string.captions_save_txt)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showEnd = false
                    session.dump()
                    onBack()
                }) { Text(stringResource(R.string.captions_dump)) }
            },
        )
    }

    AppScaffold(stringResource(R.string.live_captions), onBack = {
        if ((ui.lines.isNotEmpty() || ui.partial.isNotBlank()) && ui.run != CaptionRunState.Off) showEnd = true else {
            session.dump(); onBack()
        }
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!ui.needsAgreement && !ui.needsSpeechDownload) {
                CaptionLanguageToggle(
                    language = ui.asrLanguage,
                    englishAvailable = ui.englishAvailable,
                    onSelect = { session.setAsrLanguage(it) },
                )
            }
            if (ui.needsSpeechDownload) {
                Text(ui.status, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { ui.downloadProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.captions_download_progress, ui.downloadPercent),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (ui.cleanupDownloading && ui.run == CaptionRunState.Off) {
                Text(
                    stringResource(R.string.captions_ready_tap_start),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.captions_optional_download_pct, ui.downloadPercent),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { ui.downloadProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (ui.status.isNotBlank()) {
                Text(ui.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (denied) Text(stringResource(R.string.copy_microphone_permission_is_required), color = MaterialTheme.colorScheme.error)
            if (ui.run == CaptionRunState.Running && ui.partial.isBlank() && ui.lines.isEmpty()) {
                Text(
                    stringResource(R.string.captions_listening),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (ui.run == CaptionRunState.Off && ui.canStart && ui.lines.isEmpty()) {
                Text(
                    stringResource(R.string.captions_ready_hint),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                CaptionLinesList(
                    lines = ui.lines,
                    partial = ui.partial,
                    partialDirection = ui.partialDirection,
                    listening = ui.run == CaptionRunState.Running,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when (ui.run) {
                    CaptionRunState.Off, CaptionRunState.Stopped -> {
                        Button(
                            enabled = ui.canStart,
                            onClick = { tryStart() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                if (ui.needsSpeechDownload) stringResource(R.string.captions_waiting_download)
                                else stringResource(R.string.captions_start),
                            )
                        }
                    }
                    CaptionRunState.Starting -> Text(stringResource(R.string.captions_starting), Modifier.weight(1f))
                    CaptionRunState.Running -> {
                        OutlinedButton(onClick = { session.pauseCaptions() }, Modifier.weight(1f)) {
                            Text(stringResource(R.string.captions_pause))
                        }
                        Button(onClick = { showEnd = true }, Modifier.weight(1f)) {
                            Text(stringResource(R.string.captions_stop))
                        }
                    }
                    CaptionRunState.Paused -> {
                        Button(onClick = { session.continueCaptions() }, Modifier.weight(1f)) {
                            Text(stringResource(R.string.captions_continue))
                        }
                        OutlinedButton(onClick = { showEnd = true }, Modifier.weight(1f)) {
                            Text(stringResource(R.string.captions_stop))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CaptionsAgreementDialog(onAgree: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.captions_agree_title)) },
        text = { Text(stringResource(R.string.captions_agree_body)) },
        confirmButton = {
            TextButton(onClick = onAgree) { Text(stringResource(R.string.captions_agree)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.captions_not_now)) }
        },
    )
}

@Composable
fun CaptionLinesList(
    lines: List<CaptionUiLine>,
    modifier: Modifier = Modifier,
    partial: String = "",
    partialDirection: LayoutDirection = LayoutDirection.Rtl,
    listening: Boolean = false,
) {
    val listState = rememberLazyListState()
    val count = lines.size + if (partial.isNotBlank()) 1 else 0
    LaunchedEffect(count, partial) {
        if (count > 0) listState.animateScrollToItem(count - 1)
    }
    LazyColumn(modifier, state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(lines, key = { it.id }) { line ->
            CaptionDirectedText(
                text = line.text,
                direction = line.direction,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (partial.isNotBlank()) {
            item(key = "partial") {
                // Ellipsis at the string end → visually on the leading edge in RTL (live end).
                val shown = if (listening) "$partial …" else partial
                CaptionDirectedText(
                    text = shown,
                    direction = partialDirection,
                    style = MaterialTheme.typography.titleMedium.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun CaptionDirectedText(
    text: String,
    direction: LayoutDirection,
    style: androidx.compose.ui.text.TextStyle,
    color: androidx.compose.ui.graphics.Color,
) {
    val rtl = direction == LayoutDirection.Rtl
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Start,
            style = style.copy(
                textDirection = if (rtl) {
                    androidx.compose.ui.text.style.TextDirection.Rtl
                } else {
                    androidx.compose.ui.text.style.TextDirection.Ltr
                },
            ),
            color = color,
        )
    }
}

@Composable
fun CaptionLanguageToggle(
    language: CaptionAsrLanguage,
    englishAvailable: Boolean,
    onSelect: (CaptionAsrLanguage) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Column(
        modifier.fillMaxWidth().then(if (compact) Modifier.wrapContentHeight() else Modifier),
        verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp),
    ) {
        if (!compact) {
            Text(
                stringResource(R.string.captions_lang_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (compact) {
                Text(
                    stringResource(R.string.captions_lang_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
            FilterChip(
                selected = language == CaptionAsrLanguage.Hebrew,
                onClick = { onSelect(CaptionAsrLanguage.Hebrew) },
                label = { Text(stringResource(R.string.captions_lang_he)) },
            )
            FilterChip(
                selected = language == CaptionAsrLanguage.English,
                enabled = englishAvailable,
                onClick = { onSelect(CaptionAsrLanguage.English) },
                label = { Text(stringResource(R.string.captions_lang_en)) },
            )
        }
        if (!englishAvailable && !compact) {
            Text(
                stringResource(R.string.captions_lang_en_waiting),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Materials / lecture card: open or delete on-device captions when a JSONL file exists. */
@Composable
fun LectureCaptionsSection(
    lectureId: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val store = remember { il.ac.tau.lecturesummarizer.captions.LocalCaptionStore(ctx) }
    var present by remember(lectureId) { mutableStateOf(store.exists(lectureId)) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Re-check when returning to this screen after recording.
    LaunchedEffect(lectureId) { present = store.exists(lectureId) }
    if (!present) return

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.captions_delete_title)) },
            text = { Text(stringResource(R.string.captions_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(lectureId)
                    present = false
                    confirmDelete = false
                }) { Text(stringResource(R.string.captions_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.captions_not_now))
                }
            },
        )
    }

    Column(modifier.fillMaxWidth()) {
        SectionCard(
            title = stringResource(R.string.captions_lecture_section),
            icon = Icons.Default.Subtitles,
        ) {
            OutlineAction(
                text = stringResource(R.string.captions_open),
                icon = Icons.Default.Subtitles,
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpen,
            )
            OutlineAction(
                text = stringResource(R.string.captions_delete),
                icon = Icons.Default.Delete,
                modifier = Modifier.fillMaxWidth(),
                onClick = { confirmDelete = true },
            )
        }
    }
}

/** Read-only viewer for a lecture's saved on-device captions. */
@Composable
fun LectureCaptionsViewerScreen(lectureId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { il.ac.tau.lecturesummarizer.captions.LocalCaptionStore(ctx) }
    var lines by remember(lectureId) {
        mutableStateOf(
            store.read(lectureId).mapIndexed { i, l ->
                CaptionUiLine(
                    id = i.toLong() + 1,
                    raw = l.raw,
                    text = l.text,
                    cleaned = l.cleaned,
                    t0 = l.t0,
                    t1 = l.t1,
                    direction = il.ac.tau.lecturesummarizer.captions.LineDirection.of(
                        l.text, null, LayoutDirection.Rtl,
                    ),
                )
            },
        )
    }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.captions_delete_title)) },
            text = { Text(stringResource(R.string.captions_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(lectureId)
                    confirmDelete = false
                    onBack()
                }) { Text(stringResource(R.string.captions_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.captions_not_now))
                }
            },
        )
    }

    AppScaffold(
        stringResource(R.string.live_captions),
        onBack = onBack,
        actions = {
            TextButton(onClick = { confirmDelete = true }) {
                Text(stringResource(R.string.captions_delete))
            }
        },
    ) { pad ->
        if (lines.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.captions_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            CaptionLinesList(
                lines = lines,
                modifier = Modifier.padding(pad).fillMaxSize().padding(16.dp),
            )
        }
    }
}

/**
 * Full captions experience during an ongoing lecture recording.
 * Compact lecture timer / pause / stop on top; captions controls at the bottom.
 */
@Composable
fun LectureCaptionsOverlay(
    session: CaptionSession,
    elapsedLabel: String,
    recordingPaused: Boolean,
    onLecturePause: () -> Unit,
    onLectureResume: () -> Unit,
    onLectureStop: () -> Unit,
    onCaptionsStart: () -> Unit,
    onCaptionsDismiss: () -> Unit,
    onBack: () -> Unit,
) {
    val ui by session.state.collectAsStateWithLifecycle()
    androidx.activity.compose.BackHandler(onBack = onBack)

    AppScaffold(stringResource(R.string.live_captions), onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Thin lecture strip — wrapContentHeight so Surface/Box can't eat the captions area.
            Row(
                Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    elapsedLabel,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = if (recordingPaused) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    maxLines = 1,
                )
                TextButton(
                    onClick = if (recordingPaused) onLectureResume else onLecturePause,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        stringResource(
                            if (recordingPaused) R.string.captions_lecture_resume_short
                            else R.string.captions_lecture_pause_short,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                TextButton(
                    onClick = onLectureStop,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        stringResource(R.string.captions_lecture_stop_short),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            HorizontalDivider()

            if (!ui.needsAgreement && !ui.needsSpeechDownload) {
                CaptionLanguageToggle(
                    language = ui.asrLanguage,
                    englishAvailable = ui.englishAvailable,
                    onSelect = { session.setAsrLanguage(it) },
                    compact = true,
                )
            }

            when {
                ui.needsSpeechDownload -> {
                    Text(ui.status, style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(
                        progress = { ui.downloadProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.captions_download_progress, ui.downloadPercent),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                }
                ui.cleanupDownloading && ui.run == CaptionRunState.Off -> {
                    Text(
                        stringResource(R.string.captions_ready_tap_start),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    LinearProgressIndicator(
                        progress = { ui.downloadProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.weight(1f))
                }
                else -> {
                    if (ui.status.isNotBlank() && ui.lines.isEmpty() && ui.partial.isBlank()) {
                        Text(
                            ui.status,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (ui.run == CaptionRunState.Running && ui.partial.isBlank() && ui.lines.isEmpty()) {
                        Text(
                            stringResource(R.string.captions_listening),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    CaptionLinesList(
                        lines = ui.lines,
                        partial = ui.partial,
                        partialDirection = ui.partialDirection,
                        listening = ui.run == CaptionRunState.Running,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (ui.run) {
                    CaptionRunState.Off, CaptionRunState.Stopped -> {
                        if (ui.canStart && !recordingPaused) {
                            Button(onClick = onCaptionsStart, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.captions_start))
                            }
                            OutlinedButton(onClick = onCaptionsDismiss, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.captions_close))
                            }
                        } else {
                            OutlinedButton(onClick = onCaptionsDismiss, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.captions_close))
                            }
                        }
                    }
                    CaptionRunState.Starting -> {
                        Text(stringResource(R.string.captions_starting), Modifier.weight(1f))
                    }
                    CaptionRunState.Running -> {
                        OutlinedButton(
                            onClick = { session.pauseCaptions() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.captions_pause))
                        }
                        Button(onClick = onCaptionsDismiss, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.captions_stop))
                        }
                    }
                    CaptionRunState.Paused -> {
                        Button(
                            enabled = !recordingPaused,
                            onClick = { session.continueCaptions() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.captions_continue))
                        }
                        OutlinedButton(onClick = onCaptionsDismiss, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.captions_stop))
                        }
                    }
                }
            }
        }
    }
}
