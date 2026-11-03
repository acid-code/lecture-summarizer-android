package il.ac.tau.lecturesummarizer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.autosync.AutoSyncStore
import il.ac.tau.lecturesummarizer.autosync.AutoSyncWorker
import il.ac.tau.lecturesummarizer.calendar.CalendarResolver
import il.ac.tau.lecturesummarizer.data.Entitlements
import il.ac.tau.lecturesummarizer.data.ProFeature

@Composable
fun ProBadge() = Surface(color = MaterialTheme.colorScheme.tertiary, shape = RoundedCornerShape(6.dp)) {
    Text("PRO", Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onTertiary, fontWeight = FontWeight.Bold)
}

/** Settings → Auto-download & summarize (Pro): retro summarizer + live recordings watch. */
@Composable
fun AutoSyncSettingsSection(app: LectureApp) {
    val ctx = LocalContext.current
    val ent = remember { Entitlements(ctx.applicationContext) }
    val st = remember { AutoSyncStore(ctx.applicationContext) }
    var pro by remember { mutableStateOf(ent.isPro) }
    var retro by remember { mutableStateOf(st.retro) }
    var live by remember { mutableStateOf(st.live) }
    var lookback by remember { mutableIntStateOf(st.lookbackDays) }
    var upgrade by remember { mutableStateOf(false) }
    val runStatus by AutoSyncWorker.status
    HorizontalDivider()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("הורדה וסיכום אוטומטיים · Auto-download", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        ProBadge()
    }
    fun toggle(f: ProFeature, on: Boolean, set: (Boolean) -> Unit) {
        if (!pro) { upgrade = true; return }
        set(on); AutoSyncWorker.schedule(ctx.applicationContext)
        if (on) AutoSyncWorker.runNow(ctx.applicationContext)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(ProFeature.RETRO_SUMMARIZER.he)
            Text("Past calendar lectures/recitations missing from the notebook → find the Panopto recording, import + summarize.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(retro && pro, { on -> toggle(ProFeature.RETRO_SUMMARIZER, on) { retro = it; st.retro = it } })
    }
    if (retro && pro) Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Look back:", style = MaterialTheme.typography.bodySmall)
        listOf(30, 60, 120, 180).forEach { d -> FilterChip(lookback == d, { lookback = d; st.lookbackDays = d }, label = { Text("${d}d") }, modifier = Modifier.padding(start = 4.dp)) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(ProFeature.LIVE_RECORDINGS.he)
            Text("New classes from the last 2 days are checked every ~3 h; any whose recording isn't on Panopto yet stay on a pending watch, searched daily for up to 3 weeks, and are imported when it appears.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(live && pro, { on -> toggle(ProFeature.LIVE_RECORDINGS, on) { live = it; st.live = it } })
    }
    if (pro && (retro || live)) {
        if (!CalendarResolver.hasPermission(ctx)) Text("⚠️ Needs calendar access (above).", style = MaterialTheme.typography.bodySmall)
        Text("Runs on Wi‑Fi in the background; on mobile data it asks first. Newest classes first, ≤2 summaries at a time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val c = remember(runStatus) { st.counts() }
        Text("Imported ${c["done"] ?: 0} · already had ${c["skip"] ?: 0} · waiting ${c["wait"] ?: 0} · not found ${c["notfound"] ?: 0}", style = MaterialTheme.typography.bodySmall)
        remember(runStatus) { st.notFoundSample }?.let { Text("Not found e.g.: $it", style = MaterialTheme.typography.bodySmall) }
        (runStatus ?: st.lastRun)?.let { Text("Last run: $it", style = MaterialTheme.typography.bodySmall) }
        val pausedLive by il.ac.tau.lecturesummarizer.autosync.QueueControl.paused
        val isPaused = pausedLive ?: st.paused
        val b = remember(runStatus) { il.ac.tau.lecturesummarizer.autosync.BatteryGate.read(ctx.applicationContext) }
        Text("Battery: ${b.pct}%${if (b.charging) " (charging)" else ""} · asks below 75%, never runs below 30% unless charging", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            if (isPaused) Button(onClick = { il.ac.tau.lecturesummarizer.autosync.QueueControl.resume(ctx.applicationContext) }) { Text("Resume queue · המשך") }
            else OutlinedButton(onClick = { il.ac.tau.lecturesummarizer.autosync.QueueControl.pause(ctx.applicationContext) }) { Text("Pause queue · השהה") }
            Spacer(Modifier.width(8.dp))
        }
        if (!isPaused) Row {
            OutlinedButton(onClick = { AutoSyncWorker.runNow(ctx.applicationContext) }) { Text("Run now") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { st.resetNotFound(); AutoSyncWorker.runNow(ctx.applicationContext) }) { Text("Retry not-found") }
        }
    }
    if (!pro) OutlinedButton(onClick = { upgrade = true }) { Text("Upgrade to Pro · שדרג") }
    else ent.source?.let { Text("Pro active ($it)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    if (upgrade) AlertDialog(onDismissRequest = { upgrade = false },
        title = { Row(verticalAlignment = Alignment.CenterVertically) { Text("Lecture Summarizer Pro  "); ProBadge() } },
        text = { Text("Pro automatically downloads and summarizes your Panopto lectures:\n\n• ${ProFeature.RETRO_SUMMARIZER.title} — catch up on every past class in your calendar\n• ${ProFeature.LIVE_RECORDINGS.title} — new recordings are summarized as soon as they're posted\n\n" +
            if (ent.canSelfActivate) "Store subscriptions aren't live yet — this beta build can activate Pro for testing." else "Subscriptions are coming soon.") },
        confirmButton = { if (ent.canSelfActivate) TextButton(onClick = { ent.setPro(true, "beta"); pro = true; upgrade = false }) { Text("Activate beta Pro") } },
        dismissButton = { TextButton(onClick = { upgrade = false }) { Text("Not now") } })
}
