# Calendar → lecture → source automation

This supersedes the historical email-rules prototype. “Email-account calendar” means calendars synced to Android, **not messages**. There is no Gmail client, Gmail/OAuth scope, mailbox permission, Google Cloud client ID, or CalendarContract write. The only calendar permission is READ_CALENDAR. Source events are never edited.

## Enable and configure

1. Sync the desired Google/university account calendar to the phone in Android settings. Grant calendar read permission when requested.
2. Settings → calendar-import rules: enable the feature flag (off by default, also available in release). Open calendar rules and choose accounts and individual calendars. Calendar metadata is listed for the chooser; event bodies are queried only for explicitly selected calendar IDs whose account identity still matches.
3. Add a named rule, a destination course, and at least one positive condition: title glob, include keyword, course keyword, or recurring classes only. `*` and `?` are supported for titles; this is not unrestricted regex. Excludes win. Rules are evaluated in displayed order, first match wins. New rules start disabled. An empty/broad accidental rule is rejected; an intentional whole-calendar title glob `*` is explicit.
4. Preview the real selected calendar instances; check the desired matches and import. This creates **local empty lectures**, not calendar events and not recordings. Repeated imports dedupe by stable instance or normalized title/time. A moved instance updates its original empty local lecture; recordings, processing work and later user edits are never silently overwritten. Import/delete/dismiss UI offers undo; a guarded undo refuses to overwrite subsequent work.
5. Automatic scans are optional (30-minute WorkManager scheduling, not an exact alarm). New matches go to approval by default. Advanced automatic approval is separate and off by default. Undo suppresses unattended reimport of the same event fingerprint.
6. Open a course’s source mapping (under the same feature flag). Choose a Moodle course ID or course-page URL/site and/or a Panopto folder/session. Confirm suggested Moodle course matches, then preview events → source items and their reasons. Configure date, start-time, title tokens, lecture-number extraction and tolerance. An event-specific recording pin is tied to its event time so rescheduling does not silently keep an obsolete pin.

## Scope and migration

- The preview shows an explicitly labeled approximate current-term window in Asia/Jerusalem: October 1–March 1, March 1–August 1, or August 1–October 1. These are product defaults, **not an official university semester feed**. At most 1,000 selected instances are read; partial results are labeled, and a truncated scan is not automatically approved.
- Explicit new configuration never falls back to hardcoded university/course guesses. Clearing configured rules does not reactivate legacy scanning. Destination course IDs survive course renames; deleted destinations disable the rule.
- Existing selected Moodle IDs and legacy Panopto behavior are lazily migrated/preserved as editable per-course defaults. This is a compatibility route, not a complete conversion of all historical calendar-parser heuristics into explicit rule rows. Until the user opts into explicit rules, the previous account/calendar setup remains available to avoid disrupting an existing installation.
- Source registry: `DataSource.providerId`, replaceable `SourceItemMatcher`, serializable non-secret `SourceConfig`, `SourceItem` and `SourceListing`. Unknown providers fail explicitly. New recording providers still need an ingestion adapter: the current recording transport is Panopto. Store credentials only in the existing authenticated WebView session, never in mapping options.
- Moodle filenames are filtered to PDF/PPT/PPTX; non-slide files do not become slide suggestions. File cache keys include the Moodle site and lecture.
- Panopto explicit folders use the documented public folder-session REST endpoint. Some tenants require OAuth/admin API grants and may not accept an existing web session. This path has **not been tested against a signed-in tenant**; no new OAuth flow is implemented. Course-embedded Panopto discovery retains the existing session path. Per-session metadata can require up to 100 requests and needs device performance testing.

## Material freshness and slide search

Manual material changes bump a per-lecture revision. Explicit rebuilds synchronize the complete selected deck set (including removals), reuse the transcript, retain readable old PDFs, and persist/resume each selected summary level. Changed or unrebuilt levels are labeled outdated. A new requested level uses the same worker; it does not bypass deck synchronization. A rebuild waits for the original job to become terminal. Undo of an otherwise untouched removal restores its freshness checkpoint.

Newly finalized normal uploads record their uploaded material revision and the completed job’s explicit density; the poller downloads that density rather than an unrelated latest summary. Attaching initial draft materials does not falsely label a yet-unbuilt summary as outdated. Historical PDFs and unobserved sibling jobs have no recoverable server-side material-version provenance; this implementation tracks revisions locally going forward, and treats unknown versions conservatively after a change. A normal legacy ASR/reprocess path may leave a conservative outdated label until an explicit versioned rebuild. This is not a server-wide artifact provenance migration.

Search distinguishes queued, unmetered-network wait, battery pause, checking, partial, sign-in required, no course/no match, error and disabled states. Automatic slide search is separately switchable in settings. “Check again” requires it to be enabled. Searches remain unmetered-only. Matches are **suggestions only**, not automatic attachments, pending broader matcher validation (see BIOTECH_MATCHER_VERIFICATION.md). Manual choices/rejections remain authoritative.

## Verification boundary

JVM debug/release tests and Android debug build/lint are automated. CalendarContract permission/provider behavior, account picker, real background scheduling/undo races, authenticated Moodle/Panopto discovery/download, PDF rebuild completion, and locale switching/layout need phone/emulator integration QA. No Gmail or Google Cloud setup is needed. There was no live calendar write, live lecture change, push or deployment in this batch.
