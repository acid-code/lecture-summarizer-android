package il.ac.tau.lecturesummarizer.captions

import android.content.Context
import java.io.File

enum class CaptionDownloadState { Idle, Downloading, Ready, Failed }

/** Agreement flag, model paths under filesDir/models/, completeness checks, free-space wipe. */
class CaptionModelStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("captions", Context.MODE_PRIVATE)

    val modelsDir: File get() = File(context.filesDir, "models").apply { mkdirs() }
    val zipformerDir: File get() = File(modelsDir, "zipformer").apply { mkdirs() }
    fun zipformerFile(name: String) = File(zipformerDir, name)
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

    fun ggufReady(): Boolean {
        val f = ggufFile
        return f.isFile && f.length() == CaptionModels.GGUF_BYTES
    }

    fun modelsReady(): Boolean = zipformerReady() && ggufReady()

    fun refreshReadyState() {
        if (modelsReady()) {
            downloadState = CaptionDownloadState.Ready
            lastError = null
        } else if (downloadState == CaptionDownloadState.Ready) {
            downloadState = CaptionDownloadState.Idle
        }
    }

    /** Cancel partials, delete models, clear agreement. Next captions start needs a new Agree. */
    fun freeSpace() {
        CaptionModelDownloader.cancel(context)
        zipformerDir.listFiles()?.forEach { it.delete() }
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
        if (ggufFile.isFile) n += ggufFile.length()
        File(modelsDir, "${CaptionModels.GGUF}.partial").takeIf { it.isFile }?.let { n += it.length() }
        return n
    }
}
