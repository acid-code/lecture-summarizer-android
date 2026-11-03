# Auto-download & summarize (Pro): retro summarizer + live recordings watch

Settings → **הורדה וסיכום אוטומטיים · Auto-download** (PRO badge). Code: `autosync/`, `data/Entitlements.kt`, `ui/AutoSyncSettings.kt`.

## Pro gating (subscription from day one)
- `Entitlements` is the single source of truth (`isPro`, `has(ProFeature)`, `source`). Checked in the UI **and** in `AutoSyncWorker`/`schedule()` so a lapsed sub stops background work.
- Locked state: switches tap → "Upgrade to Pro" dialog. Debug/beta builds offer **Activate beta Pro** (`source=beta`). Release builds show "coming soon".
- Later: Play Billing (or server-verified entitlement) calls `setPro(true, "play:<order>")`; nothing else changes.

## Pipeline (per user/device, one WorkManager queue)
1. **Scan** `CalendarResolver.pastClasses` (TAU-scheme events; chosen calendars) for the last N days (retro: 30/60/120/180, default 120; live-only: 14).
2. **Skip-if-exists**: notebook found by course number → name; a lecture on the same local day (Asia/Jerusalem) with the same kind (title שיעור/תרגיל, unknown = match) ⇒ `skip`. A recording already imported (same Panopto deliveryId) ⇒ `skip`.
3. **Plan** (`AutoSyncPlanner`, pure + tested): drop final statuses (done/skip/notfound), keep `wait` only inside the live window, **sort newest first** so rolling lectures beat the retro backlog.
4. **Find recording** (`RecordingMatcher`, pure + tested): Moodle course via `MoodleMatch` (…01 lecture / …09 recitation + same-prefix siblings); recording must have the **class date in its title** (d/m[/y], d.m.yy, yyyy-mm-dd), must not be the opposite kind; +score for same kind and for **lecturer/TA name from the event notes** (`מרצה:`/`מתרגל:`).
5. **Import**: new lecture in the notebook with `recordedAtMs = class start` (notebook list is sorted newest on top), title `שיעור dd/MM` / `תרגיל dd/MM`. Captions → `/transcript/import` (headless `PanoptoImporter`); else on-phone audio via `PanoptoAudioWorker` (existing flow).
6. **Throttle**: ≤2 lectures processing/extracting at once, ≤3 new imports per pass; rest stays queued for the next pass.
7. Not found: live on & class ≤14 days old ⇒ `wait` (re-polled each pass); otherwise `notfound` ("Retry not-found" clears them).

## Live poll strategy
Periodic work every 3 h (CONNECTED + battery-not-low) while either toggle is on; also "Run now" and a pass right after enabling. Moodle/Panopto lists are cached (TTL) in `MoodleClient`, one list call per course per pass.

## Network
- Pass starts on metered network without approval ⇒ queue a UNMETERED one-time run (auto-resume on Wi‑Fi) and post a notification **Use mobile data / Wait for Wi‑Fi** (≤1 ask / 6 h). "Use mobile data" ⇒ one pass with cellular allowed (audio downloads in that pass also allowed); "Wait" ⇒ Wi‑Fi run stays queued.
- Audio extraction keeps its own Wi‑Fi→cellular pause/ask.
- Sign-in expired (Moodle/Panopto) ⇒ pass stops with "sign-in needed" in Settings (no retries storm).

## Known gaps
- Matching needs the date in the Panopto title. Recordings named only "Lecture 5" are not matched (→ not found). Next: use Panopto session start time (DeliveryInfo / session list API) instead of title.
- No Play Billing yet (beta self-activation only in debug builds).
- No "sign in needed" notification (shown in Settings only); per-class queue view not shown (counts only).
- Server-side queue/fairness across users is the API's existing job queue; the app limits itself to 2 concurrent jobs.
