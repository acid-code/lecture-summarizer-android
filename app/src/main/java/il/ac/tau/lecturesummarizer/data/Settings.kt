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

    /** Density levels to create on first summarize (multi-select; one summary per level). Default: standard + detailed. */
    var summaryDensities: Set<String>
        get() = prefs.getStringSet("summaryDensities", null)?.takeIf { it.isNotEmpty() } ?: setOf("standard", "detailed")
        set(v) = prefs.edit().putStringSet("summaryDensities", v.ifEmpty { setOf("standard") }).apply()

    /** Mock mode: no network, fake job progress and a locally generated placeholder PDF. */
    /** Forced false in release builds (here, not only in the UI) so a stale pref can never enable it. */
    var mockMode: Boolean
        get() = effectiveMockMode(BuildConfig.DEBUG, prefs.getBoolean("mock", BuildConfig.DEFAULT_MOCK))
        set(v) = prefs.edit().putBoolean("mock", v).apply()

    /** Calendar-arm source calendars (CalendarContract IDs). Empty = Auto (prefer @mail.tau.ac.il, else any). */
    var calendarIds: Set<Long>
        get() = prefs.getStringSet("calendarIds", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
        set(v) = prefs.edit().putStringSet("calendarIds", v.map { it.toString() }.toSet()).apply()

    /** Opt-in, device-calendar-only rule automation. Source calendars are never written. */
    var calendarRulesEnabled: Boolean
        get() = prefs.getBoolean("calendarRulesEnabled", false)
        set(v) = prefs.edit().putBoolean("calendarRulesEnabled", v).apply()

    /** First-launch onboarding (3 screens) finished or skipped through to the end. */
    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboardingDone", false)
        set(v) = prefs.edit().putBoolean("onboardingDone", v).apply()

    /** Legacy preference retained for compatibility; release ignores it entirely. */
    var devUnlocked: Boolean
        get() = prefs.getBoolean("devUnlocked", false)
        set(v) = prefs.edit().putBoolean("devUnlocked", v).apply()
    val showDeveloper: Boolean get() = BuildConfig.DEBUG
}
