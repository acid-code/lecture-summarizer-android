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
enum class CaptionStatus { CHECKING, HAS, NONE, AUTH, UNKNOWN }

class PanoptoAuthRequired(val viewerUrl: String? = null) : Exception("Panopto sign-in required")

data class MoodleCourse(val id: Long, val fullname: String, val shortname: String, val idnumber: String)
data class MoodleFile(val cmid: Long, val name: String, val url: String, val module: String)
data class MoodleSection(val title: String, val files: List<MoodleFile>)

/**
 * Session-cookie Moodle client (same as the web UI): cookies come from the WebView login
 * (android.webkit.CookieManager) and are only sent as headers — never logged or persisted by us.
 * Prefers Moodle AJAX (/lib/ajax/service.php + sesskey); falls back to course page HTML.
 */
class MoodleClient(private val base: String) {
    // One OkHttp client (connection pool + TLS sessions) for the whole process, see [of].
    private val http = SHARED_HTTP
    @Volatile private var sesskey: String? = null

    // In-memory caches (process lifetime): reopening the picker shows the last list instantly.
    private val coursesCache = TtlCache<Unit, List<MoodleCourse>>(10 * 60_000L)
    private val recCache = TtlCache<Long, List<PanoptoRecording>>(10 * 60_000L)
    private val capCache = TtlCache<String, CaptionStatus>(6 * 60 * 60_000L)

    /** Drop cached lists/caption status (Refresh / Retry). */
    fun invalidate() { coursesCache.clear(); recCache.clear(); capCache.clear(); signedInCache = null }

    /** Last known definite caption status (HAS / NONE) for a delivery, if still fresh. */
    fun cachedCaptionStatus(deliveryId: String): CaptionStatus? = capCache.get(deliveryId)

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

    fun courses(): List<MoodleCourse> = coursesCache.get(Unit) ?: fetchCourses().also { coursesCache.put(Unit, it) }

    private fun fetchCourses(): List<MoodleCourse> {
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
    }.map { sec -> sec.copy(files = sec.files.flatMap { f -> if (f.module == "folder") folderFiles(f) else listOf(f) }) }
        .filter { it.files.isNotEmpty() }

    /**
     * Files inside a mod/folder (recursing into its subfolders). Each child keeps its relative path in the name
     * ("תירגולים / תרגול 01 / alg1 2027a - Euler.pdf") so folder names/numbers feed lecture matching.
     */
    fun folderFiles(folder: MoodleFile): List<MoodleFile> = runCatching {
        val (_, html) = getText("$base/mod/folder/view.php?id=${folder.cmid}")
        parseFolderFiles(folder, html)
    }.getOrElse { e -> if (e is MoodleAuthRequired) throw e; emptyList() }

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

    /** Recordings listed in the course page's Panopto side block ("הקלטות הקורס בשרת הוידאו Panopto"). */
    fun panoptoRecordings(courseId: Long): List<PanoptoRecording> =
        recCache.get(courseId) ?: run {
            val block = runCatching { fetchPanoptoRecordings(courseId) }
            val hvp = runCatching { hvpRecordings(courseId) }.getOrElse { e -> if (e is MoodleAuthRequired) throw e; emptyList() }
            // Same Panopto session in the block and an H5P activity → keep one (block entry wins, it has the canonical title).
            val all = (block.getOrElse { e -> if (hvp.isEmpty()) throw e; emptyList() } + hvp).distinctBy { it.deliveryId }
            all.also { if (block.isSuccess) recCache.put(courseId, it) }
        }

