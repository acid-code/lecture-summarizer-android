# Auto-attach Moodle slides (auto-sync)

**Goal:** when auto-sync imports a Panopto recording, attach the matching Moodle PDF/PPTX automatically if the match is near-certain; otherwise leave it to the manual ⭐ Suggested picker.

## Signals (reused from manual attach)
1. **Course** – the Moodle course the recording was found in (`MoodleMatch.rank/confident/siblings`, already lecture `…01` vs recitation `…09`).
2. **Lecture number + kind** – `MoodleMatch.matchesRecording` ("Lecture 3" ↔ `lec_03.pdf`, `שיעור 3`; opposite kind rejected).
3. **Date** – `MoodleMatch.suggested` (d.M in file name), used only when the recording title has no number.

## Rule (`MoodleMatch.autoPick`)
- Pre-filter by Moodle activity title (cheap), resolve real file names (`client.fileName`, ≤6 HEAD chains).
- Keep PDF/PPT/PPTX only, not already used (`MoodleStore.isUsed`).
- Exactly **one** distinct file stem must remain; a same-stem PDF+PPTX pair counts as one (PDF wins). Any ambiguity → no auto-attach.
- Download via shared cache (`MoodleStore.cached/putCached`), `markUsed`, add to lecture slides **before** the captions job starts so the summary includes them.
- Best-effort: errors (except Moodle auth) never block the import. Pref `AutoSyncStore.autoAttach` (default on; no UI toggle yet).

## Next batch (not in this commit)
- **Battery:** before a pass, if not charging and <75% → notification asking to continue (like the cellular ask); stop/defer the queue under 30% (`BatteryManager`, re-enqueue with `setRequiresCharging` or battery-not-low).
- **Notifications:** include lecture name ("Lecture 3 · Algorithms – downloading audio"), plus "slides attached: lec3.pdf" line.
- **Single progress:** one ongoing foreground notification for the whole auto-sync queue ("2/5 · <lecture>") instead of one per worker; per-lecture workers update it via a shared id.
