package il.ac.tau.lecturesummarizer.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import il.ac.tau.lecturesummarizer.R
import il.ac.tau.lecturesummarizer.calendar.CalendarResolver
import il.ac.tau.lecturesummarizer.data.Lecture
import il.ac.tau.lecturesummarizer.data.LectureState
import il.ac.tau.lecturesummarizer.moodle.MoodleStore
import il.ac.tau.lecturesummarizer.moodle.Panopto

// ---------- Brand colors for statuses / illustrations ----------
val Navy = Color(0xFF1E3A8A)
val BrandYellow = Color(0xFFF2B705)
private val Green = Color(0xFF1B5E20)
private val Amber = Color(0xFF7A4700)

// ---------- Connection status (reuses the existing WebView-cookie sessions; no new auth) ----------
object Connections {
    fun calendar(ctx: Context) = il.ac.tau.lecturesummarizer.calendar.CalendarConnection.connected(ctx)
    fun moodle(ctx: Context): Boolean = runCatching {
        val c = android.webkit.CookieManager.getInstance().getCookie(MoodleStore(ctx.applicationContext).baseUrl) ?: return false
        c.split(';').any { it.substringBefore('=').trim().startsWith("MoodleSession", true) }
    }.getOrDefault(false)
    fun panopto(): Boolean = runCatching { Panopto.hasSessionCookie() }.getOrDefault(false)
}

/** One semantic chip primitive for connection, queue, lecture and slide states. */
@Composable
fun StatusChip(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    progress: Int? = null, busy: Boolean = false, offline: Boolean = false, onClick: (() -> Unit)? = null, icon:androidx.compose.ui.graphics.vector.ImageVector=Icons.Default.Info) {
    Column {
        if(onClick==null) Surface(color=color.copy(alpha=0.12f),contentColor=color,shape=MaterialTheme.shapes.small) {
            Row(Modifier.padding(horizontal=10.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Icon(if(offline)Icons.Default.CloudOff else icon,null,Modifier.size(16.dp))
                Text(text,style=MaterialTheme.typography.labelMedium)
            }
        } else AssistChip(onClick=onClick,label={Text(text)},leadingIcon={Icon(Icons.Default.Refresh,null,Modifier.size(16.dp))},
            colors=AssistChipDefaults.assistChipColors(containerColor=color.copy(alpha=0.12f),labelColor=color,leadingIconContentColor=color))
        if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0, 100) / 100f }, modifier = Modifier.width(120.dp).height(3.dp), color = color)
        else if (busy) LinearProgressIndicator(Modifier.width(120.dp).height(3.dp), color = color)
    }
}

@Composable
fun ConnectionChip(connected: Boolean) = StatusChip(stringResource(if (connected) R.string.connected else R.string.not_connected),
    if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun ConnectionRow(title: String, connected: Boolean, onDisconnect: (() -> Unit)? = null, onConnect: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                ConnectionChip(connected)
                if (!connected) TextButton(onClick = onConnect) { Text(stringResource(R.string.connect)) }
                else if (onDisconnect != null) TextButton(onClick = onDisconnect) { Text(stringResource(R.string.disconnect)) }
            }
        }
    }
}

