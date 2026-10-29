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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
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

private class MultiPlayer(private val tl: Timeline) {
    private var mp: MediaPlayer? = null
    private var next: MediaPlayer? = null
    var index = 0; private set
    /** User intent (play/pause). Actual MediaPlayer state follows it. */
    var wantPlay = false; private set
    var error: String? = null; private set
    /** While a seek is in flight report the target so the highlight doesn't flicker back. */
    private var pendingSeek: Long? = null

    private fun create(i: Int): MediaPlayer? = runCatching {
        MediaPlayer().apply { setDataSource(tl.files[i].absolutePath); prepare() }
    }.onFailure { error = "Can't play segment ${i + 1}: ${it.message}" }.getOrNull()

    private fun open(i: Int, posInFileMs: Long, play: Boolean) {
        next?.release(); next = null
        mp?.release(); mp = null
        index = i
        val p = create(i) ?: return
        mp = p
        attach(p)
        if (posInFileMs > 0) { pendingSeek = tl.offsetsMs[i] + posInFileMs; p.seekTo(posInFileMs, MediaPlayer.SEEK_CLOSEST) }
        if (play) p.start()
        prepareNext()
    }

    private fun attach(p: MediaPlayer) {
        p.setOnSeekCompleteListener { pendingSeek = null }
        p.setOnCompletionListener {
            val n = next
            if (index + 1 < tl.files.size) {
                // Gapless handoff: setNextMediaPlayer already started `next`; just swap references.
                index += 1
                if (n != null) { next = null; mp = n; p.release(); attach(n); if (!n.isPlaying && wantPlay) n.start(); prepareNext() }
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

    fun play() { wantPlay = true; if (mp == null) open(index, 0, true) else if (mp?.isPlaying != true) mp?.start() }
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
    val player = remember(tl) { if (tl.files.isEmpty()) null else MultiPlayer(tl) }
    DisposableEffect(player) { onDispose { player?.release() } }

    var transcript by remember { mutableStateOf<Transcript?>(null) }
    var tMsg by remember { mutableStateOf("Loading transcript…") }
    LaunchedEffect(l.remoteId, l.stage) {
        val rid = l.remoteId ?: run { tMsg = "No transcript yet — this lecture hasn't been uploaded. You can still listen to the local audio."; return@LaunchedEffect }
        runCatching { ApiProvider.get(app.settings).transcript(rid) }
            .onSuccess { t -> transcript = t?.copy(lines = t.lines.sortedBy { it.t0 })
                tMsg = if (t == null || t.lines.isEmpty()) "Transcript isn't ready yet. Audio still plays below." else "" }
            .onFailure { e -> tMsg = "Couldn't load transcript (${e.message}). Audio still plays." }
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

    val lines = transcript?.lines.orEmpty()
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

    AppScaffold("Listen", onBack = onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ElevatedCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(), shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(l.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (player == null) Text("No local recording on this phone — transcript only.", style = MaterialTheme.typography.bodySmall)
                    else {
                        Slider(value = dragging ?: (pos.toFloat() / total).coerceIn(0f, 1f),
                            onValueChange = { dragging = it },   // highlight follows the thumb while scrubbing
                            onValueChangeFinished = { dragging?.let { seek((it * total).toLong()) }; dragging = null })
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(mmss(shownPos), style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.weight(1f))
                            Text("−" + mmss(total - shownPos), style = MaterialTheme.typography.labelMedium)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalButton(onClick = { seek(pos - 15_000) }) { Text("↺ 15") }
                            Spacer(Modifier.width(16.dp))
                            Button(onClick = { player.toggle(); playing = player.isPlaying || player.wantPlay },
                                modifier = Modifier.size(64.dp), shape = RoundedCornerShape(50), contentPadding = PaddingValues(0.dp)) {
                                Text(if (playing) "❚❚" else "▶", fontSize = 22.sp)
                            }
                            Spacer(Modifier.width(16.dp))
                            FilledTonalButton(onClick = { seek(pos + 15_000) }) { Text("15 ↻") }
                        }
                        if (tl.files.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val curSeg = tl.offsetsMs.indexOfLast { it <= shownPos }.coerceAtLeast(0)
                            tl.offsetsMs.forEachIndexed { i, off ->
                                FilterChip(selected = i == curSeg, onClick = { seek(off) },
                                    label = { Text("Part ${i + 1} · ${mmss(off)}") })
                            }
                        }
                        player.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    transcript?.let { if (!it.complete) Text("Partial transcript (ASR still running)", style = MaterialTheme.typography.bodySmall) }
                    if (tMsg.isNotEmpty()) Text(tMsg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (lines.isNotEmpty()) FilterChip(selected = follow, onClick = { follow = !follow }, label = { Text("Follow") })
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 48.dp)) {
                itemsIndexed(lines, key = { i, ln -> "$i@${ln.t0}" }) { i, line ->
                    val active = i == current
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                        .clickable(enabled = player != null) {
                            seek((line.t0 * 1000).toLong()); follow = true
                            player?.play(); playing = true
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(mmss((line.t0 * 1000).toLong()), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(56.dp).padding(top = 3.dp))
                        Text(line.text, style = MaterialTheme.typography.bodyLarge.copy(fontSize = if (active) 18.sp else 16.sp),
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            color = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = if (current >= 0 && i < current) 0.55f else 0.9f))
                    }
                }
            }
        }
    }
}
