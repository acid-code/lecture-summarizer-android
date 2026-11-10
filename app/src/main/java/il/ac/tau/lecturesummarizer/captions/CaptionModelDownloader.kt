package il.ac.tau.lecturesummarizer.captions

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import il.ac.tau.lecturesummarizer.LectureApp
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
            var done = alreadyDownloadedBytes()
            store.progressBytes = done
            for ((name, size) in CaptionModels.ZIPFORMER_SIZES) {
                val dest = store.zipformerFile(name)
                if (dest.isFile && dest.length() == size) continue
                downloadTo(
                    url = "${CaptionModels.HF_ZIPFORMER}/$name",
                    dest = dest,
                    expected = size,
                    onChunk = { n ->
                        done += n
                        store.progressBytes = done
                    },
                )
                runCatching { setForeground(info(pct(done))) }
            }
            if (!store.ggufReady()) {
                var lastPct = -1
                downloadTo(
                    url = CaptionModels.HF_DICTALM,
                    dest = store.ggufFile,
                    expected = CaptionModels.GGUF_BYTES,
                    onChunk = { n ->
                        done += n
                        store.progressBytes = done
                    },
                    onProgress = {
                        val p = pct(done)
                        if (p != lastPct) {
                            lastPct = p
                            runCatching { kotlinx.coroutines.runBlocking { setForeground(info(p)) } }
                        }
                    },
                )
                runCatching { setForeground(info(pct(done))) }
            }
            store.progressBytes = CaptionModels.DOWNLOAD_TOTAL_BYTES
            store.downloadState = CaptionDownloadState.Ready
            store.lastError = null
            Result.success()
        } catch (e: Exception) {
            store.downloadState = CaptionDownloadState.Failed
            store.lastError = e.message?.take(200) ?: "download failed"
            Result.retry()
        }
    }

    private fun remainingBytes(): Long {
        var n = 0L
        for ((name, size) in CaptionModels.ZIPFORMER_SIZES) {
            val f = store.zipformerFile(name)
            if (!(f.isFile && f.length() == size)) n += size
        }
        if (!store.ggufReady()) n += CaptionModels.GGUF_BYTES
        return n
    }

    private fun alreadyDownloadedBytes(): Long {
        var n = 0L
        for ((name, size) in CaptionModels.ZIPFORMER_SIZES) {
            val f = store.zipformerFile(name)
            if (f.isFile && f.length() == size) n += size
        }
        if (store.ggufReady()) n += CaptionModels.GGUF_BYTES
        else {
            val partial = File(store.modelsDir, "${CaptionModels.GGUF}.partial")
            if (partial.isFile) n += partial.length()
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
        onProgress: (() -> Unit)? = null,
    ) {
        dest.parentFile?.mkdirs()
        val partial = File(dest.parentFile, "${dest.name}.partial")
        var existing = if (partial.isFile) partial.length() else 0L
        if (existing > expected) {
            partial.delete()
            existing = 0L
        }
        val req = Request.Builder().url(url).apply {
            if (existing > 0) header("Range", "bytes=$existing-")
        }.build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 206) error("HTTP ${resp.code} for ${dest.name}")
            val body = resp.body ?: error("empty body")
            val append = resp.code == 206 && existing > 0
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
                        if (sinceProgress >= 2L * 1024 * 1024) {
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
        private const val UNIQUE = "caption-models-download"
        private const val NOTIF_ID = 91

        fun enqueue(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<CaptionModelDownloader>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresStorageNotLow(true)
                        .build(),
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE)
        }
    }
}