/** Offline is orthogonal to the existing state; never masquerades as failure. */
@Composable
fun LectureStatusChip(l: Lecture, partial: Boolean = false, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val online = rememberOnline().first
    val pct = if (l.segments.isEmpty()) l.progress.coerceIn(0, 100) else 100 * l.segments.count { it.uploaded } / l.segments.size
    val processing = l.state == LectureState.PROCESSING
    val (res, color) = when {
        partial && l.state != LectureState.FAILED -> R.string.st_partial to if(androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFFFFDA8A) else Amber
        else -> when (l.state) {
            LectureState.DRAFT -> R.string.st_draft to cs.onSurfaceVariant
            LectureState.RECORDING -> R.string.st_recording to cs.error
            LectureState.RECORDED -> R.string.st_waiting to cs.onSurfaceVariant
            LectureState.UPLOADING -> R.string.st_uploading to cs.primary
            LectureState.PROCESSING -> (if (l.stage in setOf("asr", "repair", "uploaded", "normalized", "transcribed", "queued", "asr_wait")) R.string.st_transcribing else R.string.st_summarizing) to cs.primary
            LectureState.DONE -> R.string.st_done to if(androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF95D5A0) else Green
            LectureState.FAILED -> (if(online)R.string.st_failed else R.string.st_failed_offline) to cs.error
        }
    }
    Box(modifier) { StatusChip(if (l.state == LectureState.UPLOADING) stringResource(res, pct) else stringResource(res), color,
        progress = pct.takeIf { l.state == LectureState.UPLOADING }, busy = processing && online, offline = !online,
        onClick = onRetry.takeIf { l.state == LectureState.FAILED && online }, icon=when(l.state){LectureState.DONE->Icons.Default.CheckCircle;LectureState.FAILED->Icons.Default.Error;LectureState.UPLOADING->Icons.Default.CloudUpload;LectureState.RECORDING->Icons.Default.Mic;LectureState.PROCESSING->Icons.Default.AutoAwesome;else->Icons.Default.Schedule}) }
}

// ---------- Empty states ----------

/** Simple navy line-art + yellow accent illustration (no bitmap assets). */
@Composable
fun EmptyIllustration(modifier: Modifier = Modifier.size(96.dp)) {
    val navy = if (MaterialTheme.colorScheme.primary == Navy) Navy else MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val w = size.width; val h = size.height; val sw = w * 0.04f
        // notebook outline
        drawRoundRect(navy, topLeft = Offset(w * 0.18f, h * 0.12f), size = androidx.compose.ui.geometry.Size(w * 0.56f, h * 0.72f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.06f), style = Stroke(sw))
        for (i in 0..2) drawLine(navy, Offset(w * 0.3f, h * (0.34f + i * 0.14f)), Offset(w * 0.62f, h * (0.34f + i * 0.14f)), sw * 0.8f)
        // yellow accent
        drawCircle(BrandYellow, radius = w * 0.14f, center = Offset(w * 0.76f, h * 0.76f))
    }
}

@Composable
fun EmptyState(title: String, body: String?, modifier: Modifier = Modifier, actions: @Composable ColumnScope.() -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EmptyIllustration()
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        body?.let { Text(it, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(4.dp))
        actions()
    }
}

/** Practice tab placeholder (empty-states.md); no CTA by design. */
@Composable
fun PracticeEmptyState(modifier: Modifier = Modifier) =
    EmptyState(stringResource(R.string.es_practice_title), stringResource(R.string.es_practice_body), modifier)

/** "אין חיבור" banner/empty state with retry. */
@Composable
fun OfflineState(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedCard(modifier.fillMaxWidth().padding(12.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.CloudOff, stringResource(R.string.es_offline_title))
            Text(stringResource(R.string.es_offline_title) + ". " + stringResource(R.string.es_offline_body),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}

// ---------- Connectivity ----------

private fun isOnlineNow(ctx: Context): Boolean {
    val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/** Live online state; second value re-checks immediately (for "נסה שוב"). */
@Composable
fun rememberOnline(): Pair<Boolean, () -> Unit> {
    val ctx = LocalContext.current
    var online by remember { mutableStateOf(isOnlineNow(ctx)) }
    DisposableEffect(Unit) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online = isOnlineNow(ctx) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { online = isOnlineNow(ctx) }
            override fun onLost(network: Network) { online = isOnlineNow(ctx) }
        }
        runCatching { cm?.registerDefaultNetworkCallback(cb) }
        onDispose { runCatching { cm?.unregisterNetworkCallback(cb) } }
    }
    return online to { online = isOnlineNow(ctx) }
}
