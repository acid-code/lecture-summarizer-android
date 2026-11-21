package il.ac.tau.lecturesummarizer.moodle

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import il.ac.tau.lecturesummarizer.LectureApp
import il.ac.tau.lecturesummarizer.MainActivity
import il.ac.tau.lecturesummarizer.R

/**
 * Tracks Moodle/Panopto sessions that still have a cookie but failed a live API check.
 * Background sync used to swallow [MoodleAuthRequired] / [PanoptoAuthRequired] without prompting —
 * Settings kept showing "Connected" until the user manually opened import.
 */
object ConnectionAuth {
    private const val PREFS = "connection_auth"
    private const val MOODLE = "moodle_reauth"
    private const val PANOPTO = "panopto_reauth"
    private const val LAST_MOODLE_NOTIFY = "moodle_notify_at"
    private const val LAST_PANOPTO_NOTIFY = "panopto_notify_at"
    private const val NOTIFY_THROTTLE_MS = 6 * 3600_000L
    const val NOTIFY_ID_MOODLE = 7101
    const val NOTIFY_ID_PANOPTO = 7102

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun moodleNeedsReauth(ctx: Context): Boolean = p(ctx).getBoolean(MOODLE, false)
    fun panoptoNeedsReauth(ctx: Context): Boolean = p(ctx).getBoolean(PANOPTO, false)

    fun clearMoodle(ctx: Context) {
        p(ctx).edit().putBoolean(MOODLE, false).apply()
        NotificationManagerCompat.from(ctx).cancel(NOTIFY_ID_MOODLE)
    }

    fun clearPanopto(ctx: Context) {
        p(ctx).edit().putBoolean(PANOPTO, false).apply()
        NotificationManagerCompat.from(ctx).cancel(NOTIFY_ID_PANOPTO)
    }

    /** Mark Moodle session invalid; optionally post a reconnect notification (throttled). */
    fun markMoodleNeeded(ctx: Context, notify: Boolean = true) {
        val app = ctx.applicationContext
        val was = moodleNeedsReauth(app)
        p(app).edit().putBoolean(MOODLE, true).apply()
        if (notify && (!was || System.currentTimeMillis() - p(app).getLong(LAST_MOODLE_NOTIFY, 0) > NOTIFY_THROTTLE_MS)) {
            p(app).edit().putLong(LAST_MOODLE_NOTIFY, System.currentTimeMillis()).apply()
            notifyReconnect(app, moodle = true)
        }
    }

    fun markPanoptoNeeded(ctx: Context, notify: Boolean = true) {
        val app = ctx.applicationContext
        val was = panoptoNeedsReauth(app)
        p(app).edit().putBoolean(PANOPTO, true).apply()
        if (notify && (!was || System.currentTimeMillis() - p(app).getLong(LAST_PANOPTO_NOTIFY, 0) > NOTIFY_THROTTLE_MS)) {
            p(app).edit().putLong(LAST_PANOPTO_NOTIFY, System.currentTimeMillis()).apply()
            notifyReconnect(app, moodle = false)
        }
    }

    private fun notifyReconnect(ctx: Context, moodle: Boolean) {
        val service = if (moodle) MainActivity.RECONNECT_MOODLE else MainActivity.RECONNECT_PANOPTO
        val title = ctx.getString(if (moodle) R.string.reconnect_moodle_title else R.string.reconnect_panopto_title)
        val body = ctx.getString(if (moodle) R.string.reconnect_moodle_body else R.string.reconnect_panopto_body)
        val open = PendingIntent.getActivity(
            ctx, if (moodle) NOTIFY_ID_MOODLE else NOTIFY_ID_PANOPTO,
            Intent(ctx, MainActivity::class.java)
                .setAction(MainActivity.ACTION_RECONNECT)
                .putExtra(MainActivity.EXTRA_RECONNECT, service)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, LectureApp.CHANNEL_JOBS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(if (moodle) NOTIFY_ID_MOODLE else NOTIFY_ID_PANOPTO, n)
        } catch (_: SecurityException) { /* notifications disabled */ }
    }
}
