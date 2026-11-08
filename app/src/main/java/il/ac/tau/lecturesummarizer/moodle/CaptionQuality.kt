package il.ac.tau.lecturesummarizer.moodle

/**
 * Decide whether Panopto captions are good enough to summarize, or we should cascade to audio.
 * Pure (no Android) so unit tests can lock the thresholds.
 */
object CaptionQuality {
    /** Videos at or above this are "long" — thin captions must not produce a study PDF. */
    const val LONG_VIDEO_SEC = 15 * 60.0
    /** Caption end must cover at least this fraction of video length on long recordings. */
    const val MIN_COVERAGE = 0.25
    /** Minimum non-whitespace caption characters on a long recording. */
    const val MIN_CHARS = 800

    fun captionChars(segs: List<CaptionSegment>): Int =
        segs.sumOf { it.text.trim().length }

    /** Last cue end (seconds) — span of the SRT timeline. */
    fun captionEndSec(segs: List<CaptionSegment>): Double =
        segs.maxOfOrNull { it.t1 } ?: 0.0

    /**
     * @return true → safe to POST transcript/import; false → fall back to video/audio (or error if no audio).
     * Short video + any non-empty captions: allowed (duration shown in UI for the user).
     * Unknown video length: reject only empty / near-empty text (&lt; 200 chars).
     */
    fun captionsUsable(segs: List<CaptionSegment>, videoDurationSec: Double?): Boolean {
        if (segs.isEmpty()) return false
        val chars = captionChars(segs)
        val video = videoDurationSec?.takeIf { it.isFinite() && it > 0 }
        if (video == null) return chars >= 200
        if (video < LONG_VIDEO_SEC) return chars > 0
        if (chars < MIN_CHARS) return false
        if (captionEndSec(segs) < video * MIN_COVERAGE) return false
        return true
    }

    /** Hebrew-friendly short duration for list rows / errors, e.g. "47 דק׳", "1:05 שע׳". */
    fun formatDurationHe(sec: Double): String {
        val s = sec.toInt().coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        return if (h > 0) String.format("%d:%02d שע׳", h, m) else String.format("%d דק׳", m)
    }
}
