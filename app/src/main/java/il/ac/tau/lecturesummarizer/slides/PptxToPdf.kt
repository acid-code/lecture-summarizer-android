package il.ac.tau.lecturesummarizer.slides

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Best-effort PPTX → multi-page PDF for upload: full-slide rasters so the server figures
 * path can use PDF page renders (Vercel has no LibreOffice).
 * Not a PowerPoint clone — pictures + text runs positioned from OOXML when present.
 */
object PptxToPdf {
    private const val MAX_BYTES = 40L * 1024 * 1024
    private const val MAX_SLIDES = 250
    private const val PAGE_W = 1280
    /** Last convert failure (tests / diagnostics). */
    @Volatile var lastError: String? = null
        private set

    /** Writes [outPdf]. Returns false on unsupported/corrupt input (caller should upload the PPTX). */
    fun convert(pptx: File, outPdf: File): Boolean {
        lastError = null
        if (!pptx.isFile || pptx.length() <= 0L || pptx.length() > MAX_BYTES) {
            lastError = "bad file"; return false
        }
        if (!pptx.name.substringAfterLast('.', "").equals("pptx", true)) {
            lastError = "not pptx"; return false
        }
        return try {
            ZipFile(pptx).use { zip ->
                require(zip.size() <= 10_000)
                val order = slideOrder(zip)
                if (order.isEmpty()) { lastError = "no slides"; return false }
                val (slideCx, slideCy) = slideSizeEmu(zip)
                val pageH = max(720, (PAGE_W * slideCy.toDouble() / slideCx).roundToInt())
                // PDF page in points (~1/72"); keep aspect, ~10" wide.
                val pdfW = 720f
                val pdfH = pdfW * pageH / PAGE_W
                PDDocument().use { doc ->
                    order.take(MAX_SLIDES).forEach { slidePath ->
                        val bmp = renderSlide(zip, slidePath, PAGE_W, pageH, slideCx, slideCy)
                        try {
                            val page = PDPage(PDRectangle(pdfW, pdfH))
                            doc.addPage(page)
                            val img = LosslessFactory.createFromImage(doc, bmp)
                            PDPageContentStream(doc, page).use { cs ->
                                cs.drawImage(img, 0f, 0f, pdfW, pdfH)
                            }
                        } finally {
                            bmp.recycle()
                        }
                    }
                    outPdf.parentFile?.mkdirs()
                    val tmp = File(outPdf.parentFile, "${outPdf.name}.tmp")
                    doc.save(tmp)
                    if (!tmp.renameTo(outPdf)) {
                        outPdf.delete()
                        tmp.copyTo(outPdf, overwrite = true)
                        tmp.delete()
                    }
                }
                val ok = outPdf.length() > 200
                if (!ok) lastError = "pdf too small (${outPdf.length()})"
                ok
            }
        } catch (e: Exception) {
            outPdf.delete()
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            false
        }
    }

    private fun slideOrder(zip: ZipFile): List<String> {
        fun read(path: String): String {
            val e = zip.getEntry(path) ?: return ""
            return zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
        }
        fun attrs(tag: String) = Regex("""([\w:]+)\s*=\s*["']([^"']*)["']""").findAll(tag)
            .associate { it.groupValues[1] to it.groupValues[2] }
        val rels = Regex("<Relationship\\b[^>]*>").findAll(read("ppt/_rels/presentation.xml.rels"))
            .map { attrs(it.value) }.associate { it["Id"].orEmpty() to it["Target"].orEmpty() }
        val declared = Regex("<p:sldId\\b[^>]*>").findAll(read("ppt/presentation.xml"))
            .mapNotNull { attrs(it.value)["r:id"]?.let(rels::get) }
            .map { if (it.startsWith("/")) it.removePrefix("/") else if (it.startsWith("ppt/")) it else "ppt/$it" }
            .toList()
        return declared.ifEmpty {
            zip.entries().asSequence().map { it.name }
                .filter { Regex("ppt/slides/slide\\d+\\.xml").matches(it) }
                .sortedBy { Regex("\\d+").find(it)!!.value.toInt() }
                .toList()
        }
    }

