package il.ac.tau.lecturesummarizer.ui

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.data.ApiProvider
import il.ac.tau.lecturesummarizer.data.Transcript
import il.ac.tau.lecturesummarizer.data.TranscriptLine
import kotlinx.coroutines.delay
import java.io.File

/**
 * Plays the lecture's local segment files as one continuous timeline. Segment offsets = cumulative recorded
 * durations (the same offsets the server uses for transcript times), so missing files leave a gap rather than shifting time.
 */
private class Timeline(val files: List<File>, val offsetsMs: List<Long>, val lengthsMs: List<Long>, val totalMs: Long)

private class MultiPlayer(private val tl: Timeline,private val ctx:android.content.Context) {
    private var mp: MediaPlayer? = null
    private var next: MediaPlayer? = null
    var index = 0; private set
    /** User intent (play/pause). Actual MediaPlayer state follows it. */
    var wantPlay = false; private set
    var error: String? = null; private set
    var speed = 1f; private set
    private fun applySpeed(p: MediaPlayer?) {
        // setPlaybackParams on a paused player starts it, so only touch a playing one (start() keeps the params).
        if (p == null) return
        runCatching { if (p.isPlaying) p.playbackParams = p.playbackParams.setSpeed(speed) }
    }
    fun setSpeed(s: Float) { speed = s; applySpeed(mp) }
    /** While a seek is in flight report the target so the highlight doesn't flicker back. */
    private var pendingSeek: Long? = null

    private fun create(i: Int): MediaPlayer? = runCatching {
        MediaPlayer().apply { setDataSource(tl.files[i].absolutePath); prepare() }
    }.onFailure { error = ctx.getString(il.ac.tau.lecturesummarizer.R.string.audio_part_failed, i+1) }.getOrNull()

    private fun open(i: Int, posInFileMs: Long, play: Boolean) {
        next?.release(); next = null
        mp?.release(); mp = null
        index = i
        val p = create(i) ?: return
        mp = p
        attach(p)
        if (posInFileMs > 0) { pendingSeek = tl.offsetsMs[i] + posInFileMs; p.seekTo(posInFileMs, MediaPlayer.SEEK_CLOSEST) }
        if (play) { p.start(); applySpeed(p) }
        prepareNext()
    }

    private fun attach(p: MediaPlayer) {
        p.setOnSeekCompleteListener { pendingSeek = null }
        p.setOnCompletionListener {
            val n = next
            if (index + 1 < tl.files.size) {
                // Gapless handoff: setNextMediaPlayer already started `next`; just swap references.
                index += 1
                if (n != null) { next = null; mp = n; p.release(); attach(n); if (!n.isPlaying && wantPlay) n.start(); applySpeed(n); prepareNext() }
                else { mp = null; p.release(); open(index, 0, wantPlay) }
            } else { wantPlay = false; pendingSeek = null }
        }
    }

    private fun prepareNext() {
        val cur = mp ?: return
        if (index + 1 >= tl.files.size) return
        val n = create(index + 1) ?: return
        next = n
        runCatching { cur.setNextMediaPlayer(n) }
    }

    private var lastPos = 0L
    /** Global timeline position = offset of the current file + MediaPlayer position in that file (ms). */
    fun positionMs(): Long {
        pendingSeek?.let { lastPos = it; return it }
        val p = mp ?: return lastPos
        val inFile = runCatching { p.currentPosition.toLong() }.getOrNull() ?: return lastPos
        lastPos = tl.offsetsMs.getOrElse(index) { 0 } + inFile.coerceIn(0, tl.lengthsMs.getOrElse(index) { inFile })
        return lastPos
    }

    val isPlaying get() = runCatching { mp?.isPlaying == true }.getOrDefault(false) || (wantPlay && pendingSeek != null)

    fun seekTo(globalMs: Long) {
        val g = globalMs.coerceIn(0, maxOf(0, tl.totalMs - 1))
        var i = tl.offsetsMs.indexOfLast { it <= g }.coerceAtLeast(0)
        var within = g - tl.offsetsMs[i]
        if (within >= tl.lengthsMs[i] && i + 1 < tl.files.size) { i += 1; within = 0 }  // fell into a gap
        within = within.coerceAtMost(maxOf(0, tl.lengthsMs[i] - 50))
        lastPos = tl.offsetsMs[i] + within
        if (mp == null || i != index) open(i, within, wantPlay)
        else { pendingSeek = tl.offsetsMs[i] + within; mp!!.seekTo(within, MediaPlayer.SEEK_CLOSEST) }
    }

