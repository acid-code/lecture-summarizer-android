> Superseded locally: Asaf clarified this is calendar-account event import, not email reading. See [CALENDAR_SOURCE_AUTOMATION.md](CALENDAR_SOURCE_AUTOMATION.md). The email-rule prototype described below has been removed; this document records the previous published batch only.

# Polish / email rules / slides batch

Based on `9241771`, with WIP `034dce8` cherry-picked and conflicts resolved preserving unread badges, not-found dismiss/restore and queue ticks.

## Implemented
- Three-screen optional onboarding, default standard+detailed summaries, connection states; settings-only technical controls (7-tap unlock; always visible in debug). Release mock is enforced false at the settings boundary even for an old true preference.
- Hebrew base resources with English equivalents for onboarding, settings, core lecture actions and new components; AppCompat locale switching handles locale/layoutDirection without restarting the activity. Some legacy import/listen/recorder messages still need a separate localization pass.
- Shared semantic StatusChip, separate transcription/summary labels, upload progress, inline retry, orthogonal cloud-off state; navy/gold theme (gold backgrounds only), actionable empty states.
- Slides: unique explicit lecture-number early attach (date-only titles never qualify), then a Wi-Fi WorkManager pass after the first transcript-backed summary completes. Extracts PPTX/PDF text locally, ranks overlap with course decks, auto-attaches only a clear winner, uploads and requests summary/PDF regeneration without ASR. A near tie is only suggested. Unknown/unreadable candidates prevent declaring a winner. No OCR, embedding model, or cross-language transliteration in v1.
- Per-lecture manual overrides/rejections and provenance, top-five candidates, two/three plain-language reasons, replace/upload, remove with ~8-second undo and a one-time no-ASR update offer. Manual picks cancel/veto automatic matching.
- Experimental email rules are OFF by default (developer flag). Saved rule cards/editor, on/off, sender/keyword/course constraints, exclude precedence, first enabled matching rule, candidate dedupe and pure preview engine.

## Deliberately not claimed complete
- No existing email-pulling code was found in either repo or the supplied design directory. There is NO connected mailbox transport/OAuth or calendar-write flow here. The flagged screen says this explicitly. It does not read email or create calendar events. “Connect email” currently explains the missing secure setup, not a fake sign-in.
- The requested 30-day email scan, checkable/editable preview, event/all-day/reminder actions, pending approvals, automatic adds, real email links, reschedule+undo and provider-side prefiltering still need implementation. Do not describe the rules prototype as working email automation.
- Biotech 28/10→PPT03 and 02/11→PPT04 tests use **synthetic content fixtures**, not downloaded owner transcripts/decks. Real mappings and the wrong existing deck swap remain unverified; no live user lecture was changed.
- Post-ASR matching currently waits for the first job's completion rather than interrupting an active summary. It regenerates the current job's summary level; existing alternative-density PDFs are not all automatically invalidated/rebuilt.
- The new API DELETE /v1/lectures/:id/slides is needed only when rebuilding after removing the last deck; older deployments fail visibly rather than pretending the old server slides were removed. The companion API change must be deployed before that operation is released.
- No physical phone/emulator verification of RTL layout, sign-in, disconnect, language switch, WorkManager retries or slide UI. Existing installs keep notebooks and skip onboarding.

## Limits and review decisions
- Matching is Wi-Fi-only, battery-not-low, ≤60 resources, ≤40 MiB per downloaded file, 120 MiB per pass; incomplete candidate pools never auto-attach. PPTX expanded XML and PDF page extraction are bounded. Unreadable/scanned PDFs and old `.ppt` files do not trigger guessing.
- Confidence is a minimum of four heard terms plus a 1.6x/2-point winner margin; display high/possible labels, not percentages. Review on real Hebrew/English lectures before loosening it.
- Auto-match is conservative and never replaces an existing/manual attachment. Existing wrong auto-attachments without provenance must be corrected explicitly through Replace.
- AppCompat handles live language changes; numbers in new count/status resources use LTR isolates. Legacy dynamic strings remain a localization debt.
- Calendar disconnect opens Android app permissions; Moodle/Panopto disconnect expires that host's cookies. Real IdP/domain-cookie sign-out needs device testing.
- A detached remote deck's blob is retained rather than eagerly deleted while an existing job could read it; orphan retention cleanup is a separate follow-up.

## Validation for this batch
- `./gradlew testDebugUnitTest testReleaseUnitTest assembleDebug lintDebug --console=plain`: success; 78 tests in each variant, zero failures/errors/skips; lint zero errors (31 warnings, 4 informational findings).
- Companion API: `npm test` (63 passed) and `npx tsc --noEmit` succeeded, including a new ownership/idempotency/transcript-retention test for slide detachment.
- All translatable base string keys have English equivalents. This is resource parity, not a claim that every legacy hard-coded message has been migrated.
- No APK install, live Moodle account action, calendar write, mailbox read, Git push or production deployment was performed in this local-only batch.
