# Local checkpoint and remaining UX queue

Branch: `feature/email-pull-biotech` (legacy name; calendar-only, no mail access), based on `37ce35d`. Stay local: no push, merge, release upload or deployment in this batch.

## Delivered

- `4e75224`: calendar/account-scoped rules, preview/approval/local import-update/undo, optional background scans; generic per-course Moodle/Panopto source config/match preview; review P1 material-version freshness/rebuild, truthful slide states, localization/bidi/status chips, release no developer menu, summary before collapsed sources, current-term calendar picker/progress/results.
- `aa592cb`: course-vocabulary/script-balanced slide evidence; real Biotech top suggestions corrected and regression-asserted. All matches remain suggestions-only; no live changes.
- UX pass: unified +lecture chooser (record/Panopto/files; no draft before choosing); summary hero/always-visible standard+detailed options/single Open summary; top-bar share and overflow deletion/repair; long-press course actions; honest processing tracker and automatic upload on recording stop; Rubik and 28/20/15/12 type scale; rounded-20 bordered shadowless primary surfaces; navy/gold-new and light/dark roles.
- Home/Courses/Search bottom navigation; selected-calendar now/next, processing and unread-first summaries; missed-class Panopto picker; on-device cached transcript/PDF search; factual rebuild note; semester import/rules/course-selection/sequential mapping sheet; recording timestamp bookmarks.
- 20 Hebrew/English light/dark component screenshots and successful debug/release test, APK and lint checks. See `LOCAL_VALIDATION.md` and `UX_IMPLEMENTATION_AND_SCREENS.md`.

## Still not complete / next clean batch

- Legacy calendar-parser compatibility route still exists; explicit rules are generic but remaining conventions are not all converted into generated editable rule rows.
- Full PLAN extras are not implied: course segmented materials/exam views, exam-week/cross-lecture PDFs, bulk source-mapping setup in one transaction, editable term boundaries, full combined correction sheet, rounded-symbol icon migration/shared-element motion, auto-import provenance ribbon, recording bookmarks highlighted in generated PDFs and content-level rebuild diff remain later work.
- Home catch-up is rule-calendar-derived and opens a picker, not unattended Panopto import. Search includes indexed/downloaded content only, not a remote historical corpus. Wider matcher calibration beyond two real transcripts/four decks is still needed.
- Device/authenticated provider/background lifecycle/full PDF rebuild verification, PDF thumbnail completion, plural-form polish, font scaling/accessibility, large-corpus performance and complete locale/bidi visual QA remain.

## UX decisions needing review

- Keep suggestions-only even when ranked first; retain the 1.6× abstention margin.
- Slide search is optional **after** summary completion in the actual pipeline; tracker does not falsely mark slides complete or mandatory.
- Home reads only explicitly selected calendars/enabled rules, uses a 7-day missed-class window and a one-hour local-recording correlation tolerance; no inferred-account scan.
- Search stays local and explicit about uncached history. Standard/detailed level chips remain visible but unavailable levels are disabled.
- Semester setup applies mappings per course sequentially rather than inventing bulk configuration.
- Rebuild note reports selected levels/material count and transcript reuse, not an invented semantic diff.
- Approximate term dates/1,000-instance guard, title glob vs keyword semantics, first matching rule, advanced auto-approval and source-mapping feature flag are inherited checkpoint decisions.
