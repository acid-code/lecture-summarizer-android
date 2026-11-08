package il.ac.tau.lecturesummarizer.ui

import androidx.compose.foundation.layout.*
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import il.ac.tau.lecturesummarizer.R
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

@OptIn(ExperimentalLayoutApi::class,ExperimentalMaterial3Api::class)
/** Settings → Auto-download & summarize (Pro): retro summarizer + live recordings watch. */
@Composable
fun AutoSyncSettingsSection(app: LectureApp) {
    val ctx = LocalContext.current
    val ent = remember { Entitlements(ctx.applicationContext) }
    val st = remember { AutoSyncStore(ctx.applicationContext) }
    var pro by remember { mutableStateOf(ent.isPro) }
    var retro by remember { mutableStateOf(st.retro) }
    var live by remember { mutableStateOf(st.live) }
    var slidesSearch by remember{mutableStateOf(st.autoAttach)}
    var lookback by remember { mutableIntStateOf(st.lookbackDays) }
    var upgrade by remember { mutableStateOf(false) }
    val runStatus by AutoSyncWorker.status
    val scope=rememberCoroutineScope();val lectures by app.repo.lectures.collectAsState();var queueTick by remember{mutableIntStateOf(0)};var actionError by remember{mutableStateOf(false)}
    DisposableEffect(Unit){val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener{_,_->queueTick++};st.prefs.registerOnSharedPreferenceChangeListener(listener);onDispose{st.prefs.unregisterOnSharedPreferenceChangeListener(listener)}}
    val queueRows=remember(queueTick,lectures){st.entries(app.repo)}
    val QC = il.ac.tau.lecturesummarizer.autosync.QueueControl
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_automatic_sync), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        ProBadge()
    }
    fun toggle(f: ProFeature, on: Boolean, set: (Boolean) -> Unit) {
        if (!pro) { upgrade = true; return }
        set(on); AutoSyncWorker.schedule(ctx.applicationContext)
        if (on) AutoSyncWorker.runNow(ctx.applicationContext)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SyncCard(stringResource(R.string.slides_search_setting)){
            ToggleRow(stringResource(R.string.slides_search_setting),stringResource(R.string.slides_search_setting_body),slidesSearch){on->
                slidesSearch=on;st.autoAttach=on
                if(!on)app.repo.lectures.value.forEach{l->androidx.work.WorkManager.getInstance(app).cancelUniqueWork("slides-match-${l.id}");il.ac.tau.lecturesummarizer.slides.SlideChoices(app).phase(l.id,"disabled")}
            }
            var repairConfirm by remember{mutableStateOf(false)}
            var repairTick by remember{mutableIntStateOf(0)}
            val repairStatus = remember(repairTick){il.ac.tau.lecturesummarizer.slides.SlideDeckRepairWorker.status.get()}
            Text(stringResource(R.string.slides_repair_body),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(
                enabled=slidesSearch,
                onClick={repairConfirm=true},
                modifier=Modifier.fillMaxWidth(),
            ){Text(stringResource(R.string.slides_repair_action))}
            repairStatus?.let{Text(it,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            if(repairConfirm)AlertDialog(
                onDismissRequest={repairConfirm=false},
                title={Text(stringResource(R.string.slides_repair_action))},
                text={Text(stringResource(R.string.slides_repair_confirm))},
                confirmButton={TextButton(onClick={
                    repairConfirm=false
                    il.ac.tau.lecturesummarizer.slides.SlideDeckRepairWorker.enqueue(app)
                    repairTick++
                    scope.launch{repeat(40){kotlinx.coroutines.delay(1500);repairTick++;if(il.ac.tau.lecturesummarizer.slides.SlideDeckRepairWorker.status.get()?.startsWith("Done")==true)return@launch}}
                }){Text(stringResource(R.string.slides_repair_action))}},
                dismissButton={TextButton(onClick={repairConfirm=false}){Text(stringResource(R.string.copy_cancel))}},
            )
        }
        // ── 1. Last run / results (read-only status) ──
        if (pro && (retro || live)) SyncCard(stringResource(R.string.sync_last_run)) {
            val pausedLive by QC.paused
            val isPaused = pausedLive ?: st.paused
            var cTick by remember { mutableStateOf(0) }
            val c = remember(runStatus, cTick,queueTick) { st.counts() }
            if (!CalendarResolver.hasPermission(ctx)) Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_calendar_access_required), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_queue), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
                StatusChip(stringResource(if (isPaused) R.string.sync_paused else if (runStatus?.startsWith("Stopped") == true) R.string.sync_stopped else R.string.sync_active),
                    if (isPaused) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary)
            }
            if (app.settings.showDeveloper) (runStatus ?: st.lastRun)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            SyncQueueDisclosure(queueRows,onRetry={row->actionError=false
            if(row.category=="failed")row.lecture?.let{lecture->scope.launch{try{il.ac.tau.lecturesummarizer.upload.retryLecture(app,lecture.id)}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){actionError=true}}}
            else if(st.retry(row.key))AutoSyncWorker.runNow(app)
        },onDismiss={row->st.dismissNotFound(row.key)},error=actionError)
            (c["dismissed"] ?: 0).takeIf { it > 0 }?.let { n ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.sync_dismissed, n), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = { st.undismissAll(); cTick++ }) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_restore)) }
                }
            }
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isPaused) Button(onClick = { QC.resume(ctx.applicationContext) }) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_resume)) }
                else {
                    Button(onClick = { AutoSyncWorker.runNow(ctx.applicationContext) }) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_run_now)) }
                    OutlinedButton(onClick = { QC.pause(ctx.applicationContext) }) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_pause)) }
                    if ((c["notfound"] ?: 0) > 0) TextButton(onClick = { st.resetNotFound(); AutoSyncWorker.runNow(ctx.applicationContext) }) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_search_again)) }
                }
            }
        }
        // ── 2. Live / rolling ──
        SyncCard(stringResource(R.string.sync_rolling)) {
            ToggleRow(stringResource(R.string.sync_live),
                stringResource(R.string.sync_live_body),
                live && pro) { on -> toggle(ProFeature.LIVE_RECORDINGS, on) { live = it; st.live = it } }
        }
        // ── 2b. Whitelist ──
        if (pro && (retro || live)) SyncCard(stringResource(R.string.sync_what)) {
            var wlMode by remember { mutableStateOf(st.whitelistMode) }
            var wl by remember { mutableStateOf(st.whitelist) }
            var draft by remember { mutableStateOf("") }
            ToggleRow(stringResource(R.string.sync_allowlist),
                stringResource(R.string.sync_allowlist_body),
                wlMode) { wlMode = it; st.whitelistMode = it; AutoSyncWorker.runNow(ctx.applicationContext) }
            if (wlMode) {
                if (wl.isEmpty()) Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_the_allowlist_is_empty_nothing_will_be_imported), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    wl.forEach { w -> InputChip(selected = false, onClick = { wl = wl - w; st.whitelist = wl }, label = { Text("$w  ✕") }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), singleLine = true, label = { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_course_name_or_number)) })
                    TextButton(enabled = draft.isNotBlank(), onClick = { wl = (wl + draft.trim()).distinct(); st.whitelist = wl; draft = ""; AutoSyncWorker.runNow(ctx.applicationContext) }) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_add)) }
                }
            }
        }
        // ── 3. Historical / retro ──
        SyncCard(stringResource(R.string.sync_history)) {
            ToggleRow(stringResource(R.string.sync_retro),
                stringResource(R.string.sync_retro_body),
                retro && pro) { on -> toggle(ProFeature.RETRO_SUMMARIZER, on) { retro = it; st.retro = it } }
            if (retro && pro) {
                Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_look_back_range), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 60, 120, 180).forEach { d -> FilterChip(lookback == d, { lookback = d; st.lookbackDays = d }, label = { Text(stringResource(R.string.sync_days, d)) }) }
                }
            }
        }
        // ── 4. Power & network ──
        if (pro && (retro || live)) SyncCard(stringResource(R.string.sync_power)) {
            var floorPct by remember { mutableStateOf(st.batteryHardMin) }
            val b = remember(runStatus) { il.ac.tau.lecturesummarizer.autosync.BatteryGate.read(ctx.applicationContext) }
            Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_runs_in_the_background_on_wi_fi_asks_before_using_mobile_data_new), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.sync_battery, b.pct), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_pause_sync_when_battery_is_below), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                il.ac.tau.lecturesummarizer.autosync.BatteryGate.PRESETS.forEach { p ->
                    FilterChip(selected = floorPct == p, onClick = { floorPct = p; st.batteryHardMin = p }, label = { Text(stringResource(R.string.sync_percent, p)) })
                }
            }
        }
        if (!pro) OutlinedButton(onClick = { upgrade = true }) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_upgrade_to_pro)) }
        else ent.source?.let { Text(stringResource(R.string.sync_pro_active), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (upgrade) AlertDialog(onDismissRequest = { upgrade = false },
        title = { Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(il.ac.tau.lecturesummarizer.R.string.brand_pro)); ProBadge() } },
        text = { Text(stringResource(R.string.sync_pro_body) + "\n\n" + stringResource(if (ent.canSelfActivate) R.string.sync_beta else R.string.sync_soon)) },
        confirmButton = { if (ent.canSelfActivate) TextButton(onClick = { ent.setPro(true, "beta"); pro = true; upgrade = false }) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_activate_beta_pro)) } },
        dismissButton = { TextButton(onClick = { upgrade = false }) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_later)) } })
}

