package il.ac.tau.lecturesummarizer.slides

import android.content.Context
import org.json.JSONArray

/** Per-lecture provenance and user veto survive restarts. A manual choice always wins. */
class SlideChoices(private val ctx: Context) {
    private val p = ctx.getSharedPreferences("slide_choices", Context.MODE_PRIVATE)
    fun manual(id: String) { SummaryFreshness(ctx).changed(id); androidx.work.WorkManager.getInstance(ctx).cancelUniqueWork("slides-match-$id"); p.edit().putBoolean("manual:$id", true).putBoolean("auto:$id", false).putString("phase:$id","manual").apply() }
    fun isManual(id: String) = p.getBoolean("manual:$id", false)
    fun isAuto(id: String) = p.getBoolean("auto:$id", false)
    fun name(id: String) = p.getString("name:$id", "").orEmpty()
    fun reasons(id: String): List<String> = runCatching { val a = JSONArray(p.getString("reasons:$id", "[]")); (0 until a.length()).map { a.getString(it) } }.getOrDefault(emptyList())
    fun auto(id: String, name: String, reasons: List<String>) { p.edit().putBoolean("auto:$id", true).putString("name:$id", name).putString("reasons:$id", JSONArray(reasons.take(3)).toString()).apply() }
    fun rejected(id: String) = p.getStringSet("reject:$id", emptySet()).orEmpty()
    fun reject(id: String, deck: Long) { p.edit().putStringSet("reject:$id", rejected(id) + deck.toString()).apply() }
    fun candidates(id: String) = p.getString("candidates:$id", null)
    fun candidates(id: String, json: String?) { p.edit().putString("candidates:$id", json).apply() }
    fun phase(id: String) = p.getString("phase:$id", null)
    fun phase(id: String, value: String) { p.edit().putString("phase:$id", value).commit() }
}