    fun play() { wantPlay = true; if (mp == null) open(index, 0, true) else if (mp?.isPlaying != true) { mp?.start(); applySpeed(mp) } }
    fun pause() { wantPlay = false; runCatching { mp?.pause() } }
    fun toggle() = if (wantPlay) pause() else play()

    fun release() { next?.release(); next = null; mp?.release(); mp = null; wantPlay = false }
}

private fun mmss(ms: Long): String { val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

/** Index of the line whose [t0,next.t0) contains posMs (binary search; lines are time-sorted). */
private fun lineAt(lines: List<TranscriptLine>, posMs: Long): Int {
    var lo = 0; var hi = lines.size - 1; var ans = -1
    val t = posMs / 1000.0
    while (lo <= hi) { val mid = (lo + hi) / 2; if (lines[mid].t0 <= t + 0.05) { ans = mid; lo = mid + 1 } else hi = mid - 1 }
    return ans
}

@Composable
fun ListenScreen(app: LectureApp, lectureId: String, onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val all by app.repo.lectures.collectAsStateWithLifecycle()
    val l = all.firstOrNull { it.id == lectureId } ?: return
    val segKey = l.segments.map { it.seq to it.durationMs }
    val tl = remember(segKey) {
        val segs = l.segments.sortedBy { it.seq }
        var off = 0L
        val files = mutableListOf<File>(); val offs = mutableListOf<Long>(); val lens = mutableListOf<Long>()
        for (s in segs) { val f = File(s.path); if (f.exists()) { files += f; offs += off; lens += s.durationMs }; off += s.durationMs }
        Timeline(files, offs, lens, off)
    }
    val player = remember(tl) { if (tl.files.isEmpty()) null else MultiPlayer(tl,ctx) }
    DisposableEffect(player) { onDispose { player?.release() } }
    val prefs = remember { ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    var speed by remember { mutableFloatStateOf(prefs.getFloat("listenSpeed", 1f)) }
    LaunchedEffect(player) { player?.setSpeed(speed) }

    var transcript by remember { mutableStateOf<Transcript?>(null) }
    var tMsg by remember { mutableStateOf(ctx.getString(il.ac.tau.lecturesummarizer.R.string.transcript_loading)) }
    LaunchedEffect(l.remoteId, l.stage) {
        val rid = l.remoteId ?: run { tMsg = ctx.getString(il.ac.tau.lecturesummarizer.R.string.transcript_unuploaded); return@LaunchedEffect }
        runCatching { ApiProvider.get(app.settings).transcript(rid) }
            .onSuccess { t -> t?.let{il.ac.tau.lecturesummarizer.data.StudyIndex(app).put(lectureId,"transcript",it.lines.joinToString(" "){line->line.text})};transcript = t?.copy(lines = t.lines.sortedBy { it.t0 })
                tMsg = if (t == null || t.lines.isEmpty()) ctx.getString(il.ac.tau.lecturesummarizer.R.string.transcript_not_ready) else "" }
            .onFailure { e -> tMsg = ctx.getString(il.ac.tau.lecturesummarizer.R.string.transcript_load_failed) }
    }

    var pos by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(player) {
        while (player != null) { if (dragging == null) pos = player.positionMs(); playing = player.isPlaying; delay(100) }
    }
    val total = maxOf(1L, tl.totalMs)
    val shownPos = dragging?.let { (it * total).toLong() } ?: pos

    // Re-time ASR sentences against the local audio (server timestamps drift inside each ASR clip; see SyncAlign).
    var aligned by remember { mutableStateOf<List<TranscriptLine>?>(null) }
    LaunchedEffect(transcript, tl) {
        val raw = transcript?.lines ?: return@LaunchedEffect
        if (tl.files.isEmpty() || raw.size < 2) return@LaunchedEffect
        aligned = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                val voiced = SyncAlign.voicedFromDb(SyncAlign.envelope(tl.files, tl.offsetsMs, tl.totalMs))
                val segs = l.segments.sortedBy { it.seq }
                var off = 0.0; val offs = mutableListOf<Double>(); val lens = mutableListOf<Double>()
                for (sg in segs) { offs += off; lens += sg.durationMs / 1000.0; off += sg.durationMs / 1000.0 }
                SyncAlign.realign(raw, voiced, SyncAlign.anchors(offs, lens))
            }.getOrNull()
        }
    }
    val lines = aligned ?: transcript?.lines.orEmpty()
    val current = lineAt(lines, shownPos)
    val listState = rememberLazyListState()
    // Follow: keep the current line in the upper-middle of the viewport. Never reset to the top; only move when the
    // current line leaves the comfortable band. A user drag turns Follow off (tap a line or the chip to re-enable).
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is androidx.compose.foundation.interaction.DragInteraction.Start) follow = false }
    }
    LaunchedEffect(current, follow, lines.size) {
        if (!follow || current < 0 || current >= lines.size) return@LaunchedEffect
        val info = listState.layoutInfo
        val vis = info.visibleItemsInfo
        val item = vis.firstOrNull { it.index == current }
        val h = info.viewportEndOffset - info.viewportStartOffset
        val ok = item != null && item.offset >= h / 8 && item.offset + item.size <= h * 3 / 4
        if (!ok) {
            listState.scrollToItem(maxOf(0, current - 2))
        }
    }
    fun seek(ms: Long) { player?.seekTo(ms); pos = ms.coerceIn(0, total) }

    AppScaffold(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.listen_title), onBack = onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(l.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (player == null) Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.listen_no_audio), style = MaterialTheme.typography.bodySmall)
                    else {
                        Slider(value = dragging ?: (pos.toFloat() / total).coerceIn(0f, 1f),
                            onValueChange = { dragging = it },   // highlight follows the thumb while scrubbing
                            onValueChangeFinished = { dragging?.let { seek((it * total).toLong()) }; dragging = null })
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(ltr(mmss(shownPos)), style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.weight(1f))
                            Text(ltr("−" + mmss(total - shownPos)), style = MaterialTheme.typography.labelMedium)
                        }
                        var speedOpen by remember { mutableStateOf(false) }
                        val backCd = androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.player_back15)
                        val fwdCd = androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.player_forward15)
                        val playCd = androidx.compose.ui.res.stringResource(if (playing) il.ac.tau.lecturesummarizer.R.string.player_pause else il.ac.tau.lecturesummarizer.R.string.player_play)
                        // Icon controls + short labels: full "Back/Forward 15 seconds" strings overflowed and hid speed.
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalButton(
                                onClick = { seek(pos - 15_000) },
                                modifier = Modifier.weight(1f).semantics { contentDescription = backCd },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            ) {
                                Icon(Icons.Default.Replay, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(ltr("−15"), maxLines = 1, overflow = TextOverflow.Clip)
                            }
                            Button(
                                onClick = { player.toggle(); playing = player.isPlaying || player.wantPlay },
                                modifier = Modifier.size(56.dp).semantics { contentDescription = playCd },
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, playCd, Modifier.size(28.dp))
                            }
                            FilledTonalButton(
                                onClick = { seek(pos + 15_000) },
                                modifier = Modifier.weight(1f).semantics { contentDescription = fwdCd },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            ) {
                                Text(ltr("+15"), maxLines = 1, overflow = TextOverflow.Clip)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Default.Forward, null, Modifier.size(18.dp))
                            }
                            OutlinedButton(
                                onClick = { speedOpen = !speedOpen },
                                modifier = Modifier.widthIn(min = 56.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                Text(ltr("%sx".format(if (speed % 1f == 0f) speed.toInt().toString() else "%.2f".format(speed).trimEnd('0'))), maxLines = 1)
                            }
                        }
                        if (speedOpen) Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(ltr("0.5x"), style = MaterialTheme.typography.labelSmall)
                            // 0.5x..2x in 0.25 steps (5 intermediate stops)
                            Slider(value = speed, valueRange = 0.5f..2f, steps = 5, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                onValueChange = { speed = (Math.round(it * 4) / 4f); player.setSpeed(speed) },
                                onValueChangeFinished = { prefs.edit().putFloat("listenSpeed", speed).apply() })
                            Text(ltr("2x"), style = MaterialTheme.typography.labelSmall)
                        }
                        player.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f, fill = false).padding(end = 8.dp)) {
                    transcript?.let { if (!it.complete) Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.transcript_partial), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    if (tMsg.isNotEmpty()) Text(tMsg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (lines.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText(ctx.getString(il.ac.tau.lecturesummarizer.R.string.transcript_clipboard_label), transcriptText(l.title, lines)))
                            android.widget.Toast.makeText(ctx, ctx.getString(il.ac.tau.lecturesummarizer.R.string.transcript_copied, lines.size), android.widget.Toast.LENGTH_SHORT).show()
                        }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.transcript_copy), maxLines = 1) }
                        TextButton(onClick = { shareTranscriptTxt(ctx, l.title, lines) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.transcript_export), maxLines = 1) }
                        FilterChip(selected = follow, onClick = { follow = !follow }, label = { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.transcript_follow), maxLines = 1) })
                    }
                }
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 48.dp)) {
                itemsIndexed(lines, key = { i, ln -> "$i@${ln.t0}" }) { i, line ->
                    val active = i == current
                    val rtl = isMostlyHebrew(line.text)
                    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                        .clickable(enabled = player != null) {
                            seek((line.t0 * 1000).toLong()); follow = true
                            player?.play(); playing = true
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(ltr(mmss((line.t0 * 1000).toLong())), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(56.dp).padding(top = 3.dp))
                        Text(if (rtl) isolateLatinRuns(line.text) else line.text,
                            textAlign = TextAlign.Start,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = if (active) 18.sp else 16.sp,
                                textDirection = if (rtl) TextDirection.Rtl else TextDirection.Ltr),
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            color = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = if (current >= 0 && i < current) 0.55f else 0.9f))
                    }
                    }
                }
            }
        }
    }
}

