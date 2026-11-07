package il.ac.tau.lecturesummarizer.email

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class EmailRuleStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("email_rules", Context.MODE_PRIVATE)
    fun read(): List<EmailRule> = runCatching {
        val a = JSONArray(p.getString("rules", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
            fun list(key: String): List<String> { val x = o.optJSONArray(key) ?: return emptyList(); return (0 until x.length()).map { x.getString(it) } }
            EmailRule(o.getString("id"), o.getString("name"), o.optBoolean("enabled", true), list("senders"), list("include"), list("exclude"), o.optString("course"))
        } }
    }.getOrDefault(emptyList())
    fun save(rules: List<EmailRule>) { p.edit().putString("rules", JSONArray().apply { rules.forEach { r ->
        put(JSONObject().put("id", r.id).put("name", r.name).put("enabled", r.enabled).put("senders", JSONArray(r.senders))
            .put("include", JSONArray(r.include)).put("exclude", JSONArray(r.exclude)).put("course", r.course))
    } }.toString()).apply() }
}
