package il.ac.tau.lecturesummarizer.moodle

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PanoptoMediaTest {
    private val base = "https://cdn.example/sessions/x/abc.hls/master.panobf2?InvocationID=1&StreamID=2"

    @Test fun parsesDeliveryInfoSample() {
        val f = File("/workspace/panopto-deliveryinfo-algorithms.json")
        val json = if (f.exists()) f.readText() else """{"Delivery":{"Duration":8162.6,"HasCaptions":false,"Streams":[{"Tag":"dv","StreamUrl":"$base","Variants":[{"Bandwidth":2759503,"Url":"https://cdn.example/a/1.panobf2"},{"Bandwidth":650862,"Url":"https://cdn.example/a/3.panobf2"}]}],"PodcastStreams":[{"StreamUrl":"https://cdn.example/p.panobf1"}]}}"""
        val d = PanoptoMedia.parseDeliveryInfo(json)
        assertEquals(8162.6, d.durationSec, 1.0)
        assertTrue(d.hasAudio)
        val s = PanoptoMedia.pickStream(d)!!
        assertTrue(PanoptoMedia.isHls(s.url))
    }

    @Test fun deliveryInfoError() {
        val e = runCatching { PanoptoMedia.parseDeliveryInfo("""{"ErrorCode":4,"ErrorMessage":"You are not authorized"}""") }.exceptionOrNull()
        assertEquals("You are not authorized", e?.message)
    }

    @Test fun masterPicksLowestAudioVariant() {
        val m = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=2759503,AVERAGE-BANDWIDTH=2111000,CODECS="avc1.4d002a,mp4a.40.2",RESOLUTION=1920x1080
2759503/index.panobf2
#EXT-X-STREAM-INF:BANDWIDTH=750000,CODECS="avc1.4d002a,mp4a.40.2",RESOLUTION=960x540
646755/index.panobf2
#EXT-X-STREAM-INF:BANDWIDTH=1300000,CODECS="avc1.4d002a,mp4a.40.2"
1137/index.panobf2
"""
        val v = PanoptoMedia.parseMaster(m, base)
        assertEquals(750000L, v.first().bandwidth)
        assertEquals("https://cdn.example/sessions/x/abc.hls/646755/index.panobf2?InvocationID=1&StreamID=2", v.first().url)
        assertNull(PanoptoMedia.audioRendition(m, base))
    }

    @Test fun audioRenditionPreferred() {
        val m = "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"en\",URI=\"aud/index.m3u8\"\n"
        assertEquals("https://cdn.example/sessions/x/abc.hls/aud/index.m3u8?InvocationID=1&StreamID=2", PanoptoMedia.audioRendition(m, base))
    }

    @Test fun mediaPlaylistAndWindows() {
        val sb = StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXT-X-MAP:URI=\"init.panobf3\"\n")
        repeat(250) { sb.append("#EXTINF:10.0,\nseg$it.panobf3?x=1\n") }
        sb.append("#EXT-X-ENDLIST\n")
        val med = PanoptoMedia.parseMedia(sb.toString(), "https://cdn.example/a/646755/index.panobf2?sig=z")
        assertEquals("https://cdn.example/a/646755/init.panobf3?sig=z", med.initUrl)
        assertEquals(250, med.segments.size)
        assertEquals("https://cdn.example/a/646755/seg0.panobf3?x=1", med.segments[0].url)
        val w = PanoptoMedia.windows(med.segments)
        assertEquals(5, w.size) // 2500 s → 600,600,600,600,100
        assertEquals(60, w[0].size)
        assertEquals(10, w.last().size)
    }

    @Test fun byteRanges() {
        val med = PanoptoMedia.parseMedia("#EXTINF:6,\n#EXT-X-BYTERANGE:100@0\nf.ts\n#EXTINF:6,\n#EXT-X-BYTERANGE:50\nf.ts\n", "https://c/a/i.m3u8")
        assertEquals(0L..99L, med.segments[0].range)
        assertEquals(100L..149L, med.segments[1].range)
    }
}

class PanoptoMediaRealPlaylistTest {
    @Test fun panoptoFragmentedPlaylist() {
        val f = java.io.File("/tmp/med.m3u8"); if (!f.exists()) return
        val m = PanoptoMedia.parseMedia(f.readText(), "https://cdn/x.hls/646755/index.panobf2?sig=1")
        assertEquals(0L..1421L, m.initRange)
        assertEquals(908, m.segments.size)
        val w = PanoptoMedia.windows(m.segments)
        assertEquals(14, w.size)
        assertNotNull(PanoptoMedia.mergeRanges(w[1]))
        assertEquals(1422L, PanoptoMedia.mergeRanges(w[0])!!.first)
    }
}
