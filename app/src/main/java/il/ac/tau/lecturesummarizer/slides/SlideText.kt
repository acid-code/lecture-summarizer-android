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
    /** Preserve source page/slide order (including empty pages); never invent ranges from flattened text. */
    fun pages(context:Context,file:File):List<String>{
        require(file.length()<=40L*1024*1024)
        if(file.extension.equals("pptx",true))return pptxPages(file)
        if(!file.extension.equals("pdf",true))return emptyList()
        PDFBoxResourceLoader.init(context.applicationContext)
        return PDDocument.load(file).use{doc->
            if(doc.isEncrypted || !doc.currentAccessPermission.canExtractContent())emptyList()else{
                var remaining=300_000
                (1..minOf(doc.numberOfPages,250)).map{page->PDFTextStripper().apply{startPage=page;endPage=page}.getText(doc).take(remaining.coerceAtLeast(0)).also{remaining-=it.length}}
            }
        }
    }
    internal fun pptxPages(file:File):List<String> = ZipFile(file).use{zip->
        require(zip.size()<=10_000);var bytes=0
        fun read(path:String):String{
            val entry=zip.getEntry(path) ?: return ""
            return zip.getInputStream(entry).use{input->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=2*1024*1024 && bytes+n<=12*1024*1024);out.write(buffer,0,n);bytes+=n};out.toString("UTF-8")}
        }
        fun attrs(tag:String)=Regex("""([\w:]+)\s*=\s*["']([^"']*)["']""").findAll(tag).associate{it.groupValues[1] to it.groupValues[2]}
        fun text(xml:String)=Regex("<a:t(?:\\s[^>]*)?>(.*?)</a:t>",RegexOption.DOT_MATCHES_ALL).findAll(xml).joinToString(" "){it.groupValues[1].replace("&amp;","&").replace("&lt;","<").replace("&gt;",">")}
        val rels=Regex("<Relationship\\b[^>]*>").findAll(read("ppt/_rels/presentation.xml.rels")).map{attrs(it.value)}.associate{it["Id"].orEmpty() to it["Target"].orEmpty()}
        val declared=Regex("<p:sldId\\b[^>]*>").findAll(read("ppt/presentation.xml")).mapNotNull{attrs(it.value)["r:id"]?.let(rels::get)}.map{if(it.startsWith("/"))it.removePrefix("/")else "ppt/$it"}.toList()
        val order=declared.ifEmpty{zip.entries().asSequence().map{it.name}.filter{Regex("ppt/slides/slide\\d+\\.xml").matches(it)}.sortedBy{Regex("\\d+").find(it)!!.value.toInt()}.toList()}
        var remaining=300_000
        order.take(250).map{path->
            val slide=text(read(path));val fileName=path.substringAfterLast('/')
            val notes=Regex("<Relationship\\b[^>]*>").findAll(read("ppt/slides/_rels/$fileName.rels")).map{attrs(it.value)}.firstOrNull{it["Type"].orEmpty().endsWith("/notesSlide")}?.get("Target")?.let{target->
                val resolved=java.nio.file.Paths.get("ppt/slides").resolve(target).normalize().toString();if(resolved.startsWith("ppt/notesSlides/"))text(read(resolved))else ""
            }.orEmpty()
            "$slide $notes".take(remaining.coerceAtLeast(0)).also{remaining-=it.length}
        }
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