    private fun slideSizeEmu(zip: ZipFile): Pair<Long, Long> {
        val xml = zip.getEntry("ppt/presentation.xml")?.let { e ->
            zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
        }.orEmpty()
        val m = Regex("""<p:sldSz\b[^>]*cx="(\d+)"[^>]*cy="(\d+)"|<p:sldSz\b[^>]*cy="(\d+)"[^>]*cx="(\d+)"""").find(xml)
        return when {
            m == null -> 12_192_000L to 6_858_000L
            m.groupValues[1].isNotEmpty() -> m.groupValues[1].toLong() to m.groupValues[2].toLong()
            else -> m.groupValues[4].toLong() to m.groupValues[3].toLong()
        }
    }

    private fun renderSlide(zip: ZipFile, slidePath: String, w: Int, h: Int, slideCx: Long, slideCy: Long): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val slideXml = readEntry(zip, slidePath)
        val fileName = slidePath.substringAfterLast('/')
        val relXml = readEntry(zip, "ppt/slides/_rels/$fileName.rels")
        val relTargets = Regex("<Relationship\\b[^>]*>").findAll(relXml).map { tag ->
            val id = Regex("""Id="([^"]+)"""").find(tag.value)?.groupValues?.get(1)
            val target = Regex("""Target="([^"]+)"""").find(tag.value)?.groupValues?.get(1)
            id to target
        }.filter { it.first != null && it.second != null }.associate { it.first!! to it.second!! }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val pics = Regex("""<p:pic\b[\s\S]*?</p:pic>""").findAll(slideXml).toList()
        for (pic in pics) {
            val embed = Regex("""r:embed="([^"]+)"""").find(pic.value)?.groupValues?.get(1) ?: continue
            val target = relTargets[embed] ?: continue
            val mediaPath = resolveRel("ppt/slides", target)
            val bytes = readBytes(zip, mediaPath) ?: continue
            if (bytes.size < 64) continue
            val img = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
            try {
                val off = Regex("""<a:off\b[^>]*x="(\d+)"[^>]*y="(\d+)"|<a:off\b[^>]*y="(\d+)"[^>]*x="(\d+)"""").find(pic.value)
                val ext = Regex("""<a:ext\b[^>]*cx="(\d+)"[^>]*cy="(\d+)"|<a:ext\b[^>]*cy="(\d+)"[^>]*cx="(\d+)"""").find(pic.value)
                val (xEmu, yEmu) = when {
                    off == null -> 0L to 0L
                    off.groupValues[1].isNotEmpty() -> off.groupValues[1].toLong() to off.groupValues[2].toLong()
                    else -> off.groupValues[4].toLong() to off.groupValues[3].toLong()
                }
                val (cxEmu, cyEmu) = when {
                    ext == null -> slideCx to slideCy
                    ext.groupValues[1].isNotEmpty() -> ext.groupValues[1].toLong() to ext.groupValues[2].toLong()
                    else -> ext.groupValues[4].toLong() to ext.groupValues[3].toLong()
                }
                val left = emuToPx(xEmu, slideCx, w).toFloat()
                val top = emuToPx(yEmu, slideCy, h).toFloat()
                val rw = max(1f, emuToPx(cxEmu, slideCx, w).toFloat())
                val rh = max(1f, emuToPx(cyEmu, slideCy, h).toFloat())
                canvas.drawBitmap(img, null, RectF(left, top, left + rw, top + rh), paint)
            } finally {
                img.recycle()
            }
        }

        if (pics.isEmpty()) {
            val media = relTargets.values.mapNotNull { t ->
                val p = resolveRel("ppt/slides", t)
                if (!p.contains("/media/", ignoreCase = true)) null
                else readBytes(zip, p)
            }.filter { it.size >= 8_000 }.maxByOrNull { it.size }
            if (media != null) {
                BitmapFactory.decodeByteArray(media, 0, media.size)?.let { img ->
                    try {
                        val scale = min(w.toFloat() / img.width, h.toFloat() / img.height)
                        val dw = img.width * scale
                        val dh = img.height * scale
                        val left = (w - dw) / 2f
                        val top = (h - dh) / 2f
                        canvas.drawBitmap(img, null, RectF(left, top, left + dw, top + dh), paint)
                    } finally {
                        img.recycle()
                    }
                }
            }
        }

        val texts = Regex("""<a:t(?:\s[^>]*)?>(.*?)</a:t>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(slideXml)
            .map {
                it.groupValues[1]
                    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                    .replace(Regex("\\s+"), " ").trim()
            }
            .filter { it.isNotEmpty() }
            .take(40)
            .toList()
        if (texts.isNotEmpty()) {
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(30, 41, 59)
                textSize = 22f
                typeface = Typeface.SANS_SERIF
            }
            var y = h * 0.72f
            val maxY = h - 24f
            val lineH = textPaint.fontSpacing
            for (line in texts) {
                if (y > maxY) break
                val clipped = if (textPaint.measureText(line) <= w - 48f) line else {
                    var end = line.length
                    while (end > 8 && textPaint.measureText(line.take(end) + "…") > w - 48f) end--
                    line.take(end) + "…"
                }
                canvas.drawText(clipped, 24f, y, textPaint)
                y += lineH
            }
        }
        return bmp
    }

    private fun emuToPx(emu: Long, slideEmu: Long, px: Int): Int =
        if (slideEmu <= 0) 0 else ((emu.toDouble() / slideEmu) * px).roundToInt().coerceIn(0, px * 2)

    private fun resolveRel(fromDir: String, target: String): String {
        val cleaned = target.replace('\\', '/')
        if (cleaned.startsWith("/")) return cleaned.trimStart('/')
        val base = fromDir.trimEnd('/').split('/').toMutableList()
        for (part in cleaned.split('/')) when (part) {
            "", "." -> {}
            ".." -> if (base.isNotEmpty()) base.removeAt(base.lastIndex)
            else -> base.add(part)
        }
        return base.joinToString("/")
    }

    private fun readEntry(zip: ZipFile, path: String): String {
        val e = zip.getEntry(path) ?: return ""
        return zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8).take(2_000_000) }
    }

    private fun readBytes(zip: ZipFile, path: String): ByteArray? {
        val e = zip.getEntry(path) ?: return null
        require(e.size <= 8L * 1024 * 1024)
        return zip.getInputStream(e).use { it.readBytes() }
    }
}
