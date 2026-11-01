package il.ac.tau.lecturesummarizer.moodle

import android.content.Context
import android.webkit.CookieManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class MoodleAuthRequired : Exception("Moodle login required")

data class MoodleCourse(val id: Long, val fullname: String, val shortname: String, val idnumber: String)
data class MoodleFile(val cmid: Long, val name: String, val url: String, val module: String)
data class MoodleSection(val title: String, val files: List<MoodleFile>)

/**
 * Session-cookie Moodle client (same as the web UI): cookies come from the WebView login
 * (android.webkit.CookieManager) and are only sent as headers — never logged or persisted by us.
 * Prefers Moodle AJAX (/lib/ajax/service.php + sesskey); falls back to course page HTML.
 */
class MoodleClient(private val base: String) {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private var sesskey: String? = null

    private fun cookie(url: String): String? = CookieManager.getInstance().getCookie(url)

    private fun req(url: String) = Request.Builder().url(url).apply { cookie(url)?.let { header("Cookie", it) } }

    private fun isLoginUrl(u: String?) = u != null && (u.contains("/login/") || u.contains("/auth/saml2") || u.contains("idp"))

    /** GET following redirects manually (so cookies are attached per hop). Returns final url + body text. */
    private fun getText(url: String): Pair<String, String> {
        var u = url
        repeat(8) {
            http.newCall(req(u).build()).execute().use { r ->
                if (r.isRedirect) {
                    val loc = r.header("Location") ?: throw MoodleAuthRequired()
                    u = r.request.url.resolve(loc).toString()
                    if (isLoginUrl(u)) throw MoodleAuthRequired()
                } else {
                    if (!r.isSuccessful) error("Moodle HTTP ${r.code}")
                    return u to (r.body?.string() ?: "")
                }
            }
        }
        error("Too many redirects")
    }

    fun sesskey(): String = sesskey ?: run {
        val (_, html) = getText("$base/my/")
        val k = Regex(""""sesskey":"([A-Za-z0-9]+)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""sesskey=([A-Za-z0-9]+)""").find(html)?.groupValues?.get(1) ?: throw MoodleAuthRequired()
        k.also { sesskey = it }
    }

    fun isLoggedIn(): Boolean = runCatching { sesskey(); true }.getOrDefault(false)

    private fun ajax(method: String, args: JSONObject): Any {
        val body = JSONArray().put(JSONObject().put("index", 0).put("methodname", method).put("args", args)).toString()
        val url = "$base/lib/ajax/service.php?sesskey=${sesskey()}&info=$method"
        http.newCall(req(url).post(body.toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
            if (r.isRedirect) throw MoodleAuthRequired()
            val txt = r.body?.string().orEmpty()
            if (txt.trimStart().startsWith("{")) { // top-level error (e.g. servicerequireslogin)
                val o = JSONObject(txt); if (o.optString("errorcode").contains("login")) { sesskey = null; throw MoodleAuthRequired() }
                error(o.optString("message", "Moodle error"))
            }
            val o = JSONArray(txt).getJSONObject(0)
            if (o.optBoolean("error")) {
                val ex = o.optJSONObject("exception"); val code = ex?.optString("errorcode").orEmpty()
                if (code.contains("login") || code.contains("sesskey")) { sesskey = null; throw MoodleAuthRequired() }
                error(ex?.optString("message") ?: "Moodle error")
            }
            return o.get("data")
        }
    }

    fun courses(): List<MoodleCourse> {
        val d = ajax("core_course_get_enrolled_courses_by_timeline_classification",
            JSONObject().put("offset", 0).put("limit", 0).put("classification", "all").put("sort", "fullname")) as JSONObject
        val a = d.getJSONArray("courses")
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            MoodleCourse(it.getLong("id"), it.optString("fullname"), it.optString("shortname"), it.optString("idnumber"))
        }
    }

    /** Sections as the course itself names them (week / topic / lecturer), with file resources. */
    fun sections(courseId: Long): List<MoodleSection> = runCatching { sectionsAjax(courseId) }.getOrElse { e ->
        if (e is MoodleAuthRequired) throw e; sectionsHtml(courseId)
    }.filter { it.files.isNotEmpty() }

    private fun sectionsAjax(courseId: Long): List<MoodleSection> {
        val st = JSONObject(ajax("core_courseformat_get_state", JSONObject().put("courseid", courseId)) as String)
        val cms = st.getJSONArray("cm").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.associateBy { it.optString("id") }
        val secs = st.getJSONArray("section")
        return (0 until secs.length()).map { secs.getJSONObject(it) }.sortedBy { it.optInt("number") }.map { s ->
            val list = s.optJSONArray("cmlist") ?: JSONArray()
            val files = (0 until list.length()).mapNotNull { cms[list.get(it).toString()] }
                .filter { it.optString("module") in FILE_MODULES }
                .map { MoodleFile(it.getLong("id"), it.optString("name"), it.optString("url"), it.optString("module")) }
            MoodleSection(s.optString("title").ifBlank { "Section ${s.optInt("number")}" }, files)
        }
    }