@Composable
internal fun SyncCard(title: String, content: @Composable ColumnScope.() -> Unit) =
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

/** Expandable list of individual classes whose recording wasn't found. */
@Composable
private fun NotFoundList(items: List<il.ac.tau.lecturesummarizer.autosync.NotFoundItem>, total: Int, legacy: String?, onDismiss: (String) -> Unit) {
    if (total == 0 && items.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val fmt = remember { java.text.SimpleDateFormat("EEE dd/MM HH:mm", java.util.Locale.getDefault()) }
    Column {
        TextButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text((if (open) "▾ " else "▸ ") + stringResource(R.string.sync_found_missing, items.size.takeIf { it > 0 } ?: total))
        }
        if (open) {
            if (items.isEmpty()) Text(stringResource(R.string.sync_missing_detail),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEach { i ->
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(i.course, style = MaterialTheme.typography.bodyMedium)
                            Text(listOfNotNull(classKindLabel(il.ac.tau.lecturesummarizer.moodle.MoodleMatch.kindFromTitle(i.kind)), fmt.format(java.util.Date(i.classMs))).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onDismiss(i.key) }) { Text(androidx.compose.ui.res.stringResource(il.ac.tau.lecturesummarizer.R.string.copy_dismiss_forever)) }
                    }
                }
                if (total > items.size) Text(stringResource(R.string.sync_showing, items.size, total), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
