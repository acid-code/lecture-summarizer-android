package il.ac.tau.lecturesummarizer.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
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
private val Green = Color(0xFF2E7D32)
private val Amber = Color(0xFFB26A00)

// ---------- Connection status (reuses the existing WebView-cookie sessions; no new auth) ----------
object Connections {
    fun calendar(ctx: Context) = CalendarResolver.hasPermission(ctx)
    fun moodle(ctx: Context): Boolean = runCatching {
        val c = android.webkit.CookieManager.getInstance().getCookie(MoodleStore(ctx.applicationContext).baseUrl) ?: return false
        c.split(';').any { it.substringBefore('=').trim().startsWith("MoodleSession", true) }
    }.getOrDefault(false)
    fun panopto(): Boolean = runCatching { Panopto.hasSessionCookie() }.getOrDefault(false)
}

/** Connected / not-connected chip used in onboarding and Settings → Connections. */
@Composable
fun ConnectionChip(connected: Boolean) {
    AssistChip(onClick = {}, enabled = true,
        label = { Text(stringResource(if (connected) R.string.connected else R.string.not_connected)) },
        leadingIcon = if (connected) ({ Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp), tint = Green) }) else null,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (connected) Green.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant))
}

/** Row: title + connection chip + connect button (hidden once connected). */
@Composable
fun ConnectionRow(title: String, connected: Boolean, onConnect: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            ConnectionChip(connected)
            if (!connected) TextButton(onClick = onConnect) { Text(stringResource(R.string.connect)) }
        }
    }
}

// ---------- Lecture status chip (replaces emoji labels) ----------

/**
 * ממתין להעלאה (grey) · מעלה 42% (blue progress) · בעיבוד (navy, indeterminate) · מוכן (green) ·
 * נכשל — נסה שוב (red, action) · הקלטה חלקית (amber).
 */
@Composable
fun LectureStatusChip(l: Lecture, partial: Boolean = false, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val uploadPct = if (l.segments.isEmpty()) l.progress.coerceIn(0, 100)
        else (100 * l.segments.count { it.uploaded } / l.segments.size)
    val (label, color) = when {
        partial && l.state != LectureState.FAILED -> stringResource(R.string.st_partial) to Amber
        else -> when (l.state) {
            LectureState.DRAFT -> stringResource(R.string.st_draft) to cs.onSurfaceVariant
            LectureState.RECORDING -> stringResource(R.string.st_recording) to cs.error
            LectureState.RECORDED -> stringResource(R.string.st_waiting) to cs.onSurfaceVariant
            LectureState.UPLOADING -> stringResource(R.string.st_uploading, uploadPct) to Color(0xFF2B5BB5)
            LectureState.PROCESSING -> stringResource(R.string.st_processing) to Navy
            LectureState.DONE -> stringResource(R.string.st_done) to Green
            LectureState.FAILED -> stringResource(R.string.st_failed) to cs.error
        }
    }
    Column(modifier.width(IntrinsicSize.Max)) {
        val chipColors = AssistChipDefaults.assistChipColors(containerColor = color.copy(alpha = 0.12f), labelColor = color,
            leadingIconContentColor = color)
        if (l.state == LectureState.FAILED && onRetry != null)
            AssistChip(onClick = onRetry, label = { Text(label, style = MaterialTheme.typography.labelMedium) }, modifier = Modifier.height(28.dp),
                leadingIcon = { Icon(Icons.Default.Refresh, null, Modifier.size(16.dp)) }, colors = chipColors)
        else
            SuggestionChip(onClick = {}, label = { Text(label, style = MaterialTheme.typography.labelMedium) }, modifier = Modifier.height(28.dp),
                icon = if (l.state == LectureState.FAILED) ({ Icon(Icons.Default.ErrorOutline, null, Modifier.size(16.dp)) }) else null,
                colors = SuggestionChipDefaults.suggestionChipColors(containerColor = color.copy(alpha = 0.12f), labelColor = color, iconContentColor = color))
        when (l.state) {
            LectureState.UPLOADING -> LinearProgressIndicator(progress = { uploadPct / 100f }, Modifier.fillMaxWidth().height(3.dp), color = color)
            LectureState.PROCESSING -> LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = Navy)
            else -> {}
        }
    }
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
fun OfflineState(onRetry: () -> Unit, modifier: Modifier = Modifier) =
    EmptyState(stringResource(R.string.es_offline_title), stringResource(R.string.es_offline_body), modifier) {
        Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }

// ---------- Connectivity ----------

private fun isOnlineNow(ctx: Context): Boolean {
    val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/** Live online state; second value re-checks immediately (for "נסה שוב"). */
@Composable
fun rememberOnline(): Pair<Boolean, () -> Unit> {
    val ctx = LocalContext.current
    var online by remember { mutableStateOf(isOnlineNow(ctx)) }
    DisposableEffect(Unit) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online = true }
            override fun onLost(network: Network) { online = isOnlineNow(ctx) }
        }
        runCatching { cm?.registerDefaultNetworkCallback(cb) }
        onDispose { runCatching { cm?.unregisterNetworkCallback(cb) } }
    }
    return online to { online = isOnlineNow(ctx) }
}
