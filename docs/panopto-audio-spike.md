# Spike: audio-only download from TAU Panopto (design note, not implemented)

Endpoint: POST https://tau.cloud.panopto.eu/Panopto/Pages/Viewer/DeliveryInfo.aspx
body `deliveryId=<id>&responseType=json` with the same .ASPXAUTH session cookie we already use for GenerateSRT.
Response: `Delivery.Duration` (seconds), `Delivery.PodcastStreams[0].StreamUrl` (combined MP4 "podcast", often audio+video),
`Delivery.Streams[].StreamUrl` (per-source, frequently HLS .m3u8). No true audio-only MP3 is guaranteed; audio-only requires
fetching the MP4/HLS and demuxing (MediaExtractor/MediaMuxer on device, or ffmpeg server-side).

Feasibility: yes, technically, if the course allows viewing/download (podcast may be disabled per folder; URLs are signed/expiring).
Cost vs captions: 90-min lecture podcast MP4 ≈ 150–600 MB (HLS audio track ~40–90 MB) + upload + ASR minutes, vs ~100 KB SRT and no ASR.
Recommendation: keep captions as default. Audio path only as a fallback when captions are missing/poor: fetch HLS audio-only rendition
server-side (yt-dlp style) rather than on phone. Also: `Delivery.Duration` is a better duration source than last caption end — use it if we
ever call DeliveryInfo.
