package il.ac.tau.lecturesummarizer.autosync

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.work.*
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.calendar.CalendarResolver
import il.ac.tau.lecturesummarizer.calendar.ClassKind
import il.ac.tau.lecturesummarizer.calendar.TauClass
import il.ac.tau.lecturesummarizer.data.ApiProvider
import il.ac.tau.lecturesummarizer.data.LectureState
import il.ac.tau.lecturesummarizer.data.TranscriptImport
import il.ac.tau.lecturesummarizer.moodle.*
import il.ac.tau.lecturesummarizer.panopto.PanoptoAudioWorker
import il.ac.tau.lecturesummarizer.upload.JobKick
import il.ac.tau.lecturesummarizer.upload.JobPollWorker
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Persistent per-user auto-sync queue + toggles (Pro features: retro summarizer, live recordings watch). */
class AutoSyncStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("autosync", Context.MODE_PRIVATE)
    var retro: Boolean get() = p.getBoolean("retro", false); set(v) = p.edit().putBoolean("retro", v).apply()
    var live: Boolean get() = p.getBoolean("live", false); set(v) = p.edit().putBoolean("live", v).apply()
    /** Retro look-back (days). */
    var lookbackDays: Int get() = p.getInt("lookback", 120); set(v) = p.edit().putInt("lookback", v).apply()
    /** Last time we asked to run on mobile data (throttle: ≤1 ask / 6 h). */
    var lastCellAsk: Long get() = p.getLong("cellAsk", 0); set(v) = p.edit().putLong("cellAsk", v).apply()
    var lastRun: String? get() = p.getString("lastRun", null); set(v) = p.edit().putString("lastRun", v).apply()

    /** eventKey → status: "done:<lectureId>" | "skip" | "notfound" | "wait" (live: recording not yet on Panopto). */
    fun status(key: String): String? = p.getString("st:$key", null)
    fun setStatus(key: String, v: String) = p.edit().putString("st:$key", v).apply()
    fun counts(): Map<String, Int> = p.all.filterKeys { it.startsWith("st:") }.values.groupingBy { (it as String).substringBefore(':') }.eachCount()
    fun resetNotFound() { val e = p.edit(); p.all.filter { it.key.startsWith("st:") && it.value == "notfound" }.keys.forEach { e.remove(it) }; e.apply() }

    companion object {
        fun key(c: TauClass) = "${c.courseNumber ?: TauEventNorm(c.courseName)}|${c.kind}|${c.beginMs}"
        private fun TauEventNorm(s: String) = il.ac.tau.lecturesummarizer.calendar.TauEventParser.normalize(s)
    }
}

/** Pure planning: which classes need work, newest first (rolling lectures beat the retro backlog). */
object AutoSyncPlanner {
    const val LIVE_WINDOW_DAYS = 14
    data class Item(val cls: TauClass, val key: String, val live: Boolean)

    fun plan(classes: List<TauClass>, nowMs: Long, retro: Boolean, live: Boolean, status: (String) -> String?,
             alreadyHave: (TauClass) -> Boolean): List<Item> {
        val liveFrom = nowMs - LIVE_WINDOW_DAYS * 86_400_000L
        return classes.filter { it.endMs <= nowMs }.mapNotNull { c ->
            val k = AutoSyncStore.key(c)
            val inLive = c.beginMs >= liveFrom
            val st = status(k)
            when {
                st != null && st != "wait" -> null            // done / skip / notfound are final
                st == "wait" && !(live && inLive) -> null      // live watch off or expired
                !retro && !(live && inLive) -> null
                alreadyHave(c) -> null
                else -> Item(c, k, inLive)
            }
        }.sortedByDescending { it.cls.beginMs }
    }
}

/** Headless Panopto captions import into an existing lecture (same flow as the Panopto import screen). */
object PanoptoImporter {
    suspend fun importCaptions(app: LectureApp, lectureId: String, client: MoodleClient, rec: PanoptoRecording) {
        val srtText = client.downloadCaptions(rec)
        val segs = Panopto.parseSrt(srtText)
        if (segs.isEmpty()) error("Captions are empty")
        val repo = app.repo
        val srtFile = File(app.filesDir, "panopto/$lectureId.srt").apply { parentFile?.mkdirs(); writeText(srtText) }
        repo.updateLecture(lectureId) { it.copy(panoptoId = rec.deliveryId, panoptoTitle = rec.title, panoptoUrl = rec.viewerUrl, panoptoSrtPath = srtFile.absolutePath, panoptoDurationMs = (segs.maxOf { s -> s.t1 } * 1000).toLong()) }
        val api = ApiProvider.get(app.settings)
        val l = repo.lecture(lectureId)!!
        val c = repo.course(l.courseId) ?: error("Course missing")
        val courseRemote = c.remoteId ?: api.createCourse(c.name).also { rid -> repo.updateCourse(c.id) { it.copy(remoteId = rid) } }
        val remote = l.remoteId ?: api.createLecture(courseRemote, l.title, l.recordedAtMs).also { rid -> repo.updateLecture(lectureId) { it.copy(remoteId = rid) } }
        JobKick.kick(lectureId)
        val job = api.importTranscript(remote, TranscriptImport("panopto", rec.deliveryId, rec.title, Panopto.languageOf(segs), segs))
            ?: error("Server didn't start a captions job")
        repo.updateLecture(lectureId) { it.copy(jobId = job, liveJob = false, state = LectureState.PROCESSING, error = null, stage = null, stageDetail = null, progress = 0) }
        JobPollWorker.enqueue(app, lectureId, replace = true)
    }
}

