package il.ac.tau.lecturesummarizer.data

import android.content.Context
import il.ac.tau.lecturesummarizer.BuildConfig

/** User-editable API settings (Settings screen). Defaults come from local.properties via BuildConfig. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("baseUrl", BuildConfig.DEFAULT_BASE_URL)!!
        set(v) = prefs.edit().putString("baseUrl", v.trimEnd('/')).apply()

    var apiToken: String
        get() = prefs.getString("apiToken", BuildConfig.DEFAULT_API_TOKEN)!!
        set(v) = prefs.edit().putString("apiToken", v).apply()

    /**
     * Stable per-install id sent as X-Device-Id; the server scopes all courses/lectures/jobs to it.
     * Generated once and persisted; never regenerated (only a reinstall / clear-data yields a new one).
     */
    val deviceId: String
        @Synchronized get() = prefs.getString("deviceId", null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).commit()
        }

    /** Mock mode: no network, fake job progress and a locally generated placeholder PDF. */
    var mockMode: Boolean
        get() = prefs.getBoolean("mock", BuildConfig.DEFAULT_MOCK)
        set(v) = prefs.edit().putBoolean("mock", v).apply()

    /** Calendar-arm source calendars (CalendarContract IDs). Empty = Auto (prefer @mail.tau.ac.il, else any). */
    var calendarIds: Set<Long>
        get() = prefs.getStringSet("calendarIds", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
        set(v) = prefs.edit().putStringSet("calendarIds", v.map { it.toString() }.toSet()).apply()
}
