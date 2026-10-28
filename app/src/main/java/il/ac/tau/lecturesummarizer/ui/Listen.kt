package il.ac.tau.lecturesummarizer.ui

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.data.ApiProvider
import il.ac.tau.lecturesummarizer.data.Transcript
import kotlinx.coroutines.delay
import java.io.File

/**
 * Plays the lecture's local segment files as one continuous timeline (segment offsets = cumulative durations,
 * the same offsets the server uses for transcript times), with a timed transcript from GET /v1/lectures/{id}/transcript.
 */
private class Timeline(val files: List<File>, val offsetsMs: List<Long>, val totalMs: Long)

private class MultiPlayer(private val tl: Timeline) {
    private var mp: MediaPlayer? = null
    var index = 0; private set
    var playing = false; private set

    private fun open(i: Int, posInFileMs: Int, start: Boolean) {
        mp?.release()
        index = i
        mp = MediaPlayer().apply {
            setDataSource(tl.files[i].absolutePath)
            prepare()
            seekTo(posInFileMs)
            setOnCompletionListener {
                if (index + 1 < tl.files.size) open(index + 1, 0, true) else { this@MultiPlayer.playing = false }
            }
            if (start) start()
        }
        playing = start
    }

    fun positionMs(): Long = tl.offsetsMs.getOrElse(index) { 0 } + (mp?.currentPosition ?: 0)

    fun seekTo(globalMs: Long) {
        val g = globalMs.coerceIn(0, maxOf(0, tl.totalMs - 1))
        val i = tl.offsetsMs.indexOfLast { it <= g }.coerceAtLeast(0)
        val within = (g - tl.offsetsMs[i]).toInt()
        if (mp == null || i != index) open(i, within, playing) else mp!!.seekTo(within)
    }

    fun toggle() {
        if (mp == null) { open(0, 0, true); return }
        if (playing) { mp!!.pause(); playing = false } else { mp!!.start(); playing = true }
    }

    fun release() { mp?.release(); mp = null; playing = false }
}

private fun mmss(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

@Composable
fun ListenScreen(app: LectureApp, lectureId: String, onBack: () -> Unit) {
    val all by app.repo.lectures.collectAsStateWithLifecycle()
    val l = all.firstOrNull { it.id == lectureId } ?: return
    val tl = remember(l.segments) {
        val segs = l.segments.sortedBy { it.seq }
        var off = 0L
        val files = mutableListOf<File>(); val offs = mutableListOf<Long>()
        for (s in segs) { val f = File(s.path); if (f.exists()) { files += f; offs += off }; off += s.durationMs }
        Timeline(files, offs, off)
    }
    val player = remember(tl) { if (tl.files.isEmpty()) null else MultiPlayer(tl) }
    DisposableEffect(player) { onDispose { player?.release() } }

    var transcript by remember { mutableStateOf<Transcript?>(null) }
    var tMsg by remember { mutableStateOf("Loading transcript…") }
    LaunchedEffect(l.remoteId, l.stage) {
        val rid = l.remoteId ?: run { tMsg = "Transcript not ready (lecture not uploaded yet)"; return@LaunchedEffect }
        runCatching { ApiProvider.get(app.settings).transcript(rid) }
            .onSuccess { t -> transcript = t; tMsg = if (t == null || t.lines.isEmpty()) "Transcript not ready yet" else "" }
            .onFailure { e -> tMsg = "Transcript not ready (${e.message})" }
    }

    var pos by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(player) {
        while (player != null) { if (dragging == null) pos = player.positionMs(); playing = player.playing; delay(250) }
    }

    val lines = transcript?.lines.orEmpty()
    val current = lines.indexOfLast { it.t0 * 1000 <= pos }
    val listState = rememberLazyListState()
    LaunchedEffect(current, playing) { if (playing && current >= 0) listState.animateScrollToItem(maxOf(0, current - 2)) }

    AppScaffold("Listen", onBack = onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(l.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (player == null) Text("No local recording on this phone — transcript only.", style = MaterialTheme.typography.bodySmall)
                else {
                    val total = maxOf(1L, tl.totalMs)
                    Slider(value = dragging ?: (pos.toFloat() / total).coerceIn(0f, 1f),
                        onValueChange = { dragging = it },
                        onValueChangeFinished = { dragging?.let { player.seekTo((it * total).toLong()); pos = (it * total).toLong() }; dragging = null })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(mmss(dragging?.let { (it * total).toLong() } ?: pos))
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { player.seekTo(pos - 15_000); pos = player.positionMs() }) { Text("−15s") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { player.toggle(); playing = player.playing }) { Text(if (playing) "Pause" else "Play") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { player.seekTo(pos + 15_000); pos = player.positionMs() }) { Text("+15s") }
                        Spacer(Modifier.weight(1f))
                        Text(mmss(tl.totalMs))
                    }
                }
                transcript?.let { if (!it.complete) Text("Partial transcript (ASR still running)", style = MaterialTheme.typography.bodySmall) }
                if (tMsg.isNotEmpty()) Text(tMsg, style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                itemsIndexed(lines) { i, line ->
                    val active = i == current
                    Row(Modifier.fillMaxWidth()
                        .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                        .clickable(enabled = player != null) {
                            player?.seekTo((line.t0 * 1000).toLong()); pos = (line.t0 * 1000).toLong()
                            if (player != null && !player.playing) { player.toggle(); playing = true }
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(mmss((line.t0 * 1000).toLong()), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(52.dp))
                        Text(line.text, style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}
