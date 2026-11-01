package il.ac.tau.lecturesummarizer.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat

/** Reads device-synced calendars (no OAuth). Returns empty if READ_CALENDAR isn't granted or anything fails. */
object CalendarResolver {
    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun currentClasses(ctx: Context, nowMs: Long = System.currentTimeMillis()): List<TauClass> {
        if (!hasPermission(ctx)) return emptyList()
        return runCatching { TauEventParser.candidates(query(ctx, nowMs - 4 * 3600_000L, nowMs + 15 * 60_000L), nowMs) }
            .getOrDefault(emptyList())
    }

    private fun query(ctx: Context, from: Long, to: Long): List<CalEvent> {
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, from); ContentUris.appendId(b, to)
        val proj = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.DESCRIPTION, CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.OWNER_ACCOUNT,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )
        val out = mutableListOf<CalEvent>()
        ctx.contentResolver.query(b.build(), proj, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val owner = c.getString(5); val display = c.getString(7)
                val account = if (TauEventParser.isTauAccount(owner)) owner else display ?: owner
                out += CalEvent(c.getString(0) ?: "", c.getString(1), c.getString(2), c.getLong(3), c.getLong(4), account, c.getInt(6) == 1)
            }
        }
        return out
    }
}
