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

    data class DeviceCalendar(val id: Long, val name: String, val account: String)

    fun listCalendars(ctx: Context): List<DeviceCalendar> {
        if (!hasPermission(ctx)) return emptyList()
        return runCatching {
            val out = mutableListOf<DeviceCalendar>()
            ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME),
                null, null, CalendarContract.Calendars.ACCOUNT_NAME + " ASC")?.use { c ->
                while (c.moveToNext()) out += DeviceCalendar(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "")
            }
            out
        }.getOrDefault(emptyList())
    }

    /** [calendarIds] empty = Auto (prefer TAU accounts); otherwise only those calendars, no TAU filter. */
    fun currentClasses(ctx: Context, calendarIds: Set<Long> = emptySet(), nowMs: Long = System.currentTimeMillis()): List<TauClass> {
        if (!hasPermission(ctx)) return emptyList()
        return runCatching {
            val events = query(ctx, nowMs - 4 * 3600_000L, nowMs + 15 * 60_000L, calendarIds)
            // Prefer TAU-scheme matches; if none, fall back to any timed event now, named from its cleaned title.
            val found = TauEventParser.candidates(events, nowMs, preferTau = calendarIds.isEmpty())
                .ifEmpty { TauEventParser.candidates(events, nowMs, preferTau = false) }
            found.map {
                if (it.kind == ClassKind.UNKNOWN && it.courseNumber == null && !it.fromTauCalendar)
                    it.copy(courseName = TauEventParser.cleanTitle(it.courseName), offScheme = true) else it
            }
        }.getOrDefault(emptyList())
    }

    /** Timed classes (TAU scheme preferred) that began in [fromMs, toMs) — auto-sync retro/live scan. */
    fun pastClasses(ctx: Context, calendarIds: Set<Long>, fromMs: Long, toMs: Long): List<TauClass> {
        if (!hasPermission(ctx)) return emptyList()
        return runCatching {
            val parsed = query(ctx, fromMs, toMs, calendarIds).filter { it.endMs <= toMs }
                .mapNotNull { TauEventParser.parse(it, requireClassHint = calendarIds.isEmpty()) }
            val tau = parsed.filter { it.fromTauCalendar || it.courseNumber != null }
            (if (calendarIds.isEmpty() && tau.isNotEmpty()) tau else parsed)
                .distinctBy { Triple(it.courseName, it.kind, it.beginMs) }
        }.getOrDefault(emptyList())
    }

    private fun query(ctx: Context, from: Long, to: Long, ids: Set<Long>): List<CalEvent> {
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, from); ContentUris.appendId(b, to)
        val proj = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.DESCRIPTION, CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.OWNER_ACCOUNT,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )
        val out = mutableListOf<CalEvent>()
        val sel = if (ids.isEmpty()) null else "${CalendarContract.Instances.CALENDAR_ID} IN (${ids.joinToString(",")})"
        ctx.contentResolver.query(b.build(), proj, sel, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val owner = c.getString(5); val display = c.getString(7)
                val account = if (TauEventParser.isTauAccount(owner)) owner else display ?: owner
                out += CalEvent(c.getString(0) ?: "", c.getString(1), c.getString(2), c.getLong(3), c.getLong(4), account, c.getInt(6) == 1)
            }
        }
        return out
    }
}