    private fun sectionsHtml(courseId: Long): List<MoodleSection> {
        val (_, html) = getText("$base/course/view.php?id=$courseId")
        val parts = Regex("""<li[^>]+id="section-(\d+)"""").findAll(html).toList()
        return parts.mapIndexed { i, m ->
            val chunk = html.substring(m.range.first, parts.getOrNull(i + 1)?.range?.first ?: html.length)
            val title = Regex("""data-sectionname="([^"]+)"""").find(chunk)?.groupValues?.get(1)
                ?: Regex("""class="sectionname[^"]*"[^>]*>(?:<[^>]+>)*([^<]+)""").find(chunk)?.groupValues?.get(1) ?: "Section ${m.groupValues[1]}"
            val files = Regex("""href="([^"]*/mod/(resource|folder)/view\.php\?id=(\d+))"[^>]*>.*?<span class="instancename">([^<]+)""", RegexOption.DOT_MATCHES_ALL)
                .findAll(chunk).map { f -> MoodleFile(f.groupValues[3].toLong(), unescape(f.groupValues[4].trim()), unescape(f.groupValues[1]), f.groupValues[2]) }
                .distinctBy { it.cmid }.toList()
            MoodleSection(unescape(title.trim()), files)
        }
    }

    /** Real file name of a mod/resource (follows redirects to pluginfile.php without downloading the body). Null if unknown. */
    fun fileName(f: MoodleFile): String? = runCatching {
        var u = "$base/mod/resource/view.php?id=${f.cmid}&redirect=1"
        repeat(8) {
            if (u.contains("/pluginfile.php/")) return@runCatching java.net.URLDecoder.decode(u.substringBefore('?').substringAfterLast('/'), "UTF-8")
            var next: String? = null
            http.newCall(req(u).head().build()).execute().use { r ->
                if (r.isRedirect) next = r.request.url.resolve(r.header("Location") ?: return@runCatching null).toString()
                else if (r.isSuccessful) {
                    r.header("Content-Disposition")?.let { cd -> Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)""").find(cd)?.groupValues?.get(1)?.let { return@runCatching java.net.URLDecoder.decode(it, "UTF-8") } }
                    if (r.header("Content-Type").orEmpty().startsWith("text/html")) {
                        http.newCall(req(u).build()).execute().use { g ->
                            next = Regex("""href="([^"]+/pluginfile\.php/[^"]+)"""").find(g.body?.string().orEmpty())?.groupValues?.get(1)?.let(::unescape)
                        }
                    } else return@runCatching r.request.url.pathSegments.lastOrNull()?.takeIf { '.' in it }
                }
            }
            u = next ?: return@runCatching null
            if (isLoginUrl(u)) return@runCatching null
        }
        null
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Download a mod/resource into [dir]. Returns the file, or throws if it is not PDF/PPTX. */
    fun download(f: MoodleFile, dir: File): File {
        var u = "$base/mod/resource/view.php?id=${f.cmid}&redirect=1"
        repeat(8) {
            http.newCall(req(u).build()).execute().use { r ->
                if (r.isRedirect) {
                    u = r.request.url.resolve(r.header("Location") ?: throw MoodleAuthRequired()).toString()
                    if (isLoginUrl(u)) throw MoodleAuthRequired()
                    return@use
                }
                if (!r.isSuccessful) error("Download failed: HTTP ${r.code}")
                val type = r.header("Content-Type").orEmpty()
                if (type.startsWith("text/html")) {
                    // Resource page shown instead of file: find the pluginfile link
                    val html = r.body?.string().orEmpty()
                    u = Regex("""href="([^"]+/pluginfile\.php/[^"]+)"""").find(html)?.groupValues?.get(1)?.let(::unescape)
                        ?: error("No file found on Moodle page")
                    return@use
                }
                val fromUrl = r.request.url.pathSegments.lastOrNull().orEmpty()
                val name = safeName(fromUrl.ifBlank { f.name })
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in setOf("pdf", "pptx", "ppt") && !type.contains("pdf") && !type.contains("presentation"))
                    error("Not a PDF/PPTX: $name")
                val out = File(dir.apply { mkdirs() }, "${f.cmid}_$name")
                r.body!!.byteStream().use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                return out
            }
        }
        error("Too many redirects")
    }

    companion object {
        const val DEFAULT_BASE = "https://moodle.tau.ac.il"
        val FILE_MODULES = setOf("resource")
        fun safeName(s: String) = java.net.URLDecoder.decode(s, "UTF-8").replace(Regex("""[\\/:*?"<>|]"""), "_").take(120)
        fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">")
    }
}

/** Local state: course mapping, downloaded-file cache, "used" marks. No cookies stored here. */
class MoodleStore(private val ctx: Context) {
    private val p = ctx.getSharedPreferences("moodle", Context.MODE_PRIVATE)
    val cacheDir get() = File(ctx.filesDir, "moodle")

    /** Future: Settings hook for a different Moodle instance (e.g. /2025/). */
    var baseUrl: String
        get() = p.getString("baseUrl", MoodleClient.DEFAULT_BASE)!!
        set(v) = p.edit().putString("baseUrl", v.trim().trimEnd('/').ifEmpty { MoodleClient.DEFAULT_BASE }).apply()

    fun mappedCourse(key: String): Long? = p.getLong("map:$key", -1).takeIf { it > 0 }
    fun mapCourse(key: String, id: Long) = p.edit().putLong("map:$key", id).apply()

    fun cached(cmid: Long): File? = p.getString("file:$cmid", null)?.let(::File)?.takeIf { it.exists() }
    fun putCached(cmid: Long, f: File) = p.edit().putString("file:$cmid", f.absolutePath).apply()
    fun isUsed(cmid: Long) = p.getBoolean("used:$cmid", false)
    fun markUsed(cmid: Long) = p.edit().putBoolean("used:$cmid", true).apply()
}
