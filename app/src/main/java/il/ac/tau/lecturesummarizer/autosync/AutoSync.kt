package il.ac.tau.lecturesummarizer.autosync

import il.ac.tau.lecturesummarizer.calendar.CalendarRuleStore
import il.ac.tau.lecturesummarizer.calendar.CalendarRuleProvider
import il.ac.tau.lecturesummarizer.calendar.CalendarRuleApplier
import il.ac.tau.lecturesummarizer.calendar.CalendarRules
import il.ac.tau.lecturesummarizer.sources.*
import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.work.*
import il.ac.tau.lecturesummarizer.data.allSlides
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
    val prefs = ctx.getSharedPreferences("autosync", Context.MODE_PRIVATE)
    private val p get()=prefs
    var retro: Boolean get() = p.getBoolean("retro", false); set(v) = p.edit().putBoolean("retro", v).apply()
    var live: Boolean get() = p.getBoolean("live", false); set(v) = p.edit().putBoolean("live", v).apply()
    /** Retro look-back (days). */
    var lookbackDays: Int get() = p.getInt("lookback", 120); set(v) = p.edit().putInt("lookback", v).apply()
    /** Last time we asked to run on mobile data (throttle: ≤1 ask / 6 h). */
    var lastCellAsk: Long get() = p.getLong("cellAsk", 0); set(v) = p.edit().putLong("cellAsk", v).apply()
    var paused: Boolean get() = p.getBoolean("paused", false); set(v) = p.edit().putBoolean("paused", v).apply()
    /** User said "run anyway" on battery until this time. */
    var batteryOkUntil: Long get() = p.getLong("battOk", 0); set(v) = p.edit().putLong("battOk", v).apply()
    /** Background sync stops below this battery % (not charging). User-chosen; default 30, clamped 15–60. */
    var batteryHardMin: Int get() = p.getInt("battMin", BatteryGate.DEFAULT_MIN).coerceIn(BatteryGate.MIN_FLOOR, BatteryGate.MAX_FLOOR)
        set(v) = p.edit().putInt("battMin", v.coerceIn(BatteryGate.MIN_FLOOR, BatteryGate.MAX_FLOOR)).apply()
    var lastBatteryAsk: Long get() = p.getLong("battAsk", 0); set(v) = p.edit().putLong("battAsk", v).apply()
    var lastRun: String? get() = p.getString("lastRun", null); set(v) = p.edit().putString("lastRun", v).apply()

    /** eventKey → status: "done:<lectureId>" | "skip" | "notfound" | "wait" (live: recording not yet on Panopto). */
    /** Auto-attach Moodle slides when the match is unambiguous (default on). */
    var autoAttach: Boolean get() = p.getBoolean("autoAttach", true); set(v) { p.edit().putBoolean("autoAttach", v).apply() }
    fun status(key: String): String? = p.getString("st:$key", null)
    fun setStatus(key: String, v: String) = synchronized(STATE_LOCK){p.edit().putString("st:$key", v).apply()}
    fun counts(): Map<String, Int> = synchronized(STATE_LOCK){p.all.filterKeys { it.startsWith("st:") }.values.groupingBy { (it as String).substringBefore(':') }.eachCount()}
    /** All rows are backed by the same status keys as the counts. Legacy keys retain course/date fallback. */
    fun entries(repo:il.ac.tau.lecturesummarizer.data.Repository):List<SyncQueueEntry> = p.all.filterKeys{it.startsWith("st:")}.mapNotNull{(key,value)->
        val raw=value as? String ?: return@mapNotNull null;val eventKey=key.removePrefix("st:");val parts=eventKey.split('|')
        val lecture=if(raw.startsWith("done:"))repo.lecture(raw.substringAfter(':'))else null
        val meta=p.getString("meta:$eventKey",null)?.let{runCatching{JSONObject(it)}.getOrNull()}
        val courseKey=parts.dropLast(2).joinToString("|")
        val course=lecture?.let{repo.course(it.courseId)?.name} ?: meta?.optString("course") ?: notFoundItems().firstOrNull{it.key==eventKey}?.course ?: repo.courses.value.firstOrNull{it.courseNumber==courseKey || TauEventNorm(it.name)==courseKey}?.name ?: courseKey
        SyncQueueEntry(eventKey,SyncCategories.category(raw,lecture),course,lecture?.title ?: meta?.optString("title").orEmpty(),lecture?.recordedAtMs ?: meta?.optLong("start") ?: parts.lastOrNull()?.toLongOrNull() ?: 0L,lecture)
    }.sortedByDescending{it.start}
    fun track(c:TauClass,title:String=c.courseName){p.edit().putString("meta:${key(c)}",JSONObject().put("course",c.courseName).put("title",title).put("start",c.beginMs).toString()).apply()}
    /** Only unclaimed waiting/missing events can be explicitly rechecked. Active work is never unclaimed. */
    fun retry(key:String):Boolean{ synchronized(STATE_LOCK){val raw=status(key) ?: return false;if(!raw.startsWith("wait") && !raw.startsWith("notfound"))return false;p.edit().remove("st:$key").apply();dropNotFound(key);return true} }
    /** Cached Panopto session start per delivery id (ms; -1 = DeliveryInfo had none). */
    fun sessionStart(id: String): Long? = p.getLong("ss:$id", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
    fun setSessionStart(id: String, ms: Long?) = p.edit().putLong("ss:$id", ms ?: -1L).apply()
    /** Short "course dd/mm" sample of the last pass's not-found classes. */
    var notFoundSample: String? get() = p.getString("nfSample", null); set(v) = p.edit().putString("nfSample", v).apply()
    /** Rolling list (newest first, capped [NF_MAX]) of individual not-found classes for the Settings disclosure. */
    fun notFoundItems(): List<NotFoundItem> = (p.getString("nfItems", null) ?: "").lines().mapNotNull { NotFoundItem.decode(it) }
    fun addNotFound(i: NotFoundItem) { synchronized(STATE_LOCK){
        val list = listOf(i) + notFoundItems().filter { it.key != i.key }
        p.edit().putString("nfItems", list.take(NF_MAX).joinToString("\n") { it.encode() }).apply()
    } }
    fun dropNotFound(key: String) { synchronized(STATE_LOCK){ val l = notFoundItems(); if (l.any { it.key == key }) p.edit().putString("nfItems", l.filter { it.key != key }.joinToString("\n") { it.encode() }).apply() } }
    /** Permanently dismiss one not-found class: final status, never retried (also survives "Retry not found"). */
    fun dismissNotFound(key: String) { synchronized(STATE_LOCK){ setStatus(key, "dismissed"); dropNotFound(key) } }
    fun undismissAll() { synchronized(STATE_LOCK){ val e = p.edit(); p.all.filter { it.key.startsWith("st:") && it.value == "dismissed" }.keys.forEach { e.remove(it) }; e.apply() } }

    /** Whitelist mode: only calendar classes matching one of [whitelist] (course name / number substring, case-insensitive) are imported. */
    var whitelistMode: Boolean get() = p.getBoolean("wlMode", false); set(v) = p.edit().putBoolean("wlMode", v).apply()
    var whitelist: List<String>
        get() = (p.getString("wl", null) ?: "").lines().map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = p.edit().putString("wl", v.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")).apply()
    fun allowed(c: TauClass): Boolean = !whitelistMode || AutoSyncPlanner.whitelisted(c, whitelist)

    fun resetNotFound() { synchronized(STATE_LOCK){ p.edit().remove("nfItems").apply(); val e = p.edit(); p.all.filter { it.key.startsWith("st:") && (it.value as? String)?.startsWith("notfound") == true }.keys.forEach { e.remove(it) }; e.apply() } }

    companion object {
        private val STATE_LOCK=Any()
        const val NF_MAX = 40
        fun key(c: TauClass) = "${c.courseNumber ?: TauEventNorm(c.courseName)}|${c.kind}|${c.beginMs}"
        private fun TauEventNorm(s: String) = il.ac.tau.lecturesummarizer.calendar.TauEventParser.normalize(s)
    }
}

/** One class whose Panopto recording couldn't be found. */
data class NotFoundItem(val key: String, val course: String, val kind: String, val classMs: Long, val checkedMs: Long) {
    fun encode() = listOf(key, course, kind, classMs, checkedMs).joinToString("\t") { it.toString().replace('\t', ' ').replace('\n', ' ') }
    companion object {
        fun decode(s: String): NotFoundItem? = s.split('\t').takeIf { it.size == 5 }?.let { NotFoundItem(it[0], it[1], it[2], it[3].toLongOrNull() ?: 0, it[4].toLongOrNull() ?: 0) }
    }
}

/** Pure planning: which classes need work, newest first (rolling lectures beat the retro backlog). */
object AutoSyncPlanner {
    /** New classes are picked up by the live watch for this many days after they happen (some courses upload ~10 days late). */
    const val LIVE_WINDOW_DAYS = 10
    /** Within this many days a live class is re-checked every pass (~3 h); after that, once a day until [LIVE_WINDOW_DAYS]. */
    const val LIVE_FRESH_DAYS = 2

    fun whitelisted(c: TauClass, patterns: List<String>): Boolean = patterns.any { p ->
        val q = p.trim().lowercase(); q.isNotEmpty() && (c.courseName.lowercase().contains(q) || (c.courseNumber?.replace("-", "")?.contains(q.replace("-", "")) == true))
    }
    /** Once enrolled, a class whose recording isn't on Panopto yet stays on a pending watch this long (uploads take 24–48 h, sometimes more). */
    const val PENDING_MAX_DAYS = 21
    /** After the live window, pending classes are polled once a day. */
    const val PENDING_POLL_MS = 20 * 3600_000L
    /** notfound classes are re-scanned this often (recordings get uploaded/renamed late; matching improves). */
    const val NOTFOUND_RETRY_MS = 20 * 3600_000L
    fun lastCheck(st: String?): Long = st?.takeIf { it.startsWith("wait") || it.startsWith("notfound") }?.substringAfter(':', "0")?.toLongOrNull() ?: 0L
    data class Item(val cls: TauClass, val key: String, val live: Boolean)

    /** Final guard right before creating a lecture: the class key must not be already done/skipped/claimed, and no notebook lecture may carry this Panopto delivery. */
    fun mayCreate(status: String?, deliveryId: String, existingPanoptoIds: Collection<String?>): Boolean =
        !(status != null && (status == "skip" || status.startsWith("done") || status.startsWith("claim"))) && existingPanoptoIds.none { it == deliveryId }

    fun plan(classes: List<TauClass>, nowMs: Long, retro: Boolean, live: Boolean, status: (String) -> String?,
             alreadyHave: (TauClass) -> Boolean): List<Item> {
        val liveFrom = nowMs - LIVE_WINDOW_DAYS * 86_400_000L
        val freshFrom = nowMs - LIVE_FRESH_DAYS * 86_400_000L
        val pendingFrom = nowMs - PENDING_MAX_DAYS * 86_400_000L
        return classes.filter { it.endMs <= nowMs }.mapNotNull { c ->
            val k = AutoSyncStore.key(c)
            val inLive = c.beginMs >= liveFrom
            val st = status(k)
            val waiting = st != null && st.startsWith("wait")
            val notFound = st != null && st.startsWith("notfound")
            val pendingDue = live && waiting && c.beginMs >= pendingFrom && (c.beginMs >= freshFrom || nowMs - lastCheck(st) >= PENDING_POLL_MS)
            when {
                notFound -> if ((retro || (live && inLive)) && nowMs - lastCheck(st) >= NOTFOUND_RETRY_MS && !alreadyHave(c)) Item(c, k, inLive) else null
                st != null && !waiting -> null                 // done / skip are final
                waiting && !pendingDue -> null                 // watch off, expired, or polled today already
                waiting -> Item(c, k, true)
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
        val info = runCatching { client.deliveryInfo(rec) }.getOrNull()
        val videoSec = info?.durationSec?.takeIf { it > 0 }
        val videoMs = ((videoSec ?: 0.0) * 1000).toLong()
        val srtText = client.downloadCaptions(rec)
        val segs = Panopto.parseSrt(srtText)
        val repo = app.repo
        if (!CaptionQuality.captionsUsable(segs, videoSec)) {
            repo.updateLecture(lectureId) {
                it.copy(panoptoId = rec.deliveryId, panoptoTitle = rec.title, panoptoUrl = rec.viewerUrl,
                    panoptoSrtPath = null, panoptoAudio = true, panoptoDurationMs = videoMs.takeIf { d -> d > 0 } ?: it.panoptoDurationMs,
                    segments = emptyList(), error = null)
            }
            il.ac.tau.lecturesummarizer.panopto.PanoptoAudioWorker.enqueue(app, lectureId, wifiOnly = true)
            return
        }
        val srtFile = File(app.filesDir, "panopto/$lectureId.srt").apply { parentFile?.mkdirs(); writeText(srtText) }
        repo.updateLecture(lectureId) { it.copy(panoptoId = rec.deliveryId, panoptoTitle = rec.title, panoptoUrl = rec.viewerUrl, panoptoSrtPath = srtFile.absolutePath, panoptoDurationMs = videoMs.takeIf { d -> d > 0 } ?: (segs.maxOf { s -> s.t1 } * 1000).toLong()) }
        val api = ApiProvider.get(app.settings)
        val l = repo.lecture(lectureId)!!
        val c = repo.course(l.courseId) ?: error("Course missing")
        val courseRemote = c.remoteId ?: api.createCourse(c.name).also { rid -> repo.updateCourse(c.id) { it.copy(remoteId = rid) } }
        val remote = l.remoteId ?: api.createLecture(courseRemote, l.title, l.recordedAtMs).also { rid -> repo.updateLecture(lectureId) { it.copy(remoteId = rid) } }
        JobKick.kick(lectureId)
        val job = api.importTranscript(remote, TranscriptImport("panopto", rec.deliveryId, rec.title, Panopto.languageOf(segs), segs, videoDurationSec = videoSec))
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
        if (st.paused) { status.value = "Paused · מושהה"; return Result.success() }
        if (BatteryGate.tooLow(app)) { status.value = "Battery below ${BatteryGate.floor(app)}% — waiting for charger"; return Result.success() }
        if (BatteryGate.needsAsk(app)) {
            if (System.currentTimeMillis() - st.lastBatteryAsk > 3 * 3600_000L) { st.lastBatteryAsk = System.currentTimeMillis(); BatteryGate.ask(app) }
            status.value = "Battery below ${BatteryGate.ASK_BELOW}% — asked to confirm"
            return Result.success()
        }
        val allowCell = inputData.getBoolean(KEY_ALLOW_CELL, false)
        if (!allowCell && PanoptoAudioWorker.onCellular(app)) {
            // Metered: ask (throttled), and keep a Wi‑Fi-constrained run queued that auto-resumes.
            enqueueWifi(app)
            if (System.currentTimeMillis() - st.lastCellAsk > 6 * 3600_000L) { st.lastCellAsk = System.currentTimeMillis(); askCellular(app) }
            status.value = "Waiting for Wi‑Fi · ממתין ל-Wi‑Fi"
            return Result.success()
        }
        cancelPrompt(app)
        // One pass at a time per process: periodic + drain + Run now / Wi‑Fi resume can otherwise overlap and twin-create lectures.
        if (!PASS_LOCK.tryLock()) { return Result.success() }
        return runCatching { try { pass(app, st, allowCell) } finally { PASS_LOCK.unlock() } }.fold({ status.value = it; st.lastRun = it; Result.success() }, { e ->
            val msg = when (e) { is MoodleAuthRequired -> "Moodle sign-in needed"; is PanoptoAuthRequired -> "Panopto sign-in needed"; else -> e.message ?: "Error" }
            status.value = "Stopped: $msg"; st.lastRun = status.value
            if (e is MoodleAuthRequired || e is PanoptoAuthRequired) Result.success() else Result.retry()
        })
    }

    private suspend fun pass(app: LectureApp, st: AutoSyncStore, allowCell: Boolean): String {
        val now = System.currentTimeMillis()
        val from = now - maxOf(if (st.retro) st.lookbackDays else 0, if (st.live) AutoSyncPlanner.PENDING_MAX_DAYS else 0) * 86_400_000L
        val ruleStore=CalendarRuleStore(app)
        val customRules=app.settings.calendarRulesEnabled && ruleStore.prefs.getBoolean("configured",false)
        val matchedEvents=if(customRules)CalendarRules.preview(ruleStore.rules(),CalendarRuleProvider.read(app,ruleStore.sources(),now).events).filter{it.event.start>=from && it.event.end<=now && (il.ac.tau.lecturesummarizer.calendar.CalendarSeriesSelection.pickerRule(it.rule) || (ruleStore.autoApprove && !ruleStore.undone(it)) || CalendarRuleApplier(app).current(it))}else emptyList()
        val classes=if(customRules)matchedEvents.map{m->TauClass(m.rule.targetCourse,MoodleMatch.kindFromTitle(m.event.title),app.repo.courses.value.firstOrNull{it.name==m.rule.targetCourse}?.courseNumber,null,null,m.event.start,m.event.end,false)}
            else CalendarResolver.pastClasses(app,app.settings.calendarIds,from,now).filter(st::allowed)
        classes.forEach{c->st.track(c,matchedEvents.firstOrNull{it.rule.targetCourse==c.courseName && it.event.start==c.beginMs}?.event?.title ?: c.courseName)}
        val mappingStore=SourceMappingStore(app)
        val sourceRegistry=DataSourceRegistry()
        val sourceCache=java.util.concurrent.ConcurrentHashMap<SourceConfig,SourceListing>()
        val repo = app.repo
        fun have(c: TauClass): Boolean {
            val course = repo.courses.value.firstOrNull { c.courseNumber != null && it.courseNumber == c.courseNumber }
                ?: repo.courses.value.firstOrNull { TauNorm(it.name) == TauNorm(c.courseName) } ?: return false
            return repo.lectures.value.any { l -> !(l.state==LectureState.DRAFT && l.segments.isEmpty() && l.panoptoId==null && l.remoteId==null && l.jobId==null && l.pdfPath==null && l.allSlides.isEmpty()) && l.courseId == course.id && RecordingMatcher.sameDay(l.recordedAtMs, c.beginMs) &&
                MoodleMatch.kindFromTitle(l.title).let { k -> k == c.kind || k == ClassKind.UNKNOWN || c.kind == ClassKind.UNKNOWN } }
        }
        val items = AutoSyncPlanner.plan(classes, now, st.retro, st.live, st::status, ::have)
        // mark calendar classes already present so they never re-scan
        classes.filter { st.status(AutoSyncStore.key(it)) == null && have(it) }.forEach { st.setStatus(AutoSyncStore.key(it), "skip") }
        if (items.isEmpty()) return "Up to date · ${classes.size} classes checked"
        val store = MoodleStore(app)
        val client = MoodleClient.of(store.baseUrl)
        val courses by lazy { client.courses() }
        var started = 0; var waiting = 0; var missing = 0; var dIFetches = 0
        val nfSample = mutableListOf<String>()
        // Moodle's Panopto list carries no dates → fill session start from DeliveryInfo (cached per delivery id) for recordings whose title lacks the day.
        fun withDates(recs: List<PanoptoRecording>, beginMs: Long): List<PanoptoRecording> = recs.map { r ->
            if (!RecordingMatcher.needsSessionDate(r, beginMs)) return@map r
            val cached = st.sessionStart(r.deliveryId)
            if (cached != null) return@map if (cached > 0) r.copy(startMs = cached) else r
            if (dIFetches >= MAX_DELIVERY_INFO_PER_RUN) return@map r
            dIFetches++
            val ms = runCatching { client.deliveryInfo(r).sessionStartMs }.getOrElse { e -> if (e is PanoptoAuthRequired) throw e; return@map r }
            st.setSessionStart(r.deliveryId, ms)
            if (ms != null) r.copy(startMs = ms) else r
        }
        for (it in items) {
            if (st.paused || BatteryGate.tooLow(app)) break
            val active = repo.lectures.value.count { l -> l.state == LectureState.PROCESSING || PanoptoAudioWorker.extracting[l.id] == true }
            if (active >= MAX_ACTIVE || started >= MAX_PER_RUN) break
            val c = it.cls
            if(st.status(it.key)=="dismissed")continue
            val binding=mappingStore.forName(app,c.courseName)
            val explicit=binding?.any{!it.legacy}==true
            if(customRules && !explicit && binding?.any{it.courseId!=null}!=true){
                st.setStatus(it.key,"notfound:$now");st.addNotFound(NotFoundItem(it.key,c.courseName,c.kind.name,c.beginMs,now));missing++;continue
            }
            var hit:Pair<MoodleCourse,PanoptoRecording>?=null
            if(explicit){
                val eventMatch=matchedEvents.firstOrNull{it.rule.targetCourse==c.courseName && it.event.start==c.beginMs}
                val sourceEvent=SourceEvent(eventMatch?.event?.title ?: c.courseName,c.beginMs,c.endMs,eventMatch?.event?.zone ?: "Asia/Jerusalem")
                val pin=eventMatch?.let{mappingStore.pinned(it.event.key,it.event.start)}
                val panConfig=binding!!.firstOrNull{it.providerId=="panopto"}
                val picked=pin ?: panConfig?.let{cfg->
                    val provider=sourceRegistry.provider(cfg.providerId) ?: error("unsupported_provider")
                    val listing=sourceCache[cfg] ?: provider.items(cfg).also{sourceCache[cfg]=it}
                    if(listing.partial)null else SourceMatcher.clearRecording(provider.matcher.rank(sourceEvent,cfg,listing.items))
                }
                if(picked!=null){
                    if(repo.lectures.value.any{it.panoptoId==picked.id})st.setStatus(it.key,"skip")
                    else hit=MoodleCourse(binding.firstOrNull{it.providerId=="moodle"}?.courseId ?: panConfig?.courseId ?: 0,c.courseName,"","") to PanoptoRecording(picked.id,picked.title,picked.url,picked.start)
                }
            }else{
                // Migrated legacy defaults preserve existing TAU detection. Explicit per-course mappings never fall back here.
                val ranked=MoodleMatch.rank(courses,c.courseNumber,c.kind,c.courseName)
                val mapped=binding?.firstOrNull{it.providerId=="moodle"}?.courseId?.let{id->courses.firstOrNull{it.id==id}}
                val pool=(listOfNotNull(mapped,MoodleMatch.confident(ranked))+MoodleMatch.siblings(courses,c.courseNumber)+ranked.take(2).map{r->r.course}).distinctBy{m->m.id}
                for(mc in pool){
                    val recs=runCatching{client.panoptoRecordings(mc.id)}.getOrElse{e->if(e is MoodleAuthRequired)throw e;emptyList()}
                    if(recs.any{r->repo.lectures.value.any{l->l.panoptoId==r.deliveryId} && RecordingMatcher.score(r,c.beginMs,c.kind,c.lecturer)!=null}){st.setStatus(it.key,"skip");break}
                    (RecordingMatcher.best(recs,c.beginMs,c.kind,c.lecturer) ?: RecordingMatcher.best(withDates(recs,c.beginMs),c.beginMs,c.kind,c.lecturer))?.let{r->hit=mc to r}
                    if(hit!=null)break
                }
            }
            if(st.status(it.key)=="dismissed")continue
            if (st.status(it.key) == "skip") { st.dropNotFound(it.key); continue }
            val (mc, rec) = hit ?: run {
                if (st.live && it.live) { st.setStatus(it.key, "wait:$now"); waiting++ } else {
                    st.setStatus(it.key, "notfound:$now"); missing++
                    st.addNotFound(NotFoundItem(it.key, c.courseName, c.kind.he, c.beginMs, now))
                    if (nfSample.size < 6) RecordingMatcher.ymdOf(c.beginMs).let { d -> nfSample += "${c.courseName.take(24)} ${"%02d/%02d".format(d.d, d.m)}" }
                }
                null
            } ?: continue
            st.dropNotFound(it.key)
            if (have(c) || !AutoSyncPlanner.mayCreate(st.status(it.key), rec.deliveryId, repo.lectures.value.map { l -> l.panoptoId })) { st.setStatus(it.key, "skip"); continue }
            st.setStatus(it.key, "claim:$now")
            val course = repo.findOrCreateCourse(c.courseName, c.courseNumber)
            val dm = RecordingMatcher.ymdOf(c.beginMs)
            val title = app.getString(when(c.kind){ClassKind.RECITATION->il.ac.tau.lecturesummarizer.R.string.generated_recitation;else->il.ac.tau.lecturesummarizer.R.string.generated_lecture},"%02d/%02d".format(dm.d,dm.m))
            val l = repo.addLecture(course.id, title) // Only reached after a real recording match; never reuse an unproven edited placeholder.
            st.setStatus(it.key, "done:${l.id}")
            repo.updateLecture(l.id) { x -> x.copy(recordedAtMs = c.beginMs, panoptoId = rec.deliveryId, panoptoTitle = rec.title, panoptoUrl = rec.viewerUrl) }
            if(mc.id>0)store.setLectureSource(l.id, mc.id, rec.title,if(explicit)binding?.firstOrNull{it.providerId=="moodle"}?.baseUrl else null)
            // Unambiguous Moodle deck before captions/audio so figures land in the first PDF.
            // Post-DONE term search (SlideMatchWorker) only suggests; never auto-attaches.
            if(st.autoAttach && mc.id>0) runCatching{
                val site=store.lectureBase(l.id)
                val slideClient=if(site==store.baseUrl)client else MoodleClient.of(site)
                autoAttachSlides(app,l.id,slideClient,store,mc,rec.title,dm.d to dm.m)
            }.onFailure{e->if(e is MoodleAuthRequired)throw e}
            val caps = runCatching { client.captionStatus(rec) }.getOrDefault(CaptionStatus.UNKNOWN)
            if (caps == CaptionStatus.AUTH) throw PanoptoAuthRequired(rec.viewerUrl)
            val ok = if (caps == CaptionStatus.HAS) runCatching { PanoptoImporter.importCaptions(app, l.id, client, rec) }.onFailure { e -> if (e is PanoptoAuthRequired) throw e }.isSuccess else false
            if (!ok) {
                repo.updateLecture(l.id) { x -> x.copy(panoptoAudio = true, segments = emptyList(), error = null) }
                PanoptoAudioWorker.enqueue(app, l.id, wifiOnly = !allowCell)
            }
            st.setStatus(it.key, "done:${l.id}"); started++
        }
        if (missing > 0 || nfSample.isNotEmpty()) st.notFoundSample = nfSample.joinToString(" · ").ifEmpty { null }
        // Drain the retro backlog: a full batch → chain another pass soon instead of waiting for the 3 h periodic run.
        // Stops when a pass makes no progress (started == 0) or after MAX_DRAIN_CHAIN passes; the worker re-checks pause/battery/Wi‑Fi each time.
        val remaining = items.size - started - missing - waiting
        if (started > 0 && remaining > 0 && !st.paused) enqueueDrain(app, allowCell, inputData.getInt(KEY_DRAIN, 0) + 1)
        return "Started $started · waiting $waiting · not found $missing · queue ${items.size}"
    }

    /** Near-certain Moodle PDF/PPTX only (MoodleMatch.autoPick). Ambiguous → leave for post-ASR suggestions. */
    private fun autoAttachSlides(app: LectureApp, lectureId: String, client: MoodleClient, store: MoodleStore, mc: MoodleCourse, recTitle: String?, dayMonth: Pair<Int, Int>) {
        if (mc.id <= 0) return
        val site = store.lectureBase(lectureId)
        val files = client.sections(mc.id).flatMap { it.files }.filter { !store.isUsed(it.cmid) }
            .filter { f -> MoodleMatch.matchesRecording(f.name, recTitle) || MoodleMatch.suggested(f.name, dayMonth) }
            .take(6).map { f -> f to (client.fileName(f) ?: f.name) }
        val pick = MoodleMatch.autoPick(files, recTitle, dayMonth) ?: return
        if (il.ac.tau.lecturesummarizer.slides.SlideChoices(app).isManual(lectureId) || !app.repo.lecture(lectureId)?.allSlides.isNullOrEmpty()) return
        val file = store.cached(pick.cmid, site) ?: client.download(pick, store.cacheDir(site)).also { store.putCached(pick.cmid, it, site) }
        store.markUsed(pick.cmid)
        il.ac.tau.lecturesummarizer.slides.SlideChoices(app).auto(lectureId, file.name, listOf("same_course", "lecture_number"))
        il.ac.tau.lecturesummarizer.slides.SlideChoices(app).phase(lectureId, "done")
        val uri = androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".files", file)
        app.repo.updateLecture(lectureId) { x ->
            if (x.slidesUri == null) x.copy(slidesUri = uri.toString())
            else x.copy(extraSlides = (x.extraSlides + uri.toString()).distinct())
        }
    }

    private fun TauNorm(s:String)=il.ac.tau.lecturesummarizer.calendar.TauEventParser.normalize(s)

    companion object {
        private val PASS_LOCK = kotlinx.coroutines.sync.Mutex()
        const val MAX_ACTIVE = 2
        const val MAX_PER_RUN = 3
        const val MAX_DELIVERY_INFO_PER_RUN = 150
        const val MAX_DRAIN_CHAIN = 30
        const val DRAIN_DELAY_MIN = 3L
        const val KEY_DRAIN = "drain"

        /** One-time follow-up pass; delayed so the ≤2 concurrent processing slots can free up. Wi‑Fi unless the user allowed cellular. */
        fun enqueueDrain(ctx: Context, allowCell: Boolean, n: Int) {
            if (n > MAX_DRAIN_CHAIN) return
            WorkManager.getInstance(ctx).enqueueUniqueWork("autosync-drain", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<AutoSyncWorker>().setInitialDelay(DRAIN_DELAY_MIN, TimeUnit.MINUTES)
                    .setInputData(workDataOf(KEY_ALLOW_CELL to allowCell, KEY_DRAIN to n))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(if (allowCell) NetworkType.CONNECTED else NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true).build()).build())
        }
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
            val req = PeriodicWorkRequestBuilder<AutoSyncWorker>(15, TimeUnit.MINUTES) // WorkManager minimum periodic interval
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
                .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(ctx.getString(il.ac.tau.lecturesummarizer.R.string.auto_sync_mobile_title))
                .setContentText(ctx.getString(il.ac.tau.lecturesummarizer.R.string.auto_sync_mobile_body))
                .setAutoCancel(true)
                .addAction(0, ctx.getString(il.ac.tau.lecturesummarizer.R.string.use_mobile), pi(ACTION_USE_CELL, 1))
                .addAction(0, ctx.getString(il.ac.tau.lecturesummarizer.R.string.wait_wifi), pi(ACTION_WAIT_WIFI, 2)).build()
            try { androidx.core.app.NotificationManagerCompat.from(ctx).notify(PROMPT_ID, n) } catch (_: SecurityException) { /* Notifications disabled by the user. */ }
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
