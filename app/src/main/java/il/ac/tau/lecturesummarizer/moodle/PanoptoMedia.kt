package il.ac.tau.lecturesummarizer.moodle

import org.json.JSONObject

/** One stream in DeliveryInfo.Delivery.Streams (HLS master in StreamUrl; Variants when Panopto lists them). */
data class PanoptoStream(val url: String, val tag: String?, val variants: List<HlsVariant>)
data class HlsVariant(val bandwidth: Long, val url: String, val codecs: String? = null)
data class DeliveryInfo(val durationSec: Double, val hasCaptions: Boolean, val streams: List<PanoptoStream>, val podcastUrl: String?) {
    val hasAudio get() = streams.isNotEmpty() || podcastUrl != null
}
data class HlsSegment(val url: String, val durationSec: Double, val range: LongRange? = null)
data class HlsMedia(val initUrl: String?, val initRange: LongRange?, val segments: List<HlsSegment>)

/** Pure DeliveryInfo / HLS helpers for the no-captions audio fallback (unit-testable). */
object PanoptoMedia {
    const val DELIVERY_INFO = "${Panopto.HOST}/Panopto/Pages/Viewer/DeliveryInfo.aspx"
    const val WINDOW_SEC = 600.0

    fun deliveryInfoForm(deliveryId: String) =
        "deliveryId=$deliveryId&invocationId=&isLiveNotes=false&refreshAuthCookie=true&isActiveBroadcast=false&isEditing=false&isKollectiveAgentInstalled=false&isEmbed=false&responseType=json"

    /** Throws [IllegalStateException] with Panopto's ErrorMessage when the JSON is an error (e.g. not signed in). */
    fun parseDeliveryInfo(json: String): DeliveryInfo {
        val o = JSONObject(json)
        if (o.has("ErrorCode") && !o.has("Delivery")) error(o.optString("ErrorMessage", "Panopto DeliveryInfo error ${o.opt("ErrorCode")}"))
        val d = o.getJSONObject("Delivery")
        fun streams(key: String): List<PanoptoStream> {
            val a = d.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                val s = a.getJSONObject(i)
                val url = s.optString("StreamUrl").ifBlank { s.optString("StreamHttpUrl") }.ifBlank { return@mapNotNull null }
                val va = s.optJSONArray("Variants")
                val vars = if (va == null) emptyList() else (0 until va.length()).mapNotNull { j ->
                    val v = va.getJSONObject(j); val u = v.optString("Url").ifBlank { return@mapNotNull null }
                    HlsVariant(v.optLong("Bandwidth"), u, v.optString("Codecs").ifBlank { null })
                }
                PanoptoStream(url, s.optString("Tag").ifBlank { null }, vars)
            }
        }
        val podcast = streams("PodcastStreams").firstOrNull()?.url
        return DeliveryInfo(d.optDouble("Duration", 0.0).let { if (it.isNaN()) 0.0 else it }, d.optBoolean("HasCaptions"),
            streams("Streams").filter { isHls(it.url) || it.variants.isNotEmpty() }, podcast)
    }

    fun isHls(url: String) = url.substringBefore('?').let { it.contains(".hls/") || it.endsWith(".m3u8") || it.endsWith("master.panobf2") }

    /** Primary audio stream: the "dv" (main) one if tagged, else the first. Screen-only streams usually carry no audio. */
    fun pickStream(info: DeliveryInfo): PanoptoStream? =
        info.streams.firstOrNull { it.tag.equals("dv", true) } ?: info.streams.firstOrNull()

    fun resolve(base: String, ref: String): String {
        var s = java.net.URL(java.net.URL(base), ref).toString()
        // Panopto playlists may reference children without the signed query; carry the parent's query over.
        val q = base.substringAfter('?', "")
        if ('?' !in ref && q.isNotEmpty()) s = "$s?$q"
        return s
    }

    private val ATTR = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")
    private fun attrs(line: String) = ATTR.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    /** Variants of a master playlist (lowest bandwidth first) that carry audio. */
    fun parseMaster(text: String, base: String): List<HlsVariant> {
        val lines = text.lines().map { it.trim() }
        val out = mutableListOf<HlsVariant>()
        lines.forEachIndexed { i, l ->
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                val a = attrs(l)
                val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@forEachIndexed
                out += HlsVariant(a["BANDWIDTH"]?.toLongOrNull() ?: Long.MAX_VALUE, resolve(base, uri), a["CODECS"])
            }
        }
        return out.filter { it.codecs == null || it.codecs.contains("mp4a") || it.codecs.contains("opus") }.sortedBy { it.bandwidth }
    }

    /** Audio-only rendition (#EXT-X-MEDIA TYPE=AUDIO with URI), when Panopto provides one — far cheaper than muxed video. */
    fun audioRendition(text: String, base: String): String? = text.lines().map { it.trim() }
        .firstOrNull { it.startsWith("#EXT-X-MEDIA") && attrs(it)["TYPE"] == "AUDIO" && attrs(it)["URI"] != null }
        ?.let { resolve(base, attrs(it)["URI"]!!) }

    private fun range(spec: String, prevEnd: Long): LongRange {
        val len = spec.substringBefore('@').trim().toLong()
        val off = spec.substringAfter('@', "").trim().toLongOrNull() ?: prevEnd
        return off until off + len
    }

    fun parseMedia(text: String, base: String): HlsMedia {
        var init: String? = null; var initRange: LongRange? = null
        val segs = mutableListOf<HlsSegment>()
        var dur = 0.0; var br: LongRange? = null; var prevEnd = 0L
        for (raw in text.lines()) {
            val l = raw.trim()
            when {
                l.startsWith("#EXT-X-MAP") -> { val a = attrs(l); init = a["URI"]?.let { resolve(base, it) }; initRange = a["BYTERANGE"]?.let { range(it, 0) } }
                l.startsWith("#EXTINF:") -> dur = l.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                l.startsWith("#EXT-X-BYTERANGE:") -> br = range(l.removePrefix("#EXT-X-BYTERANGE:"), prevEnd)
                l.isEmpty() || l.startsWith("#") -> {}
                else -> { segs += HlsSegment(resolve(base, l), dur, br); br?.let { prevEnd = it.last + 1 }; br = null; dur = 0.0 }
            }
        }
        return HlsMedia(init, initRange, segs)
    }

    /** Groups segments into ~[windowSec] slices (whole segments; never empty). */
    fun windows(segs: List<HlsSegment>, windowSec: Double = WINDOW_SEC): List<List<HlsSegment>> {
        val out = mutableListOf<MutableList<HlsSegment>>(); var acc = 0.0
        for (s in segs) {
            if (out.isEmpty() || acc >= windowSec) { out += mutableListOf<HlsSegment>(); acc = 0.0 }
            out.last() += s; acc += s.durationSec
        }
        return out
    }

    /** Single byte range covering [win] when all segments are contiguous ranges of the same URL, else null. */
    fun mergeRanges(win: List<HlsSegment>): LongRange? {
        if (win.isEmpty() || win.any { it.range == null || it.url != win[0].url }) return null
        for (i in 1 until win.size) if (win[i].range!!.first != win[i - 1].range!!.last + 1) return null
        return win.first().range!!.first..win.last().range!!.last
    }

    /** Bytes the phone will download for the variant (bandwidth × duration), for the Wi-Fi/cellular warning. */
    fun estimateBytes(bandwidth: Long, durationSec: Double) = (bandwidth / 8.0 * durationSec).toLong()
}
