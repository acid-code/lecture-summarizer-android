package il.ac.tau.lecturesummarizer.panopto

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.app.NotificationCompat
import androidx.work.*
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.data.LectureState
import il.ac.tau.lecturesummarizer.data.Segment
import il.ac.tau.lecturesummarizer.moodle.*
import il.ac.tau.lecturesummarizer.upload.UploadWorker
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * No-captions fallback: phone pulls the lowest HLS variant (muxed A/V) with the user's Panopto session,
 * in ~10 min slices (download slice → decode audio → append to one mono AAC .m4a → delete slice),
 * then hands the single file to [UploadWorker] as segment 0 → normal ASR + summary. Vercel never touches Panopto.
 */
class PanoptoAudioWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LectureApp
        val repo = app.repo
        val id = inputData.getString(KEY_LECTURE) ?: return Result.failure()
        val l = repo.lecture(id) ?: return Result.failure()
        val pid = l.panoptoId ?: return Result.failure()
        val outFile = File(app.filesDir, "panopto/$id.m4a")
        // Finished extraction (duration recorded) → just (re)upload. Partial runs restart extraction from scratch.
        if (l.panoptoDurationMs > 0 && l.segments.isNotEmpty() && l.segments.all { File(it.path).exists() }) { UploadWorker.enqueue(app, id); return Result.success() }
        if (il.ac.tau.lecturesummarizer.autosync.QueueControl.isPaused(app)) return Result.success() // Resume re-enqueues
        if (il.ac.tau.lecturesummarizer.autosync.BatteryGate.tooLow(app)) return Result.retry()
        val allowCell = inputData.getBoolean(KEY_ALLOW_CELL, false)
        if (!allowCell && onCellular(app)) { askCellular(app, id); return Result.retry() } // constraint says UNMETERED; belt & braces
        cancelPrompt(app, id)
        // Watch the network while we run: Wi‑Fi → cellular without consent → prompt; WorkManager (UNMETERED) stops us,
        // completed ~10 min parts are kept, and the work resumes by itself when Wi‑Fi returns.
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: android.net.Network, caps: NetworkCapabilities) {
                if (!allowCell && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) askCellular(app, id)
            }
        }
        runCatching { cm?.registerDefaultNetworkCallback(cb) }
        try { setForeground(info(applicationContext.getString(il.ac.tau.lecturesummarizer.R.string.audio_download_start), 0)) } catch (_: Exception) {}
        status[id] = "0"
        repo.updateLecture(id) { it.copy(state = LectureState.UPLOADING, error = null, stageDetail = applicationContext.getString(il.ac.tau.lecturesummarizer.R.string.panopto_audio_downloading), progress = 0) }
        val tmpDir = File(app.cacheDir, "panopto-$id").apply { deleteRecursively(); mkdirs() }
        var tx: AudioTranscoder? = null
        // Resume: keep the contiguous run of already-extracted parts (0..k-1) whose files still exist.
        val kept = l.segments.sortedBy { it.seq }.withIndex().takeWhile { (i, sg) -> sg.seq == i && File(sg.path).exists() && sg.path.endsWith("$id-$i.m4a") }.map { it.value }
        repo.updateLecture(id) { it.copy(segments = kept, panoptoDurationMs = 0) }
        extracting[id] = true
        return try {
            val client = MoodleClient.of(MoodleStore(app).baseUrl)
            val rec = PanoptoRecording(pid, l.panoptoTitle ?: "", l.panoptoUrl ?: Panopto.HOST)
            val di = client.deliveryInfo(rec)  // fresh signed URLs
            outFile.parentFile?.mkdirs(); outFile.delete()
            val t = AudioTranscoder(outFile).also { tx = it }
            val total = di.durationSec.takeIf { it > 0 } ?: 1.0
            suspend fun report(done: Double) {
                val pct = (done / total * 100).toInt().coerceIn(0, 99)
                if (status[id] != pct.toString()) {
                    status[id] = pct.toString()
                    repo.updateLecture(id) { it.copy(progress = pct, stageDetail = "Panopto audio $pct% · ${fmt(done)} / ${fmt(total)}") }
                    runCatching { setForeground(info(applicationContext.getString(il.ac.tau.lecturesummarizer.R.string.audio_download_progress, pct,il.ac.tau.lecturesummarizer.ui.ltr(fmt(done)),il.ac.tau.lecturesummarizer.ui.ltr(fmt(total))), pct)) }
                }
            }
            val stream = PanoptoMedia.pickStream(di)
            if (stream != null) {
                val master = client.mediaText(stream.url)
                val mediaUrl = if (master.contains("#EXTINF")) stream.url
                    else PanoptoMedia.audioRendition(master, stream.url)
                        ?: PanoptoMedia.parseMaster(master, stream.url).firstOrNull()?.url
                        ?: stream.variants.minByOrNull { it.bandwidth }?.url
                        ?: error("No playable HLS variant")
                val media = PanoptoMedia.parseMedia(client.mediaText(mediaUrl), mediaUrl)
                if (media.segments.isEmpty()) error("Panopto playlist is empty")
                val init = media.initUrl?.let { u -> retry { client.openMedia(u, media.initRange) { it.readBytes() } } }
                val wins = PanoptoMedia.windows(media.segments)
                parts[id] = wins.size
                t.abort(); tx = null // HLS: one .m4a per ~10 min slice, each uploaded as soon as it's ready (ASR starts mid-download)
                var doneSec = kept.sumOf { it.durationMs } / 1000.0
                if (kept.isNotEmpty()) report(doneSec)
                for ((wi, win) in wins.withIndex()) {
                    if (wi < kept.size) continue // already extracted before a pause
                    if (isStopped) throw CancellationException()
                    if (il.ac.tau.lecturesummarizer.autosync.BatteryGate.tooLow(app)) return Result.retry() // parts kept
                    val slice = File(tmpDir, "w$wi.bin")
                    slice.outputStream().buffered().use { os ->
                        init?.let(os::write)
                        // Panopto fMP4: one file + contiguous byte ranges → one ranged GET per ~10 min slice.
                        val merged = PanoptoMedia.mergeRanges(win)
                        if (merged != null) retry { client.openMedia(win[0].url, merged) { it.copyTo(os) } }
                        else for (seg in win) retry { client.openMedia(seg.url, seg.range) { it.copyTo(os) } }
                    }
                    val partFile = File(app.filesDir, "panopto/$id-$wi.m4a")
                    val pt = AudioTranscoder(partFile).also { tx = it }
                    pt.appendSlice(slice); slice.delete()
                    val d = pt.finish(); tx = null
                    if (d >= 1) {
                        val startMs = l.recordedAtMs + (doneSec * 1000).toLong()
                        repo.updateLecture(id) { it.copy(segments = it.segments + Segment(wi, partFile.absolutePath, startMs, (d * 1000).toLong())) }
                        UploadWorker.enqueue(app, id, live = true) // upload this part now; server live job transcribes it
                    } else partFile.delete()
                    doneSec += d
                    report(doneSec)
                }
                if (doneSec < 5) error("Extracted audio is empty")
                repo.updateLecture(id) { it.copy(panoptoDurationMs = (doneSec * 1000).toLong(), progress = 0, stageDetail = null) }
            } else {
                // Podcast MP4 has moov at the end → must be fully downloaded before demuxing.
                val url = di.podcastUrl ?: error("This recording has no downloadable stream")
                val f = File(tmpDir, "podcast.mp4")
                retry { f.outputStream().buffered().use { os -> client.openMedia(url) { it.copyTo(os) } } }
                t.appendSlice(f)
                f.delete()
            }
            tx?.let { t2 ->
                val dur = t2.finish(); tx = null
                if (dur < 5) error("Extracted audio is empty")
                repo.updateLecture(id) { it.copy(segments = listOf(Segment(0, outFile.absolutePath, it.recordedAtMs, (dur * 1000).toLong())),
                    panoptoDurationMs = (dur * 1000).toLong(), progress = 0, stageDetail = null) }
            }
            status[id] = DONE
            extracting.remove(id)
            UploadWorker.enqueue(app, id) // final (non-live) run: uploads any remaining parts and finalizes the job
            Result.success()
        } catch (e: CancellationException) {
            tx?.abort(); extracting.remove(id)
            if (status[id] != WAIT_WIFI) status.remove(id)
            throw e
        } catch (e: Exception) {
            tx?.abort(); extracting.remove(id)
            if (isStopped || (!allowCell && onCellular(app))) { // network change, not a real failure → keep parts, resume later
                if (!allowCell && onCellular(app)) askCellular(app, id)
                return Result.retry()
            }
            val msg = if (e is PanoptoAuthRequired) AUTH_MSG else "Panopto audio failed: ${e.message}"
            status[id] = "error:$msg"
            repo.updateLecture(id) { it.copy(error = msg) }
            if (e !is PanoptoAuthRequired && runAttemptCount < 3) Result.retry()
            else { repo.updateLecture(id) { it.copy(state = LectureState.FAILED) }; Result.failure() }
        } finally { tmpDir.deleteRecursively(); runCatching { cm?.unregisterNetworkCallback(cb) } }
    }

    private fun <T> retry(n: Int = 4, block: () -> T): T {
        var last: Exception? = null
        repeat(n) { a -> try { return block() } catch (e: Exception) { last = e; Thread.sleep(1500L * (a + 1)) } }
        throw last!!
    }

    private fun fmt(s: Double) = "%d:%02d".format((s / 3600).toInt(), ((s % 3600) / 60).toInt())

    override suspend fun getForegroundInfo(): ForegroundInfo = info(applicationContext.getString(il.ac.tau.lecturesummarizer.R.string.audio_download_start), 0)

    /** One shared queue notification: "<course> · <lecture>", this lecture's progress, and how many more are queued. */
    private fun info(text: String, pct: Int): ForegroundInfo {
        val app = applicationContext as LectureApp
        val id = inputData.getString(KEY_LECTURE)
        val l = id?.let { app.repo.lecture(it) }
        val course = l?.let { app.repo.course(it.courseId)?.name }
        val title = listOfNotNull(course, l?.title).joinToString(" · ").ifEmpty { "Lecture Summarizer" }
        val more = app.repo.lectures.value.count { it.id != id && it.panoptoAudio && it.panoptoDurationMs == 0L && it.state == LectureState.UPLOADING }
        val sub = if (more > 0) "Auto-sync queue · $more more waiting" else "Auto-sync queue"
        val n = NotificationCompat.Builder(applicationContext, LectureApp.CHANNEL_UPLOAD)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title).setContentText(text).setSubText(sub)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n$sub"))
            .addAction(0, applicationContext.getString(il.ac.tau.lecturesummarizer.R.string.queue_pause), il.ac.tau.lecturesummarizer.autosync.QueueControl.pi(app, il.ac.tau.lecturesummarizer.autosync.QueueControl.ACTION_PAUSE, 1))
            .setOngoing(true).setOnlyAlertOnce(true).setProgress(100, pct, pct == 0).build()
        val nid = QUEUE_NID
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(nid, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(nid, n)
    }

    companion object {
        const val KEY_LECTURE = "lecture"
        /** Single ongoing notification shared by all queued Panopto downloads. */
        const val QUEUE_NID = 0x30000
        const val DONE = "done"
        const val AUTH_MSG = "Panopto sign-in required — open the lecture and tap Sign in to Panopto, then Retry"
        /** lectureId → "0".."99" progress, [DONE], or "error:…" (UI). */
        val status = mutableStateMapOf<String, String>()
        /** lectureId → total ~10 min audio parts being extracted (UI "parts X / Y"). */
        val parts = mutableStateMapOf<String, Int>()
        /** Lectures whose Panopto audio is still being extracted (live part uploads allowed). */
        val extracting = mutableStateMapOf<String, Boolean>()

        const val KEY_ALLOW_CELL = "allowCell"
        /** status value: paused on mobile data, waiting for Wi‑Fi (or the user's OK to use mobile data). */
        const val WAIT_WIFI = "wait-wifi"
        const val ACTION_USE_CELL = "il.ac.tau.lecturesummarizer.PANOPTO_USE_CELL"
        const val ACTION_WAIT_WIFI = "il.ac.tau.lecturesummarizer.PANOPTO_WAIT_WIFI"
        private fun promptId(id: String) = 0x31000 + (id.hashCode() and 0xFFFF)

        /** Pause + ask: "continue on mobile data" vs "wait for Wi‑Fi" (notification + in-app card on the lecture). */
        fun askCellular(ctx: Context, id: String) {
            if (status[id] == WAIT_WIFI) return
            status[id] = WAIT_WIFI
            fun pi(action: String, rc: Int) = android.app.PendingIntent.getBroadcast(ctx, promptId(id) + rc,
                android.content.Intent(ctx, CellularChoiceReceiver::class.java).setAction(action).putExtra(KEY_LECTURE, id),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(ctx, LectureApp.CHANNEL_JOBS)
                .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(ctx.getString(il.ac.tau.lecturesummarizer.R.string.panopto_mobile_pause_title))
                .setContentText(ctx.getString(il.ac.tau.lecturesummarizer.R.string.panopto_mobile_pause_body))
                .setAutoCancel(true)
                .addAction(0, ctx.getString(il.ac.tau.lecturesummarizer.R.string.use_mobile), pi(ACTION_USE_CELL, 1))
                .addAction(0, ctx.getString(il.ac.tau.lecturesummarizer.R.string.wait_wifi), pi(ACTION_WAIT_WIFI, 2)).build()
            try { androidx.core.app.NotificationManagerCompat.from(ctx).notify(promptId(id), n) } catch (_: SecurityException) { /* Notifications disabled by the user. */ }
        }
        fun cancelPrompt(ctx: Context, id: String) = androidx.core.app.NotificationManagerCompat.from(ctx).cancel(promptId(id))

        fun onCellular(ctx: Context): Boolean {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED).not()
        }

        /** [wifiOnly]: wait for an unmetered network (default); false = user accepted cellular. */
        fun enqueue(ctx: Context, lectureId: String, wifiOnly: Boolean = true) {
            status[lectureId] = "0"
            if (!wifiOnly) cancelPrompt(ctx, lectureId)
            val req = OneTimeWorkRequestBuilder<PanoptoAudioWorker>()
                .setInputData(workDataOf(KEY_LECTURE to lectureId, KEY_ALLOW_CELL to !wifiOnly)).addTag(il.ac.tau.lecturesummarizer.autosync.QueueControl.TAG)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("panopto-audio-$lectureId", ExistingWorkPolicy.REPLACE, req)
            if (wifiOnly && onCellular(ctx)) askCellular(ctx, lectureId) // starts on cellular → ask, don't burn data
        }
    }
}

/** Notification actions: mobile data → re-enqueue with CONNECTED (parts resume); wait → keep the UNMETERED work queued. */
class CellularChoiceReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: android.content.Intent) {
        val id = intent.getStringExtra(PanoptoAudioWorker.KEY_LECTURE) ?: return
        PanoptoAudioWorker.cancelPrompt(ctx, id)
        if (intent.action == PanoptoAudioWorker.ACTION_USE_CELL) PanoptoAudioWorker.enqueue(ctx, id, wifiOnly = false)
        }
    }
