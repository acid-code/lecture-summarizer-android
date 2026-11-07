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

@OptIn(ExperimentalLayoutApi::class)
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
    val QC = il.ac.tau.lecturesummarizer.autosync.QueueControl
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        Text("הורדה וסיכום אוטומטיים · Auto-download", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        ProBadge()
    }
    fun toggle(f: ProFeature, on: Boolean, set: (Boolean) -> Unit) {
        if (!pro) { upgrade = true; return }
        set(on); AutoSyncWorker.schedule(ctx.applicationContext)
        if (on) AutoSyncWorker.runNow(ctx.applicationContext)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ── 1. Last run / results (read-only status) ──
        if (pro && (retro || live)) SyncCard("סטטוס וריצה אחרונה · Last run") {
            val pausedLive by QC.paused
            val isPaused = pausedLive ?: st.paused
            var cTick by remember { mutableStateOf(0) }
            val c = remember(runStatus, cTick) { st.counts() }
            var nfTick by remember { mutableStateOf(0) }
            val nf = remember(runStatus, nfTick) { st.notFoundItems() }
            if (!CalendarResolver.hasPermission(ctx)) Text("נדרשת גישה ליומן", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Queue:", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
                StatusChip(if (isPaused) "Paused · מושהה" else if (runStatus != null && runStatus!!.startsWith("Stopped")) "Stopped · נעצר" else "Active · פעיל",
                    if (isPaused) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer)
            }
            (runStatus ?: st.lastRun)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip("Imported · יובאו ${c["done"] ?: 0}")
                StatusChip("Already had · קיימים ${c["skip"] ?: 0}")
                StatusChip("Waiting · ממתינים ${c["wait"] ?: 0}")
                StatusChip("Not found · לא נמצאו ${c["notfound"] ?: 0}", if ((c["notfound"] ?: 0) > 0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant)
            }
            NotFoundList(nf, c["notfound"] ?: 0, legacy = remember(runStatus) { st.notFoundSample },
                onDismiss = { k -> st.dismissNotFound(k); nfTick++; cTick++ })
            (c["dismissed"] ?: 0).takeIf { it > 0 }?.let { n ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Dismissed forever · הוסרו לצמיתות: $n", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = { st.undismissAll(); cTick++ }) { Text("Restore · שחזר") }
                }
            }
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isPaused) Button(onClick = { QC.resume(ctx.applicationContext) }) { Text("Resume · המשך") }
                else {
                    Button(onClick = { AutoSyncWorker.runNow(ctx.applicationContext) }) { Text("Run now · הרץ עכשיו") }
                    OutlinedButton(onClick = { QC.pause(ctx.applicationContext) }) { Text("Pause · השהה") }
                    if ((c["notfound"] ?: 0) > 0) TextButton(onClick = { st.resetNotFound(); AutoSyncWorker.runNow(ctx.applicationContext) }) { Text("Retry not found · נסה שוב") }
                }
            }
        }
        // ── 2. Live / rolling ──
        SyncCard("עדכונים שוטפים · Rolling updates") {
            ToggleRow(ProFeature.LIVE_RECORDINGS.he,
                "New classes are checked every ~3 h for 2 days, then daily up to 10 days after the class (some courses upload late). Recordings not on Panopto yet stay on a pending watch (daily, up to 3 weeks) and are imported when they appear.",
                live && pro) { on -> toggle(ProFeature.LIVE_RECORDINGS, on) { live = it; st.live = it } }
        }
        // ── 2b. Whitelist ──
        if (pro && (retro || live)) SyncCard("מה לייבא · What to import") {
            var wlMode by remember { mutableStateOf(st.whitelistMode) }
            var wl by remember { mutableStateOf(st.whitelist) }
            var draft by remember { mutableStateOf("") }
            ToggleRow("Only whitelisted courses · רק קורסים מרשימה",
                "Off: every class in your calendar is imported. On: only calendar items whose course name or number contains one of the entries below.",
                wlMode) { wlMode = it; st.whitelistMode = it; AutoSyncWorker.runNow(ctx.applicationContext) }
            if (wlMode) {
                if (wl.isEmpty()) Text("⚠️ Whitelist is empty — nothing will be imported.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    wl.forEach { w -> InputChip(selected = false, onClick = { wl = wl - w; st.whitelist = wl }, label = { Text("$w  ✕") }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), singleLine = true, label = { Text("Course name or number") })
                    TextButton(enabled = draft.isNotBlank(), onClick = { wl = (wl + draft.trim()).distinct(); st.whitelist = wl; draft = ""; AutoSyncWorker.runNow(ctx.applicationContext) }) { Text("Add") }
                }
            }
        }
        // ── 3. Historical / retro ──
        SyncCard("הרצאות קודמות · Historical lectures") {
            ToggleRow(ProFeature.RETRO_SUMMARIZER.he,
                "Past calendar lectures/recitations missing from the notebook → find the Panopto recording, import + summarize.",
                retro && pro) { on -> toggle(ProFeature.RETRO_SUMMARIZER, on) { retro = it; st.retro = it } }
            if (retro && pro) {
                Text("Look back · כמה אחורה", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 60, 120, 180).forEach { d -> FilterChip(lookback == d, { lookback = d; st.lookbackDays = d }, label = { Text("${d}d") }) }
                }
            }
        }
        // ── 4. Power & network ──
        if (pro && (retro || live)) SyncCard("סוללה ורשת · Battery & network") {
            var floorPct by remember { mutableStateOf(st.batteryHardMin) }
            val b = remember(runStatus) { il.ac.tau.lecturesummarizer.autosync.BatteryGate.read(ctx.applicationContext) }
            Text("Runs on Wi‑Fi in the background; on mobile data it asks first. Newest classes first, ≤2 summaries at a time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Battery now: ${b.pct}%${if (b.charging) " (charging)" else ""} · asks below 75%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Stop background sync below · עצור מתחת ל־", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                il.ac.tau.lecturesummarizer.autosync.BatteryGate.PRESETS.forEach { p ->
                    FilterChip(selected = floorPct == p, onClick = { floorPct = p; st.batteryHardMin = p }, label = { Text("$p%") })
                }
            }
        }
        if (!pro) OutlinedButton(onClick = { upgrade = true }) { Text("Upgrade to Pro · שדרג") }
        else ent.source?.let { Text("Pro active ($it)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (upgrade) AlertDialog(onDismissRequest = { upgrade = false },
        title = { Row(verticalAlignment = Alignment.CenterVertically) { Text("Lecture Summarizer Pro  "); ProBadge() } },
        text = { Text("Pro automatically downloads and summarizes your Panopto lectures:\n\n• ${ProFeature.RETRO_SUMMARIZER.title} — catch up on every past class in your calendar\n• ${ProFeature.LIVE_RECORDINGS.title} — new recordings are summarized as soon as they're posted\n\n" +
            if (ent.canSelfActivate) "Store subscriptions aren't live yet — this beta build can activate Pro for testing." else "Subscriptions are coming soon.") },
        confirmButton = { if (ent.canSelfActivate) TextButton(onClick = { ent.setPro(true, "beta"); pro = true; upgrade = false }) { Text("Activate beta Pro") } },
        dismissButton = { TextButton(onClick = { upgrade = false }) { Text("Not now") } })
}

