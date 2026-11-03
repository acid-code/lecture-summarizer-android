package il.ac.tau.lecturesummarizer.autosync

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.data.LectureState
import il.ac.tau.lecturesummarizer.panopto.PanoptoAudioWorker

/** Battery policy for background auto-sync: below the user's floor (default 30%, not charging) never runs; <75% asks first. */
object BatteryGate {
    const val DEFAULT_MIN = 30
    const val MIN_FLOOR = 15
    const val MAX_FLOOR = 60
    val PRESETS = listOf(20, 30, 40, 50)
    fun floor(ctx: Context) = AutoSyncStore(ctx).batteryHardMin
    const val ASK_BELOW = 75
    data class State(val pct: Int, val charging: Boolean)
    fun read(ctx: Context): State {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plug = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (lvl < 0 || scale <= 0) 100 else lvl * 100 / scale
        return State(pct, plug != 0 || st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL)
    }
    /** True → must not run at all. */
    fun tooLow(ctx: Context) = read(ctx).let { !it.charging && it.pct < floor(ctx) }
    /** True → needs the user's OK (valid for 12 h once given). */
    fun needsAsk(ctx: Context) = read(ctx).let { !it.charging && it.pct < ASK_BELOW && it.pct >= floor(ctx) } &&
        System.currentTimeMillis() > AutoSyncStore(ctx).batteryOkUntil

    private const val PROMPT_ID = 0x32002
    const val ACTION_BATTERY_OK = "il.ac.tau.lecturesummarizer.AUTOSYNC_BATTERY_OK"
    const val ACTION_BATTERY_LATER = "il.ac.tau.lecturesummarizer.AUTOSYNC_BATTERY_LATER"
    fun ask(ctx: Context) {
        val b = read(ctx)
        val f = floor(ctx)
        val open = PendingIntent.getActivity(ctx, 0x32100 + 13,
            Intent(ctx, BatteryFloorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, LectureApp.CHANNEL_JOBS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
            .setContentTitle("Auto-sync lectures — battery ${b.pct}%")
            .setContentText("Keep syncing on battery until it drops below $f%? Tap to choose the stop %. · עד כמה אחוזים להמשיך?")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Background sync is on battery under 75%. Choose the % below which it stops (default 30%), or wait until charging. · בחר באיזה אחוז סוללה לעצור."))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Choose stop %", open)
            .addAction(0, "Run (stop <$f%)", QueueControl.pi(ctx, ACTION_BATTERY_OK, 11))
            .addAction(0, "Wait", QueueControl.pi(ctx, ACTION_BATTERY_LATER, 12)).build()
        runCatching { NotificationManagerCompat.from(ctx).notify(PROMPT_ID, n) }
    }
    fun cancelAsk(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(PROMPT_ID)

    /** Save chosen floor; continue (12 h OK, like Run anyway) only if the battery is still at/above it. */
    fun chooseFloor(ctx: Context, pct: Int): Boolean {
        cancelAsk(ctx)
        val st = AutoSyncStore(ctx); st.batteryHardMin = pct
        val b = read(ctx)
        return if (b.charging || b.pct >= st.batteryHardMin) {
            st.batteryOkUntil = System.currentTimeMillis() + 12 * 3600_000L
            QueueControl.resume(ctx); true
        } else { AutoSyncWorker.status.value = "Battery below ${st.batteryHardMin}% — waiting for charger"; false }
    }
}

/** Pause / resume of the whole auto-sync queue (scans + Panopto audio downloads). */
object QueueControl {
    const val ACTION_PAUSE = "il.ac.tau.lecturesummarizer.AUTOSYNC_PAUSE"
    const val ACTION_RESUME = "il.ac.tau.lecturesummarizer.AUTOSYNC_RESUME"
    const val TAG = "autosync-queue"
    val paused = mutableStateOf<Boolean?>(null)

    fun pi(ctx: Context, action: String, rc: Int): PendingIntent = PendingIntent.getBroadcast(ctx, 0x32100 + rc,
        Intent(ctx, QueueControlReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun isPaused(ctx: Context) = AutoSyncStore(ctx).paused

    fun pause(ctx: Context) {
        AutoSyncStore(ctx).paused = true; paused.value = true
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork("autosync-now"); wm.cancelUniqueWork("autosync-wifi")
        wm.cancelAllWorkByTag(TAG) // Panopto downloads: finished ~10 min parts are kept and resume later
        AutoSyncWorker.status.value = "Paused · מושהה"
    }

    fun resume(ctx: Context) {
        val app = ctx.applicationContext as LectureApp
        AutoSyncStore(app).paused = false; paused.value = false
        app.repo.lectures.value.filter { it.panoptoAudio && it.panoptoDurationMs == 0L && it.state == LectureState.UPLOADING }
            .forEach { PanoptoAudioWorker.enqueue(app, it.id) }
        AutoSyncWorker.schedule(app)
        AutoSyncWorker.runNow(app)
    }
}

class QueueControlReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            QueueControl.ACTION_PAUSE -> QueueControl.pause(ctx)
            QueueControl.ACTION_RESUME -> QueueControl.resume(ctx)
            BatteryGate.ACTION_BATTERY_OK -> {
                BatteryGate.cancelAsk(ctx)
                AutoSyncStore(ctx).batteryOkUntil = System.currentTimeMillis() + 12 * 3600_000L
                QueueControl.resume(ctx)
            }
            BatteryGate.ACTION_BATTERY_LATER -> BatteryGate.cancelAsk(ctx)
        }
    }
}