    /** Panopto public folder REST API. Uses this host's existing WebView session; never another host's cookies.
     * Some tenants require administrator-enabled API/OAuth access; 401/403 is an honest sign-in/access state. */
    fun panoptoFolderSessions(host:String,folder:String):List<PanoptoRecording> {
        require(folder.matches(Regex("[0-9a-fA-F-]{36}")))
        val origin=Panopto.origin(host);val all=mutableListOf<PanoptoRecording>()
        for(page in 0 until 10){
            val url="$origin/Panopto/api/v1/folders/$folder/sessions?pageNumber=$page&maxResults=100"
            val json=http.newCall(req(url).build()).execute().use{r->
                if(r.isRedirect || r.code==401 || r.code==403)throw PanoptoAuthRequired("$origin/Panopto/Pages/Sessions/List.aspx#folderID=$folder")
                check(r.isSuccessful){"panopto_folder_api"};JSONObject(r.body!!.string())}
            val items=json.optJSONArray("Results") ?: json.optJSONArray("results") ?: error("panopto_folder_schema")
            for(i in 0 until items.length()){val o=items.getJSONObject(i);val id=o.optString("Id",o.optString("id"));val title=o.optString("Name",o.optString("name"));val start=runCatching{java.time.Instant.parse(o.optString("StartTime",o.optString("startTime"))).toEpochMilli()}.getOrNull()
                if(id.isNotBlank())all+=PanoptoRecording(id,title,"$origin/Panopto/Pages/Viewer.aspx?id=$id",start)}
            if(items.length()<100)return all.distinctBy{it.deliveryId}
        }
        error("panopto_folder_partial")
    }

    private val hvpCache = TtlCache<Long, String>(24 * 60 * 60_000L) // cmid → Panopto delivery id ("" = none)

