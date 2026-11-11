package il.ac.tau.lecturesummarizer.captions

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import java.io.File

data class CaptionHardwareSnapshot(
    val availMemBytes: Long,
    val totalMemBytes: Long,
    val freeDiskBytes: Long,
) {
    val canLoadCleanup: Boolean get() = availMemBytes >= CaptionModels.CLEANUP_FREE_RAM_BYTES
    val lowRamDevice: Boolean get() = totalMemBytes in 1 until (4L * 1024 * 1024 * 1024)
    fun canDownload(needed: Long): Boolean = freeDiskBytes >= needed + CaptionModels.DISK_HEADROOM_BYTES
}

object CaptionHardware {
    fun snapshot(context: Context, modelsDir: File): CaptionHardwareSnapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val dir = modelsDir.apply { mkdirs() }
        val stat = StatFs(dir.absolutePath)
        return CaptionHardwareSnapshot(
            availMemBytes = mem.availMem,
            totalMemBytes = mem.totalMem,
            freeDiskBytes = stat.availableBytes,
        )
    }
}
