package il.ac.tau.lecturesummarizer.slides

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.zip.ZipFile

object SlideText {
    /** No OCR in v1: scanned PDFs yield no match rather than guessing from their names. */
    fun read(context: Context, file: File): String {
        require(file.length() <= 40L * 1024 * 1024) { "Deck too large for on-device matching" }
        if (file.extension.equals("pdf", true)) {
            PDFBoxResourceLoader.init(context.applicationContext)
            return PDDocument.load(file).use { doc ->
                if (doc.isEncrypted || !doc.currentAccessPermission.canExtractContent()) ""
                else PDFTextStripper().apply { endPage = minOf(doc.numberOfPages, 250) }.getText(doc).take(300_000)
            }
        }
        if (!file.extension.equals("pptx", true)) return ""
        return pptx(file)
    }
    internal fun pptx(file: File): String = ZipFile(file).use { zip ->
        require(zip.size() <= 10_000) { "Too many ZIP entries" }
        val out = StringBuilder()
        val entries = zip.entries()
        var bytes = 0
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            if (!Regex("ppt/(slides/slide|notesSlides/notesSlide)\\d+\\.xml").matches(e.name)) continue
            val data = zip.getInputStream(e).use { input ->
                val buffer = ByteArray(8192)
                val output = java.io.ByteArrayOutputStream()
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    require(output.size() + n <= 2 * 1024 * 1024) { "Slide XML too large" }
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
            bytes += data.size
            require(data.size <= 2 * 1024 * 1024 && bytes <= 12 * 1024 * 1024) { "Expanded deck too large" }
            Regex("<a:t(?:\\s[^>]*)?>(.*?)</a:t>", RegexOption.DOT_MATCHES_ALL).findAll(data.toString(Charsets.UTF_8)).forEach {
                if (out.length < 300_000) out.append(it.groupValues[1].replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")).append(' ')
            }
        }
        out.toString().take(300_000)
    }
}