/**
 * One auto-sync pass: scan past calendar classes, skip ones already in the notebook (kind + date), find the Panopto
 * recording (date + kind + lecturer from event notes), import captions (else on-phone audio), newest first.
 * Throttled: ≤[MAX_ACTIVE] lectures processing at once per user, ≤[MAX_PER_RUN] new imports per pass.
 */
class AutoSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LectureApp
        val st = AutoSyncStore(app)
        val ent = il.ac.tau.lecturesummarizer.data.Entitlements(app)
        if (!ent.isPro) { status.value = "Pro required"; return Result.success() }
        if (!st.retro && !st.live) return Result.success()
        val allowCell = inputData.getBoolean(KEY_ALLOW_CELL, false)
        if (!allowCell && PanoptoAudioWorker.onCellular(app)) {
            // Metered: ask (throttled), and keep a Wi‑Fi-constrained run queued that auto-resumes.
            enqueueWifi(app)
            if (System.currentTimeMillis() - st.lastCellAsk > 6 * 3600_000L) { st.lastCellAsk = System.currentTimeMillis(); askCellular(app) }
            status.value = "Waiting for Wi‑Fi · ממתין ל-Wi‑Fi"
            return Result.success()
        }
        cancelPrompt(app)
        return runCatching { pass(app, st, allowCell) }.fold({ status.value = it; st.lastRun = it; Result.success() }, { e ->
            val msg = when (e) { is MoodleAuthRequired -> "Moodle sign-in needed"; is PanoptoAuthRequired -> "Panopto sign-in needed"; else -> e.message ?: "Error" }
            status.value = "Stopped: $msg"; st.lastRun = status.value
            if (e is MoodleAuthRequired || e is PanoptoAuthRequired) Result.success() else Result.retry()
        })
    }

    private suspend fun pass(app: LectureApp, st: AutoSyncStore, allowCell: Boolean): String {
        val now = System.currentTimeMillis()
        val from = now - (if (st.retro) st.lookbackDays else AutoSyncPlanner.LIVE_WINDOW_DAYS) * 86_400_000L
        val classes = CalendarResolver.pastClasses(app, app.settings.calendarIds, from, now)
        val repo = app.repo
        fun have(c: TauClass): Boolean {
            val course = repo.courses.value.firstOrNull { c.courseNumber != null && it.courseNumber == c.courseNumber }
                ?: repo.courses.value.firstOrNull { TauNorm(it.name) == TauNorm(c.courseName) } ?: return false
            return repo.lectures.value.any { l -> l.courseId == course.id && RecordingMatcher.sameDay(l.recordedAtMs, c.beginMs) &&
                MoodleMatch.kindFromTitle(l.title).let { k -> k == c.kind || k == ClassKind.UNKNOWN || c.kind == ClassKind.UNKNOWN } }
        }
        val items = AutoSyncPlanner.plan(classes, now, st.retro, st.live, st::status, ::have)
        // mark calendar classes already present so they never re-scan
        classes.filter { st.status(AutoSyncStore.key(it)) == null && have(it) }.forEach { st.setStatus(AutoSyncStore.key(it), "skip") }
        if (items.isEmpty()) return "Up to date · ${classes.size} classes checked"
        val store = MoodleStore(app)
        val client = MoodleClient.of(store.baseUrl)
        val courses = client.courses()
        var started = 0; var waiting = 0; var missing = 0
        for (it in items) {
            val active = repo.lectures.value.count { l -> l.state == LectureState.PROCESSING || PanoptoAudioWorker.extracting[l.id] == true }
            if (active >= MAX_ACTIVE || started >= MAX_PER_RUN) break
            val c = it.cls
            val ranked = MoodleMatch.rank(courses, c.courseNumber, c.kind, c.courseName)
            // Right Moodle course for the kind (…01 lecture / …09 recitation groups) + same-prefix siblings as fallback.
            val pool = (listOfNotNull(MoodleMatch.confident(ranked)) + MoodleMatch.siblings(courses, c.courseNumber) + ranked.take(2).map { r -> r.course }).distinctBy { m -> m.id }
            var hit: Pair<MoodleCourse, PanoptoRecording>? = null
            for (mc in pool) {
                val recs = runCatching { client.panoptoRecordings(mc.id) }.getOrElse { e -> if (e is MoodleAuthRequired) throw e; emptyList() }
                if (recs.any { r -> repo.lectures.value.any { l -> l.panoptoId == r.deliveryId } && RecordingMatcher.score(r, c.beginMs, c.kind, c.lecturer) != null }) { hit = null; st.setStatus(it.key, "skip"); break }
                RecordingMatcher.best(recs, c.beginMs, c.kind, c.lecturer)?.let { r -> hit = mc to r }
                if (hit != null) break
            }
            if (st.status(it.key) == "skip") continue
            val (mc, rec) = hit ?: run {
                if (st.live && it.live) { st.setStatus(it.key, "wait"); waiting++ } else { st.setStatus(it.key, "notfound"); missing++ }
                null
            } ?: continue
            val course = repo.findOrCreateCourse(c.courseName, c.courseNumber)
            val dm = RecordingMatcher.ymdOf(c.beginMs)
            val title = "${c.kind.he.ifEmpty { "שיעור" }} ${"%02d/%02d".format(dm.d, dm.m)}"
            val l = repo.addLecture(course.id, title)
            repo.updateLecture(l.id) { x -> x.copy(recordedAtMs = c.beginMs, panoptoId = rec.deliveryId, panoptoTitle = rec.title, panoptoUrl = rec.viewerUrl) }
            store.setLectureSource(l.id, mc.id, rec.title)
            val caps = runCatching { client.captionStatus(rec) }.getOrDefault(CaptionStatus.UNKNOWN)
            if (caps == CaptionStatus.AUTH) throw PanoptoAuthRequired(rec.viewerUrl)
            val ok = if (caps == CaptionStatus.HAS) runCatching { PanoptoImporter.importCaptions(app, l.id, client, rec) }.onFailure { e -> if (e is PanoptoAuthRequired) throw e }.isSuccess else false
            if (!ok) {
                repo.updateLecture(l.id) { x -> x.copy(panoptoAudio = true, segments = emptyList(), error = null) }
                PanoptoAudioWorker.enqueue(app, l.id, wifiOnly = !allowCell)
            }
            st.setStatus(it.key, "done:${l.id}"); started++
        }
        return "Started $started · waiting $waiting · not found $missing · queue ${items.size}"
    }

    private fun TauNorm(s: String) = il.ac.tau.lecturesummarizer.calendar.TauEventParser.normalize(s)

    companion object {
        const val MAX_ACTIVE = 2
        const val MAX_PER_RUN = 3
        const val KEY_ALLOW_CELL = "allowCell"
        const val ACTION_USE_CELL = "il.ac.tau.lecturesummarizer.AUTOSYNC_USE_CELL"
        const val ACTION_WAIT_WIFI = "il.ac.tau.lecturesummarizer.AUTOSYNC_WAIT_WIFI"
        private const val PROMPT_ID = 0x32001
        val status = mutableStateOf<String?>(null)

        /** Periodic pass (live watch cadence) while either feature is on; prefers unmetered + battery not low. */
        fun schedule(ctx: Context) {
            val st = AutoSyncStore(ctx)
            val wm = WorkManager.getInstance(ctx)
            if ((!st.retro && !st.live) || !il.ac.tau.lecturesummarizer.data.Entitlements(ctx).isPro) { wm.cancelUniqueWork("autosync-periodic"); wm.cancelUniqueWork("autosync-wifi"); return }
            val req = PeriodicWorkRequestBuilder<AutoSyncWorker>(3, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()).build()
            wm.enqueueUniquePeriodicWork("autosync-periodic", ExistingPeriodicWorkPolicy.UPDATE, req)
        }
        fun runNow(ctx: Context, allowCell: Boolean = false) {
            status.value = "Running…"
            WorkManager.getInstance(ctx).enqueueUniqueWork("autosync-now", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<AutoSyncWorker>().setInputData(workDataOf(KEY_ALLOW_CELL to allowCell))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
        fun enqueueWifi(ctx: Context) = WorkManager.getInstance(ctx).enqueueUniqueWork("autosync-wifi", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AutoSyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()).build())

        fun askCellular(ctx: Context) {
            fun pi(action: String, rc: Int) = android.app.PendingIntent.getBroadcast(ctx, PROMPT_ID + rc,
                android.content.Intent(ctx, AutoSyncChoiceReceiver::class.java).setAction(action),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(ctx, LectureApp.CHANNEL_JOBS)
                .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Auto-sync lectures — on mobile data")
                .setContentText("Download new Panopto lectures on mobile data, or wait for Wi‑Fi? · להמשיך בסלולר?")
                .setAutoCancel(true)
                .addAction(0, "Use mobile data", pi(ACTION_USE_CELL, 1))
                .addAction(0, "Wait for Wi‑Fi", pi(ACTION_WAIT_WIFI, 2)).build()
            runCatching { androidx.core.app.NotificationManagerCompat.from(ctx).notify(PROMPT_ID, n) }
        }
        fun cancelPrompt(ctx: Context) = androidx.core.app.NotificationManagerCompat.from(ctx).cancel(PROMPT_ID)
    }
}

/** Use mobile data → one run allowed on cellular; wait → the UNMETERED run stays queued and resumes on Wi‑Fi. */
class AutoSyncChoiceReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: android.content.Intent) {
        AutoSyncWorker.cancelPrompt(ctx)
        if (intent.action == AutoSyncWorker.ACTION_USE_CELL) AutoSyncWorker.runNow(ctx, allowCell = true)
        else AutoSyncWorker.enqueueWifi(ctx)
    }
}
