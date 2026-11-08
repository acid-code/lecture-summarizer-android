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
    @Test fun pagesFollowPresentationOrderAndKeepNotesWithTheirSlide(){
        val f=File.createTempFile("ordered-slides",".pptx")
        try{ZipOutputStream(f.outputStream()).use{z->
            val files=mapOf("ppt/slides/slide1.xml" to "<a:t>first physical</a:t>","ppt/slides/slide2.xml" to "<a:t>second physical</a:t>",
                "ppt/presentation.xml" to "<p:sldId r:id=\"r2\"/><p:sldId r:id=\"r1\"/>",
                "ppt/_rels/presentation.xml.rels" to "<Relationship Id=\"r1\" Target=\"slides/slide1.xml\"/><Relationship Id=\"r2\" Target=\"slides/slide2.xml\"/>",
                "ppt/slides/_rels/slide2.xml.rels" to "<Relationship Type=\"http://example/notesSlide\" Target=\"../notesSlides/notesSlide9.xml\"/>","ppt/notesSlides/notesSlide9.xml" to "<a:t>notes second</a:t>")
            files.forEach{(name,text)->z.putNextEntry(ZipEntry(name));z.write(text.toByteArray());z.closeEntry()}
        };val pages=SlideText.pptxPages(f);assertEquals(2,pages.size);assertTrue(pages[0].contains("second physical"));assertTrue(pages[0].contains("notes second"));assertTrue(pages[1].contains("first physical"));assertFalse(pages[1].contains("notes second"))}finally{f.delete()}
    }
    @Test fun missingPresentationOrderUsesNumericSlidesNotZipOrder(){
        val f=File.createTempFile("numeric-slides",".pptx")
        try{ZipOutputStream(f.outputStream()).use{z->listOf(10,2,1).forEach{n->z.putNextEntry(ZipEntry("ppt/slides/slide$n.xml"));z.write("<a:t>page$n</a:t>".toByteArray());z.closeEntry()}};assertEquals(listOf("page1","page2","page10"),SlideText.pptxPages(f).map{it.trim()})}finally{f.delete()}
    }
}
