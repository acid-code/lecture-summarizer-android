package il.ac.tau.lecturesummarizer.moodle

import org.junit.Assert.*
import org.junit.Test

class FolderFilesTest {
    private val html = """
      <li><span class="fp-filename">תרגול 01</span><ul>
      <li><a href="https://moodle.tau.ac.il/pluginfile.php/123/mod_folder/content/0/%D7%AA%D7%A8%D7%92%D7%95%D7%9C%2001/alg1%202027a%20-%20Euler.pdf?forcedownload=1"><span class="fp-filename">alg1 2027a - Euler.pdf</span></a></li>
      <li><a href="https://moodle.tau.ac.il/pluginfile.php/123/mod_folder/content/0/%D7%AA%D7%A8%D7%92%D7%95%D7%9C%2001/alg1%202027a%20-%20Euler.pptx?forcedownload=1">x</a></li></ul></li>
      <li><a href="https://moodle.tau.ac.il/pluginfile.php/123/mod_folder/content/0/%D7%AA%D7%A8%D7%92%D7%95%D7%9C%2002/alg2%20BFS.pdf?forcedownload=1">y</a></li>
      <li><a href="https://moodle.tau.ac.il/pluginfile.php/123/mod_folder/content/0/%D7%AA%D7%A8%D7%92%D7%95%D7%9C%2003/alg3%20DFS.pdf">z</a></li>
      <li><a href="https://moodle.tau.ac.il/pluginfile.php/123/mod_folder/content/0/readme.txt">t</a></li>
    """

    @Test fun parsesNestedFiles() {
        val files = MoodleClient.parseFolderFiles(MoodleFile(555, "תירגולים", "", "folder"), html)
        assertEquals(4, files.size)
        assertEquals("תירגולים / תרגול 01 / alg1 2027a - Euler.pdf", files[0].name)
        assertTrue(files.all { it.cmid < 0 && it.module == MoodleClient.FOLDER_FILE })
        assertEquals(files.size, files.map { it.cmid }.distinct().size)
    }

    @Test fun folderNumberDrivesMatching() {
        val files = MoodleClient.parseFolderFiles(MoodleFile(555, "תירגולים", "", "folder"), html)
        val bfs = files.first { it.name.contains("BFS") }
        assertTrue(MoodleMatch.matchesRecording(bfs.name, "תרגול 2"))
        assertFalse(MoodleMatch.matchesRecording(bfs.name, "תרגול 1"))
        val pick = MoodleMatch.autoPick(files.map { it to it.name }, "Tutorial 1", null)
        assertTrue(pick!!.name.endsWith("Euler.pdf"))
    }
}

class HvpTest {
    @Test fun extractsEmbeddedPanoptoId() {
        val iframe = """<iframe src="https://tau.cloud.panopto.eu/Panopto/Pages/Embed.aspx?id=de222bf9-e5ae-4924-a837-b37d0085197e&amp;autoplay=false"></iframe>"""
        assertEquals("de222bf9-e5ae-4924-a837-b37d0085197e", Panopto.embeddedSessionId(iframe))
        val json = """H5PIntegration = {"x":"<iframe src=\"https:\/\/tau.cloud.panopto.eu\/Panopto\/Pages\/Embed.aspx?v=1&id=DE222BF9-E5AE-4924-A837-B37D0085197E\">"}"""
        assertEquals("de222bf9-e5ae-4924-a837-b37d0085197e", Panopto.embeddedSessionId(json))
        assertNull(Panopto.embeddedSessionId("<p>no video</p>"))
    }
    @Test fun hvpLinksFromCoursePage() {
        val html = """<a href="https://moodle.tau.ac.il/mod/hvp/view.php?id=221010" class="aalink"><span class="instancename">שיעור 5 - תקשורת: נוירוטרנסמיטורים</span></a>"""
        assertEquals(listOf(221010L to "שיעור 5 - תקשורת: נוירוטרנסמיטורים"), Panopto.extractHvpActivities(html))
    }
}