    /** mod/hvp (H5P interactive video) activities in the course: (cmid, activity title). */
    fun hvpActivities(courseId: Long): List<Pair<Long, String>> = runCatching {
        val st = JSONObject(ajax("core_courseformat_get_state", JSONObject().put("courseid", courseId)) as String)
        val a = st.getJSONArray("cm")
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("module") == "hvp" }.map { it.getLong("id") to it.optString("name") }
    }.getOrElse { e ->
        if (e is MoodleAuthRequired) throw e
        val (_, html) = getText("$base/course/view.php?id=$courseId")
        Panopto.extractHvpActivities(html)
    }

    /** Panopto sessions embedded in H5P activities (e.g. "שיעור 5 - תקשורת: נוירוטרנסמיטורים"), titled by the activity. */
    fun hvpRecordings(courseId: Long): List<PanoptoRecording> = hvpActivities(courseId).mapNotNull { (cmid, name) ->
        val id = hvpCache.get(cmid) ?: runCatching { Panopto.embeddedSessionId(getText("$base/mod/hvp/view.php?id=$cmid").second).orEmpty() }
            .getOrElse { e -> if (e is MoodleAuthRequired) throw e; return@mapNotNull null }.also { hvpCache.put(cmid, it) }
        id.takeIf { it.isNotEmpty() }?.let { PanoptoRecording(it, unescape(name.trim()), "${Panopto.HOST}/Panopto/Pages/Viewer.aspx?id=$it") }
    }

    private fun fetchPanoptoRecordings(courseId: Long): List<PanoptoRecording> {
        // The block renders "מאחזר תוכן…" and loads its list via AJAX (block_panopto_get_content) — the course page HTML
        // has no links. Call that web service directly. Retry only on errors / placeholder answers (it hits Panopto's
        // SOAP API and can be slow); a real answer with zero recordings is final — no sleeps, no course-page scrape.
        var lastErr: Throwable? = null
        var lastAnswer = ""
        val waits = longArrayOf(1000, 2000, 4000) // ~7 s total for a slow Panopto SOAP backend
        for (attempt in 0..waits.size) {
            runCatching { ajax("block_panopto_get_content", JSONObject().put("courseid", courseId)) as String }
                .onSuccess { html ->
                    val recs = Panopto.extractRecordings(html)
                    if (recs.isNotEmpty() || Panopto.isFinalBlockAnswer(html)) return recs
                    lastErr = null; lastAnswer = html
                }
                .onFailure { if (it is MoodleAuthRequired) throw it; lastErr = it }
            if (attempt < waits.size) Thread.sleep(waits[attempt])
        }
        val (_, html) = getText("$base/course/view.php?id=$courseId")
        val recs = Panopto.extractRecordings(html)
        if (recs.isEmpty() && Panopto.isLoadingPlaceholder(html))
            error("Panopto block didn't finish loading (\"מאחזר תוכן…\")" +
                (lastErr?.message?.let { " — Moodle web service error: $it" }
                    ?: lastAnswer.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(120).takeIf { it.isNotBlank() }?.let { " — Panopto said: $it" }
                    ?: " — Panopto is slow to answer") + ". Try Refresh.")
        return recs
    }

    /**
     * SRT captions for a Panopto delivery via GenerateSRT.ashx, using CookieManager cookies for the Panopto host.
     * Throws [PanoptoAuthRequired] when Panopto redirects to its sign-in (open the viewer once in the WebView).
     */
    fun downloadCaptions(rec: PanoptoRecording, language: Int = 0): String {
        var u = Panopto.captionsUrl(rec.deliveryId, language,Panopto.origin(rec.viewerUrl))
        repeat(8) {
            http.newCall(req(u).build()).execute().use { r ->
                if (r.isRedirect) {
                    u = r.request.url.resolve(r.header("Location") ?: throw PanoptoAuthRequired(rec.viewerUrl)).toString()
                    if (u.contains("/Auth/", true) || u.contains("Login", true) || isLoginUrl(u)) throw PanoptoAuthRequired(rec.viewerUrl)
                    return@use
                }
                if (r.code == 401 || r.code == 403) throw PanoptoAuthRequired(rec.viewerUrl)
                if (!r.isSuccessful) error("Captions HTTP ${r.code}")
                val txt = r.body?.string().orEmpty()
                if ("-->" !in txt) {
                    if (txt.contains("<html", true)) throw PanoptoAuthRequired(rec.viewerUrl)
                    // Panopto answers 200 + empty body when NOT signed in (same as truly missing captions).
                    // Disambiguate: Viewer.aspx 302s to Auth/Login without a Panopto session.
                    if (!panoptoSignedIn(rec)) throw PanoptoAuthRequired(rec.viewerUrl)
                    error(Panopto.NO_CAPTIONS)
                }
                return txt
            }
        }
        error("Too many redirects")
    }

    @Volatile private var signedInCache: Boolean? = null
    private val signedInLock = Any()

    /**
     * Cheap caption-availability probe: GETs GenerateSRT but reads at most 4 KB, then cancels the stream.
     * Panopto returns 200 + empty body both for "no captions" and "not signed in", so an empty answer is
     * disambiguated once (cached) via the viewer's sign-in redirect.
     */
    fun captionStatus(rec: PanoptoRecording): CaptionStatus = capCache.get(rec.deliveryId) ?: probeCaptions(rec).also {
        if (it == CaptionStatus.HAS || it == CaptionStatus.NONE) capCache.put(rec.deliveryId, it, if (it == CaptionStatus.NONE) 20 * 60_000L else null)
    }

    private fun probeCaptions(rec: PanoptoRecording): CaptionStatus = runCatching {
        http.newCall(req(Panopto.captionsUrl(rec.deliveryId,host=Panopto.origin(rec.viewerUrl))).build()).execute().use { r ->
            if (r.isRedirect) return@runCatching if (Panopto.isLoginRedirect(r.header("Location"))) CaptionStatus.AUTH else CaptionStatus.UNKNOWN
            if (r.code == 401 || r.code == 403) return@runCatching CaptionStatus.AUTH
            if (!r.isSuccessful) return@runCatching CaptionStatus.UNKNOWN
            val head = r.body?.source()?.let { src -> src.request(4096); src.buffer.snapshot().utf8() }.orEmpty()
            when {
                "-->" in head -> CaptionStatus.HAS
                head.contains("<html", true) -> CaptionStatus.AUTH
                head.isBlank() -> {
                    // One sign-in check shared by all parallel probes (was: up to 6 concurrent Viewer.aspx hits).
                    val ok = signedInCache ?: synchronized(signedInLock) { signedInCache ?: panoptoSignedIn(rec).also { signedInCache = it } }
                    if (ok) CaptionStatus.NONE else CaptionStatus.AUTH
                }
                else -> CaptionStatus.UNKNOWN
            }
        }
    }.getOrDefault(CaptionStatus.UNKNOWN)

    private val audioCache = TtlCache<String, Boolean>(30 * 60_000L)
    fun cachedAudioAvailable(deliveryId: String): Boolean? = audioCache.get(deliveryId)

    /**
     * POST DeliveryInfo.aspx with the Panopto session cookies (same as GenerateSRT). Fresh signed stream URLs each call.
     * Throws [PanoptoAuthRequired] when Panopto wants a sign-in.
     */
    fun deliveryInfo(rec: PanoptoRecording): DeliveryInfo {
        val body = PanoptoMedia.deliveryInfoForm(rec.deliveryId).toRequestBody("application/x-www-form-urlencoded".toMediaType())
        http.newCall(req(Panopto.origin(rec.viewerUrl)+"/Panopto/Pages/Viewer/DeliveryInfo.aspx").post(body).build()).execute().use { r ->
            if (r.isRedirect || r.code == 401 || r.code == 403) throw PanoptoAuthRequired(rec.viewerUrl)
            if (!r.isSuccessful) error("Panopto DeliveryInfo HTTP ${r.code}")
            val txt = r.body?.string().orEmpty()
            if (!txt.trimStart().startsWith("{")) throw PanoptoAuthRequired(rec.viewerUrl)
            return try { PanoptoMedia.parseDeliveryInfo(txt) } catch (e: IllegalStateException) {
                if (!panoptoSignedIn(rec)) throw PanoptoAuthRequired(rec.viewerUrl) else throw e
            }
        }
    }

    /** Has a downloadable stream (HLS or podcast) → selectable for the audio fallback. Null = couldn't tell. */
    fun audioAvailable(rec: PanoptoRecording): Boolean? = audioCache.get(rec.deliveryId) ?: runCatching { deliveryInfo(rec).hasAudio }
        .getOrNull()?.also { audioCache.put(rec.deliveryId, it) }

    /** GET a CDN/Panopto URL following redirects (cookies per hop), optional byte range; returns the open body stream to [use]. */
    fun <T> openMedia(url: String, range: LongRange? = null, use: (java.io.InputStream) -> T): T {
        var u = url
        repeat(8) {
            val b = req(u); range?.let { b.header("Range", "bytes=${it.first}-${it.last}") }
            http.newCall(b.build()).execute().use { r ->
                if (r.isRedirect) { u = r.request.url.resolve(r.header("Location") ?: error("Bad redirect")).toString(); return@use }
                if (r.code == 401 || r.code == 403) error("Panopto stream HTTP ${r.code} (link expired?)")
                if (!r.isSuccessful) error("Panopto stream HTTP ${r.code}")
                return use(r.body!!.byteStream())
            }
        }
        error("Too many redirects")
    }

    fun mediaText(url: String): String = openMedia(url) { it.readBytes().toString(Charsets.UTF_8) }

    fun resetPanoptoSession() { signedInCache = null }

    /** True if the Panopto viewer for [rec] opens without redirecting to Panopto sign-in. */
    fun panoptoSignedIn(rec: PanoptoRecording): Boolean = runCatching {
        http.newCall(req("${Panopto.origin(rec.viewerUrl)}/Panopto/Pages/Viewer.aspx?id=${rec.deliveryId}").build()).execute().use { r ->
            !(r.isRedirect && Panopto.isLoginRedirect(r.header("Location"))) && r.code != 401 && r.code != 403
        }
    }.getOrDefault(true)

    /** Real file name of a mod/resource (follows redirects to pluginfile.php without downloading the body). Null if unknown. */
    fun fileName(f: MoodleFile): String? = if (f.module == FOLDER_FILE) f.name else runCatching {
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
    fun download(f: MoodleFile, dir: File, maxBytes: Long = Long.MAX_VALUE): File {
        var u = if (f.module == FOLDER_FILE) f.url else "$base/mod/resource/view.php?id=${f.cmid}&redirect=1"
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
                val fromUrl = r.request.url.pathSegments.lastOrNull().orEmpty().replace("%", "%25")
                val name = safeName(fromUrl.ifBlank { f.name })
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in setOf("pdf", "pptx", "ppt") && !type.contains("pdf") && !type.contains("presentation"))
                    error("Not a PDF/PPTX: $name")
                val out = File(dir.apply { mkdirs() }, "${f.cmid}_$name")
                try {
                    r.body!!.byteStream().use { input -> out.outputStream().use { output ->
                        val buffer = ByteArray(8192); var total = 0L
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            total += n; require(total <= maxBytes) { "File exceeds matching download limit" }
                            output.write(buffer, 0, n)
                        }
                    } }
                } catch (e: Exception) { out.delete(); throw e }
                return out
            }
        }
        error("Too many redirects")
    }

    companion object {
        private val SHARED_HTTP = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        private val instances = java.util.concurrent.ConcurrentHashMap<String, MoodleClient>()
        /** Process-wide client per Moodle base: keeps sesskey (skips the /my/ page scrape), caches and pooled connections. */
        fun of(base: String): MoodleClient = instances.getOrPut(base) { MoodleClient(base) }
        const val DEFAULT_BASE = "https://moodle.tau.ac.il"
        val FILE_MODULES = setOf("resource", "folder")
        const val FOLDER_FILE = "folderfile"

        /** Parse a folder page: every pluginfile link under mod_folder/content, path after the revision segment. */
        fun parseFolderFiles(folder: MoodleFile, html: String): List<MoodleFile> =
            Regex("""href="([^"]*/pluginfile\.php/\d+/mod_folder/content/\d+/([^"?]+))[^"]*"""").findAll(html).map { m ->
                val url = unescape(m.groupValues[1])
                val rel = m.groupValues[2].split('/').map { runCatching { java.net.URLDecoder.decode(unescape(it).replace("+", "%2B"), "UTF-8") }.getOrDefault(it) }
                val name = (listOf(folder.name.trim()) + rel).filter { it.isNotBlank() }.joinToString(" / ")
                MoodleFile(-((folder.cmid * 31 + url.hashCode()).let { if (it == Long.MIN_VALUE) 1 else kotlin.math.abs(it) }), name, url, FOLDER_FILE)
            }.distinctBy { it.url }
                .filter { it.name.substringAfterLast('.').lowercase() in setOf("pdf", "pptx", "ppt") }.toList()
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

    /** University username only (not secret) — prefilled on the IdP form. Passwords are left to Android Autofill. */
    var username: String?
        get() = p.getString("username", null)?.takeIf { it.isNotBlank() }
        set(v) = p.edit().putString("username", v?.trim()).apply()

    /** Moodle course + Panopto recording a lecture was imported from: Attach-from-Moodle goes straight there. */
    fun lectureCourse(lectureId: String): Long? = p.getLong("lcourse:$lectureId", -1).takeIf { it > 0 }
    fun lectureRecording(lectureId: String): String? = p.getString("lrec:$lectureId", null)
    fun lectureBase(lectureId:String)=p.getString("lbase:$lectureId",null) ?: baseUrl
    fun setLectureSource(lectureId: String, courseId: Long, recordingTitle: String,site:String?=null) =
        p.edit().putLong("lcourse:$lectureId", courseId).putString("lrec:$lectureId", recordingTitle).putString("lbase:$lectureId",site ?: baseUrl).apply()

    fun mappedCourse(key: String): Long? = p.getLong("map:$key", -1).takeIf { it > 0 }
    fun mapCourse(key: String, id: Long) = p.edit().putLong("map:$key", id).apply()

    fun cacheDir(site:String)=File(cacheDir,"site-${site.hashCode().toUInt()}").apply{mkdirs()}
    private fun fileKey(cmid:Long,site:String)=if(site==baseUrl)"file:$cmid"else"file:${site.hashCode()}:$cmid"
    fun cached(cmid: Long,site:String=baseUrl): File? = p.getString(fileKey(cmid,site), null)?.let(::File)?.takeIf { it.exists() }
    fun putCached(cmid: Long, f: File,site:String=baseUrl) = p.edit().putString(fileKey(cmid,site), f.absolutePath).apply()
    fun isUsed(cmid: Long) = p.getBoolean("used:$cmid", false)
    fun markUsed(cmid: Long) = p.edit().putBoolean("used:$cmid", true).apply()
}
