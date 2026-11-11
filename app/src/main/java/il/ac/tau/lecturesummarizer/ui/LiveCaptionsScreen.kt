package il.ac.tau.lecturesummarizer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
            if (ui.status.isNotBlank()) {
                Text(ui.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (ui.needsSpeechDownload || ui.cleanupDownloading) {
                LinearProgressIndicator(
                    progress = { ui.downloadProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.captions_download_progress, ui.downloadPercent),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (denied) Text(stringResource(R.string.copy_microphone_permission_is_required), color = MaterialTheme.colorScheme.error)
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
            Text(
                line.text,
                modifier = Modifier.fillMaxWidth(),
                textAlign = if (line.direction == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (partial.isNotBlank()) {
            item(key = "partial") {
                Text(
                    if (listening) "… $partial" else partial,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = if (partialDirection == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left,
                    style = MaterialTheme.typography.titleMedium.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
fun CaptionControlRow(
    session: CaptionSession,
    modifier: Modifier = Modifier,
) {
    val ui by session.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (ui.status.isNotBlank()) {
            Text(ui.status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (ui.needsSpeechDownload || ui.cleanupDownloading) {
            LinearProgressIndicator(progress = { ui.downloadProgress }, modifier = Modifier.fillMaxWidth())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (ui.run) {
                CaptionRunState.Running -> {
                    Text(stringResource(R.string.captions_on), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { session.pauseCaptions() }) { Text(stringResource(R.string.captions_pause)) }
                    TextButton(onClick = { session.stopCaptionsForSession() }) { Text(stringResource(R.string.captions_stop)) }
                }
                CaptionRunState.Paused -> {
                    TextButton(onClick = { session.continueCaptions() }) { Text(stringResource(R.string.captions_continue)) }
                    TextButton(onClick = { session.stopCaptionsForSession() }) { Text(stringResource(R.string.captions_stop)) }
                }
                CaptionRunState.Stopped -> {
                    TextButton(onClick = { session.startAgain() }) { Text(stringResource(R.string.captions_start_again)) }
                }
                else -> Unit
            }
        }
        CaptionLinesList(
            lines = ui.lines.takeLast(6),
            partial = ui.partial,
            partialDirection = ui.partialDirection,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}
