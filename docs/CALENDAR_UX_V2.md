# Calendar/course feedback v2 (local only)

Branch starts at continuation `7dca316`. No API changes; no mailbox scopes or writes to source calendars.

## Diagnosis (phone report clarified by owner)

* **#1 confirmed UI bug:** Home derived its onboarding/connect hero solely from `next == null`. A granted permission, synced device account, previously chosen calendars, or successfully imported courses did not affect it. No future matched rule could therefore look exactly like an unconnected account. The old import CTA was also always labelled “connect”, regardless of device connection.
* **#3 event disappearance is NOT confirmed on the phone.** The original course picker *did* persist courses and `settings.calendarIds`. However, it populated neither the separate calendar-rule `sources` nor `rules` store; Home and mapping previews read those stores. That is a confirmed integration gap, not proof Asaf lost a selection. No success/next-step state made the distinction visible.

## Changes

* Connection = READ_CALENDAR plus a selected or available device calendar/account. On-resume refresh; never needs Gmail/OAuth. Home replaces the connect hero with a watched-event/course count, upcoming classes, watch-list link, last background check, and honestly labelled next-check estimate. Background checks off is explicitly stated. Availability alone never selects a calendar or reads its events.
* The actual event picker reads only calendars explicitly chosen, within the displayed term. Search, select all (filtered results if searching), clear **all** including hidden selections, selected count, lazy list. Nothing selected by default.
* Saving events creates/reuses courses, exact-title rules scoped to those calendars, and deduplicated local event placeholders with stable import links. Repeat imports are idempotent, schedule changes update unedited placeholders, and recordings/edited lectures are not overwritten. Enables the rules feature but **not** automatic approvals, Panopto downloading or background checking. Copy explains recurring-title scope before saving. Other manually authored rules survive; choosing a new calendar set revokes future reads of deselected calendars without deleting saved lectures.
* Watch list contains matching term events, not just unapproved events; upcoming first. Calendar-rule previews retain their separate pending-approval meaning. Both pickers now have bulk/search controls. Existing legacy imports did not retain event identities; do not invent them. Use “Manage calendar events” to choose watched events once.
* Course sources: readable calendar/Moodle/Panopto status cards; focused bottom sheets with full-width fields; configuration is not mislabelled as authentication. Cancel discards edits. Mapping preview now prefers stable course ID, with legacy name fallback.
* Local search indexing becomes Refresh: running progress, visible success/partial-failure state, snackbar result, persisted last **successful** refresh time. Failures do not replace that timestamp or erase old text. Local downloaded content only.
* Course last-update is a separate persistent content-write timestamp (not lecture date, not a claim that remote Moodle was checked). Old installs show “not updated yet” until a real update. Muted line on tile, header and mapping screen.
* Sync count/info chips open actual status-backed lists with course/date/title/status and guarded retry/dismiss actions. Processing imports are **queued**, ready summaries **done**, failed work separate. Legacy event keys yield course/date even without cached metadata; future passes cache titles. Counts and lists share the same rows. Waiting explanation reflects real planner: new classes collected 10 days back; checks ~3 h first 2 days then ~daily, existing watches up to 21 days (not falsely “10 days maximum”). Android power/network scheduling can delay checks.

## Verification boundaries

Tests cover connection policy, persisted import reopen, exact/calendar scoping, repeat/dedup/reschedule, selection helpers and actual Compose bulk interactions, info-chip actions, and queue category/action guards. Native Robolectric screenshots use production UI components and synthetic data, not Asaf's calendar and not an emulator/device end-to-end.

Not verified on a physical phone: actual university CalendarProvider data/permission lifecycle, Android background scheduling, Moodle/Panopto authentication, real local PDF reindex failures, and server recovery after a failed lecture. No production data changed.

## Final local run

* `testDebugUnitTest`: **135 tests**, zero failures/errors/skips.
* `testReleaseUnitTest`: **135 tests**, zero failures/errors/skips.
* Both real slide fixture sets enabled (`REAL_CONTINUATION_FIXTURES`, `REAL_BIOTECH_FIXTURES`); no live lectures changed.
* `assembleDebug` and `lintDebug` passed; lint has zero errors (existing/newly orphaned resource warnings remain).
* Separate native Compose run: **3 tests passed** (bulk select/clear, info-chip callback, twelve renders).
* Twelve Hebrew/light screenshots visually reviewed; `/workspace/lecture-summarizer/screens/v2/index.html` is the local gallery. Edit forms are production components rendered with demo data, not a captured signed-in device session.
* All translatable Hebrew keys have English counterparts.