/** True when Hebrew letters make up at least ~30% of the letters (Hebrew lectures with English terms). */
/** Plain UTF-8 transcript, all lines in order, "[mm:ss] text" per line. */
internal fun transcriptText(title: String, lines: List<TranscriptLine>): String = buildString {
    append(title).append("\n\n")
    lines.forEach { append('[').append(mmss((it.t0 * 1000).toLong())).append("] ").append(it.text.trim()).append('\n') }
}

/** Writes the transcript to files/shared/ and opens the share sheet (Save to Files/Drive, send, etc.). */
internal fun shareTranscriptTxt(ctx: android.content.Context, title: String, lines: List<TranscriptLine>) {
    runCatching {
        val dir = java.io.File(ctx.filesDir, "shared").apply { mkdirs() }
        val safe = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).ifBlank { "lecture" }
        val f = java.io.File(dir, "$safe - transcript.txt")
        f.writeText("\uFEFF" + transcriptText(title, lines), Charsets.UTF_8)
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_STREAM, uri).putExtra(android.content.Intent.EXTRA_SUBJECT, "$title — transcript")
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(android.content.Intent.createChooser(send, ctx.getString(il.ac.tau.lecturesummarizer.R.string.transcript_share)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { android.widget.Toast.makeText(ctx, ctx.getString(il.ac.tau.lecturesummarizer.R.string.transcript_export_failed), android.widget.Toast.LENGTH_LONG).show() }
}

internal fun isMostlyHebrew(s: String): Boolean {
    var heb = 0; var lat = 0
    for (c in s) { if (c in '\u0590'..'\u05FF') heb++ else if (c in 'A'..'Z' || c in 'a'..'z') lat++ }
    return heb > 0 && heb * 10 >= (heb + lat) * 3
}

private val latinRun = Regex("[A-Za-z0-9][A-Za-z0-9 .,'’_/:+#=()\\-]*[A-Za-z0-9)]|[A-Za-z]")

/** Wrap each Latin/English run in LRI…PDI (U+2066/U+2069) so it renders LTR without reordering the Hebrew around it. */
internal fun isolateLatinRuns(s: String): String =
    latinRun.replace(s) { m -> if (m.value.any { it.isLetter() }) "\u2066${m.value}\u2069" else m.value }
