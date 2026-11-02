package il.ac.tau.lecturesummarizer.moodle

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PanoptoTest {
    private fun res(n: String) = javaClass.classLoader!!.getResource(n).readText()

    @Test fun extractsRecordingsFromBlock() {
        val recs = Panopto.extractRecordings("<a href=\"https://tau.cloud.panopto.eu/Panopto/Pages/Viewer.aspx?id=99999999-2222-3333-4444-555555555555\">outside</a>" + res("panopto-block.html"))
        assertEquals(2, recs.size)
        assertEquals("7d763e9c-dc6f-4289-9a23-b4f500f6c660", recs[0].deliveryId)
        assertEquals("Lecture 3", recs[0].title)
        assertTrue(recs[0].viewerUrl.endsWith("&instance=moodle2026"))
        assertEquals("Recitation 2 & Q", recs[1].title)
    }

    @Test fun placeholderThenAjaxSingleQuotedWithShowAll() {
        val page = "<section class=\"block_panopto\"><h5>${Panopto.BLOCK_TITLE}</h5><div id=\"block_panopto_content\"><div id=\"loading_text\">מאחזר תוכן...</div></div></section>"
        assertTrue(Panopto.extractRecordings(page).isEmpty()); assertTrue(Panopto.isLoadingPlaceholder(page))
        val recs = Panopto.extractRecordings(res("panopto-ajax-block.html"))
        assertEquals(4, recs.size) // includes the hidden ones behind [תצוגה מלאה]
        assertEquals("11111111-aaaa-bbbb-cccc-000000000004", recs[3].deliveryId)
        assertEquals("שיעור 4 & תרגול", recs[3].title)
        assertTrue(recs[3].viewerUrl.endsWith("&instance=moodle"))
        assertFalse(Panopto.isLoadingPlaceholder(res("panopto-ajax-block.html")))
    }

    @Test fun captionsUrl() = assertEquals(
        "https://tau.cloud.panopto.eu/Panopto/Pages/Transcription/GenerateSRT.ashx?id=x&language=0", Panopto.captionsUrl("x"))

    @Test fun parsesSrtFixture() {
        val s = Panopto.parseSrt(res("panopto-sample.srt"))
        assertEquals(4, s.size)
        assertEquals(3.584, s[0].t0, 1e-6); assertEquals(5.134, s[0].t1, 1e-6)
        assertEquals("Jonathan Berant", s[0].speaker)
        assertEquals("לאוד כזה, לא?", s[0].text)
        assertFalse(s.any { it.text.contains("Auto-generated") })
    }

    @Test fun parsesCrlfAndHours() {
        val s = Panopto.parseSrt("\uFEFF1\r\n02:36:01,754 --> 02:36:03,424\r\nלהתראות\r\nשורה 2\r\n\r\n")
        assertEquals(1, s.size); assertEquals(9361.754, s[0].t0, 1e-6); assertEquals("להתראות שורה 2", s[0].text); assertNull(s[0].speaker)
    }

    /** Full real lecture file (box-local, skipped if absent). */
    @Test fun parsesFullLectureIfPresent() {
        val f = File("/workspace/panopto-lecture3-captions.srt"); if (!f.exists()) return
        val s = Panopto.parseSrt(f.readText())
        assertTrue(s.size > 800)
        assertTrue(s.last().t1 > 9360)
        assertTrue(s.zipWithNext().all { (a, b) -> b.t0 >= a.t0 })
    }

    @Test fun languageOfCaptions() {
        assertEquals("he", Panopto.languageOf(Panopto.parseSrt(res("panopto-sample.srt"))))
        assertEquals("en", Panopto.languageOf(listOf(CaptionSegment(0.0, 1.0, "hello world"))))
    }

    @org.junit.Test fun loginRedirectDetection() {
        org.junit.Assert.assertTrue(Panopto.isLoginRedirect("https://tau.cloud.panopto.eu/Panopto/Pages/Auth/Login.aspx?Auth=SessionView"))
        org.junit.Assert.assertFalse(Panopto.isLoginRedirect("/Panopto/Pages/Viewer.aspx?id=x"))
        org.junit.Assert.assertFalse(Panopto.isLoginRedirect(null))
    }
}
