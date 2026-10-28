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

    /** Mock mode: no network, fake job progress and a locally generated placeholder PDF. */
    var mockMode: Boolean
        get() = prefs.getBoolean("mock", BuildConfig.DEFAULT_MOCK)
        set(v) = prefs.edit().putBoolean("mock", v).apply()
}
