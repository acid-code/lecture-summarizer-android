package il.ac.tau.lecturesummarizer.moodle

import org.junit.Assert.*
import org.junit.Test

class CaptionQualityTest {
    private fun segs(vararg texts: String, end: Double = 60.0) =
        texts.mapIndexed { i, t -> CaptionSegment(i * 1.0, end, t) }

    @Test fun emptyRejected() {
        assertFalse(CaptionQuality.captionsUsable(emptyList(), 3600.0))
    }

    @Test fun shortVideoAllowsSparseCaptions() {
        assertTrue(CaptionQuality.captionsUsable(segs("שלום"), videoDurationSec = 120.0))
    }

    @Test fun longVideoRejectsTinyText() {
        assertFalse(CaptionQuality.captionsUsable(segs("קצר"), videoDurationSec = 3600.0))
    }

    @Test fun longVideoRejectsShortSpan() {
        val text = "א".repeat(900)
        assertFalse(CaptionQuality.captionsUsable(segs(text, end = 60.0), videoDurationSec = 3600.0))
    }

    @Test fun longVideoAcceptsGoodCoverage() {
        val text = "מילה ".repeat(200).trim()
        assertTrue(CaptionQuality.captionsUsable(segs(text, end = 2000.0), videoDurationSec = 3600.0))
    }

    @Test fun unknownDurationRejectsNearEmpty() {
        assertFalse(CaptionQuality.captionsUsable(segs("x"), videoDurationSec = null))
        assertTrue(CaptionQuality.captionsUsable(segs("y".repeat(200)), videoDurationSec = null))
    }

    @Test fun formatDuration() {
        assertEquals("47 דק׳", CaptionQuality.formatDurationHe(47 * 60.0))
        assertEquals("1:05 שע׳", CaptionQuality.formatDurationHe(65 * 60.0))
    }
}
