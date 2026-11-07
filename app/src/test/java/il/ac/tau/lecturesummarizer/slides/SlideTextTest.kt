package il.ac.tau.lecturesummarizer.slides

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SlideTextTest {
    @Test fun pptxExtractsHebrewAndEnglishTextButNotMedia() {
        val f = File.createTempFile("slides", ".pptx")
        try {
            ZipOutputStream(f.outputStream()).use { z ->
                z.putNextEntry(ZipEntry("ppt/slides/slide1.xml")); z.write("<a:t>אנזימים &amp; plasmid</a:t>".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("ppt/notesSlides/notesSlide1.xml")); z.write("<a:t>restriction</a:t>".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("ppt/media/image1.bin")); z.write("<a:t>not text</a:t>".toByteArray()); z.closeEntry()
            }
            assertTrue(SlideText.pptx(f).contains("אנזימים & plasmid"))
            assertTrue(SlideText.pptx(f).contains("restriction"))
            assertFalse(SlideText.pptx(f).contains("not text"))
        } finally { f.delete() }
    }
    @Test(expected = IllegalArgumentException::class) fun expandedXmlIsBounded() {
        val f = File.createTempFile("large-slides", ".pptx")
        try {
            ZipOutputStream(f.outputStream()).use { z -> z.putNextEntry(ZipEntry("ppt/slides/slide1.xml")); z.write(ByteArray(2 * 1024 * 1024 + 1)); z.closeEntry() }
            SlideText.pptx(f)
        } finally { f.delete() }
    }
}