@Composable
private fun SyncCard(title: String, content: @Composable ColumnScope.() -> Unit) =
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }

@Composable
private fun ToggleRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }

@Composable
private fun StatusChip(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceVariant) =
    Surface(color = color, shape = RoundedCornerShape(8.dp)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
    }

/** Expandable list of individual classes whose recording wasn't found. */
@Composable
private fun NotFoundList(items: List<il.ac.tau.lecturesummarizer.autosync.NotFoundItem>, total: Int, legacy: String?, onDismiss: (String) -> Unit) {
    if (total == 0 && items.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val fmt = remember { java.text.SimpleDateFormat("EEE dd/MM HH:mm", java.util.Locale.getDefault()) }
    Column {
        TextButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text((if (open) "▾ " else "▸ ") + "What wasn't found · מה לא נמצא (${items.size.takeIf { it > 0 } ?: total})")
        }
        if (open) {
            if (items.isEmpty()) Text(legacy?.let { "From last pass: $it\n(Details appear after the next run.)" } ?: "Details appear after the next run.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEach { i ->
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(i.course, style = MaterialTheme.typography.bodyMedium)
                            Text(listOfNotNull(i.kind.ifEmpty { null }, fmt.format(java.util.Date(i.classMs))).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onDismiss(i.key) }) { Text("Dismiss forever · הסר") }
                    }
                }
                if (total > items.size) Text("Showing latest ${items.size} of $total", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
