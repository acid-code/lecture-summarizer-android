package il.ac.tau.lecturesummarizer.captions

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.MainActivity
import il.ac.tau.lecturesummarizer.R
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** Unmetered-only download of Zipformer assets + DictaLM GGUF into filesDir/models/. */
class CaptionModelDownloader(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val store = CaptionModelStore(applicationContext)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .build()

    override suspend fun doWork(): Result {
        if (!store.agreed) return Result.failure()
        store.downloadState = CaptionDownloadState.Downloading
        store.lastError = null
        try {
            setForeground(info(0))
        } catch (_: Exception) { /* best-effort */ }

        val hw = CaptionHardware.snapshot(applicationContext, store.modelsDir)
        val stillNeeded = remainingBytes()
        if (!hw.canDownload(stillNeeded)) {
            store.downloadState = CaptionDownloadState.Failed
            store.lastError = applicationContext.getString(R.string.captions_err_disk)
            return Result.failure()
        }

        return try {
            var done = store.bytesDownloadedOnDisk()
            var lastPersisted = done
            fun bump(n: Long) {
                done += n
                // Avoid flooding SharedPreferences on every 256KB chunk (~1GB → thousands of writes).
                if (done - lastPersisted >= 1L * 1024 * 1024 || done >= CaptionModels.DOWNLOAD_TOTAL_BYTES) {
                    store.progressBytes = done
                    lastPersisted = done
                }
            }
            store.progressBytes = done
            for ((name, size) in CaptionModels.ZIPFORMER_SIZES) {
                val dest = store.zipformerFile(name)
                if (dest.isFile && dest.length() == size) continue
                downloadTo(
                    url = "${CaptionModels.HF_ZIPFORMER}/$name",
                    dest = dest,
                    expected = size,
                    onChunk = { bump(it) },
                )
                done = store.bytesDownloadedOnDisk()
                store.progressBytes = done
                lastPersisted = done
                runCatching { setForeground(info(pct(done))) }
            }
            if (store.zipformerReady()) {
                notifyUser(
                    DONE_SPEECH_ID,
                    applicationContext.getString(R.string.captions_speech_ready_title),
                    applicationContext.getString(R.string.captions_speech_ready_body),
                )
            }
            for ((name, size) in CaptionModels.ZIPFORMER_EN_SIZES) {
                val dest = store.englishFile(name)
                if (dest.isFile && dest.length() == size) continue
                downloadTo(
                    url = "${CaptionModels.HF_ZIPFORMER_EN}/$name",
                    dest = dest,
                    expected = size,
                    onChunk = { bump(it) },
                )
                done = store.bytesDownloadedOnDisk()
                store.progressBytes = done
                lastPersisted = done
                runCatching { setForeground(info(pct(done))) }
            }
            if (store.englishReady()) {
                notifyUser(
                    DONE_EN_ID,
                    applicationContext.getString(R.string.captions_english_ready_title),
                    applicationContext.getString(R.string.captions_english_ready_body),
                )
            }
            if (!store.ggufReady()) {
                var lastPct = -1
                // Resync before GGUF so resume doesn't double-count a partial.
                done = store.bytesDownloadedOnDisk()
                store.progressBytes = done
                lastPersisted = done
                Log.i(TAG, "GGUF download start at ${pct(done)}% (${done} bytes)")
                downloadTo(
                    url = CaptionModels.HF_DICTALM,
                    dest = store.ggufFile,
                    expected = CaptionModels.GGUF_BYTES,
                    onChunk = { bump(it) },
                    onRewind = { discarded ->
                        done = (done - discarded).coerceAtLeast(0L)
                        store.progressBytes = done
                        lastPersisted = done
                    },
                    onProgress = {
                        // Prefer disk length so UI can't freeze on a stale counter.
                        done = store.bytesDownloadedOnDisk()
                        store.progressBytes = done
                        lastPersisted = done
                        val p = pct(done)
                        if (p != lastPct) {
                            lastPct = p
                            Log.i(TAG, "GGUF progress $p%")
                            runCatching { kotlinx.coroutines.runBlocking { setForeground(info(p)) } }
                        }
                    },
                )
                done = store.bytesDownloadedOnDisk()
                store.progressBytes = done
                runCatching { setForeground(info(pct(done))) }
            }
            store.progressBytes = CaptionModels.DOWNLOAD_TOTAL_BYTES
            store.downloadState = CaptionDownloadState.Ready
            store.lastError = null
            notifyUser(
                DONE_ALL_ID,
                applicationContext.getString(R.string.captions_download_done_title),
                applicationContext.getString(R.string.captions_download_done_body),
            )
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "download failed", e)
            store.syncProgressFromDisk()
            store.downloadState = CaptionDownloadState.Failed
            store.lastError = e.message?.take(200) ?: "download failed"
            Result.retry()
        }
    }

    private fun notifyUser(id: Int, title: String, body: String) {
        val open = PendingIntent.getActivity(
            applicationContext, id,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(applicationContext, LectureApp.CHANNEL_JOBS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { NotificationManagerCompat.from(applicationContext).notify(id, n) }
    }

    private fun remainingBytes(): Long {
        var n = 0L
        for ((name, size) in CaptionModels.ZIPFORMER_SIZES) {
            val f = store.zipformerFile(name)
            if (!(f.isFile && f.length() == size)) n += size
        }
        for ((name, size) in CaptionModels.ZIPFORMER_EN_SIZES) {
            val f = store.englishFile(name)
            if (!(f.isFile && f.length() == size)) n += size
        }
        if (!store.ggufReady()) {
            val partial = File(store.modelsDir, "${CaptionModels.GGUF}.partial")
            val have = if (partial.isFile) partial.length() else 0L
            n += (CaptionModels.GGUF_BYTES - have).coerceAtLeast(0L)
        }
        return n
    }

    private fun pct(done: Long): Int =
        ((done.toDouble() / CaptionModels.DOWNLOAD_TOTAL_BYTES) * 100).toInt().coerceIn(0, 99)

    private fun downloadTo(
        url: String,
        dest: File,
        expected: Long,
        onChunk: (Long) -> Unit,
        onRewind: ((Long) -> Unit)? = null,
        onProgress: (() -> Unit)? = null,
    ) {
        dest.parentFile?.mkdirs()
        val partial = File(dest.parentFile, "${dest.name}.partial")
        var existing = if (partial.isFile) partial.length() else 0L
        if (existing > expected) {
            partial.delete()
            existing = 0L
        }
        if (existing == expected) {
            if (dest.exists()) dest.delete()
            if (!partial.renameTo(dest)) {
                partial.copyTo(dest, overwrite = true)
                partial.delete()
            }
            return
        }
        val req = Request.Builder().url(url).apply {
            if (existing > 0) header("Range", "bytes=$existing-")
        }.build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 206) error("HTTP ${resp.code} for ${dest.name}")
            val body = resp.body ?: error("empty body")
            // If server ignored Range and sent 200, rewrite partial and only count new bytes from 0.
            val append = resp.code == 206 && existing > 0
            if (!append && existing > 0) {
                Log.w(TAG, "${dest.name}: server ignored Range, restarting file")
                onRewind?.invoke(existing)
                existing = 0L
            }
            java.io.FileOutputStream(partial, append).use { out ->
                body.byteStream().use { inp ->
                    val buf = ByteArray(256 * 1024)
                    var sinceProgress = 0L
                    while (true) {
                        val r = inp.read(buf)
                        if (r <= 0) break
                        out.write(buf, 0, r)
                        onChunk(r.toLong())
                        sinceProgress += r
                        if (sinceProgress >= 1L * 1024 * 1024) {
                            sinceProgress = 0
                            onProgress?.invoke()
                        }
                    }
                    out.fd.sync()
                }
            }
        }
        if (partial.length() != expected) {
            error("${dest.name}: got ${partial.length()}, expected $expected")
        }
        if (dest.exists()) dest.delete()
        if (!partial.renameTo(dest)) {
            partial.copyTo(dest, overwrite = true)
            partial.delete()
        }
    }

    private fun info(pct: Int): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, LectureApp.CHANNEL_RECORDING)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.captions_download_title))
            .setContentText(applicationContext.getString(R.string.captions_download_progress, pct))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, pct, pct <= 0)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, n)
        }
    }

    companion object {
        private const val TAG = "CaptionModelDl"
        private const val UNIQUE = "caption-models-download"
        private const val NOTIF_ID = 91
        private const val DONE_SPEECH_ID = 92
        private const val DONE_EN_ID = 94
        private const val DONE_ALL_ID = 93

        fun enqueue(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            val active = runCatching {
                wm.getWorkInfosForUniqueWork(UNIQUE).get()
                    .any {
                        it.state == WorkInfo.State.ENQUEUED ||
                            it.state == WorkInfo.State.RUNNING ||
                            it.state == WorkInfo.State.BLOCKED
                    }
            }.getOrDefault(false)
            if (active) return
            // No storage-not-low constraint: a 1GB GGUF often trips it near the end and freezes ~96%.
            val req = OneTimeWorkRequestBuilder<CaptionModelDownloader>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .build(),
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            wm.enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, req)
            Log.i(TAG, "enqueued caption model download")
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE)
        }
    }
}
