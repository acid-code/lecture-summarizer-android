package il.ac.tau.lecturesummarizer.captions

import android.content.Context
import java.io.File

enum class CaptionDownloadState { Idle, Downloading, Ready, Failed }

/** Agreement flag, model paths under filesDir/models/, completeness checks, free-space wipe. */
class CaptionModelStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("captions", Context.MODE_PRIVATE)

    val modelsDir: File get() = File(context.filesDir, "models").apply { mkdirs() }
    val zipformerDir: File get() = File(modelsDir, "zipformer").apply { mkdirs() }
    val zipformerEnDir: File get() = File(modelsDir, "zipformer-en").apply { mkdirs() }
    fun zipformerFile(name: String) = File(zipformerDir, name)
    fun englishFile(name: String) = File(zipformerEnDir, name)
    val ggufFile: File get() = File(modelsDir, CaptionModels.GGUF)

    var agreed: Boolean
        get() = prefs.getBoolean("agreed", false)
        set(v) = prefs.edit().putBoolean("agreed", v).apply()

    var downloadState: CaptionDownloadState
        get() = runCatching { CaptionDownloadState.valueOf(prefs.getString("downloadState", "Idle")!!) }
            .getOrDefault(CaptionDownloadState.Idle)
        set(v) = prefs.edit().putString("downloadState", v.name).apply()

    var lastError: String?
        get() = prefs.getString("lastError", null)
        set(v) = prefs.edit().putString("lastError", v).apply()

    var progressBytes: Long
        get() = prefs.getLong("progressBytes", 0L)
        set(v) = prefs.edit().putLong("progressBytes", v).apply()

    fun zipformerReady(): Boolean = CaptionModels.ZIPFORMER_SIZES.all { (name, size) ->
        val f = zipformerFile(name)
        f.isFile && f.length() == size
    }

    fun englishReady(): Boolean = CaptionModels.ZIPFORMER_EN_SIZES.all { (name, size) ->
        val f = englishFile(name)
        f.isFile && f.length() == size
    }

    fun ggufReady(): Boolean {
        val f = ggufFile
        return f.isFile && f.length() == CaptionModels.GGUF_BYTES
    }

    /** Speech captions need Hebrew Zipformer; English Zipformer improves Latin English. */
    fun speechReady(): Boolean = zipformerReady()

    fun modelsReady(): Boolean = zipformerReady() && englishReady() && ggufReady()

    fun progressFraction(): Float =
        (progressBytes.toFloat() / CaptionModels.DOWNLOAD_TOTAL_BYTES.toFloat()).coerceIn(0f, 1f)

    fun refreshReadyState() {
        if (modelsReady()) {
            downloadState = CaptionDownloadState.Ready
            lastError = null
        } else if (downloadState == CaptionDownloadState.Ready) {
            downloadState = if (agreed) CaptionDownloadState.Downloading else CaptionDownloadState.Idle
        }
    }

    /** If user already agreed but English model is missing, keep downloading in background. */
    fun ensureBackgroundDownload() {
        if (!agreed) return
        if (modelsReady()) {
            downloadState = CaptionDownloadState.Ready
            return
        }
        if (downloadState != CaptionDownloadState.Downloading) {
            downloadState = CaptionDownloadState.Downloading
            CaptionModelDownloader.enqueue(context)
        }
    }

    /** Cancel partials, delete models, clear agreement. Next captions start needs a new Agree. */
    fun freeSpace() {
        CaptionModelDownloader.cancel(context)
        zipformerDir.listFiles()?.forEach { it.delete() }
        zipformerEnDir.listFiles()?.forEach { it.delete() }
        File(modelsDir, "${CaptionModels.GGUF}.partial").delete()
        ggufFile.delete()
        modelsDir.listFiles()?.filter { it.name.endsWith(".partial") }?.forEach { it.delete() }
        agreed = false
        downloadState = CaptionDownloadState.Idle
        progressBytes = 0
        lastError = null
    }

    fun bytesOnDisk(): Long {
        var n = 0L
        zipformerDir.walkTopDown().filter { it.isFile }.forEach { n += it.length() }
        zipformerEnDir.walkTopDown().filter { it.isFile }.forEach { n += it.length() }
        if (ggufFile.isFile) n += ggufFile.length()
        File(modelsDir, "${CaptionModels.GGUF}.partial").takeIf { it.isFile }?.let { n += it.length() }
        return n
    }
}
