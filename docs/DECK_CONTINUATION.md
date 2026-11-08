# Deck continuation — local implementation

Android base: `c3a8282`, branch `feature/deck-continuation`. API companion is LOCAL commit `2331196` on a branch of the same name based on `be7a4a8`. No push/deploy/live data mutation.

## What was already supported (code audit before changes)

- **Same deck in multiple lectures: yes.** Android stores slide URIs on each Lecture, with no global consumed/assigned-deck exclusion (`data/Models.kt`, `slidesUri`, `extraSlides`, `allSlides`). A Moodle cached file or persisted file URI may be reused. Each lecture uploads independently. API `lecture_files` rows belong to a lecture, with no global deck uniqueness. This is logical reuse, not deduplicated shared-blob lifecycle guarantees.
- **Several decks in one lecture: yes.** `UploadWorker` and `SummaryRefreshWorker` upload all `allSlides`, clearing on the first and appending the rest. API `uploadSlides` already stored multiple rows and concatenated named extracted text into `lectures.slides_text` (`handlers.ts:167–197` at `be7a4a8`). Pipeline context uses that text, and the PDF figures path loops every attached deck (`pipeline/index.ts`, `slideDecks`; `figures/index.ts`, `pickFigures`). Thus both are inputs to summaries/PDFs, subject to prompt/figure budgets and model relevance, not a guarantee every slide appears.
- Gaps: the main SlidesCard replaced/removed all decks as a single slot; matcher ranked one deck without continuation; no page-range metadata. Global prompt head/tail truncation could drop a middle deck entirely. API same-name single-deck retries could create a legacy duplicate, and replacing a deck changed source order.

## Added

- Suggestions-only course sequence matching. Recognized previous lecture deck + explicitly numbered next deck form the candidate fence. Prior attachments are not consumed or excluded. Same-course previous lecture uses the same lecture/exercise kind. Stable Moodle cache identity or a unique matching filename can identify the previous deck. Unnumbered decks are not assigned a fictional next sequence. A per-course “Deck order” editor on attached rows lets the user provide the sequence explicitly for filenames such as Amino acids / Proteins; this is suggestion metadata, not a material change or a Biochemistry hardcoded mapping.
- Up to two suggestions only with independent deck-specific terms and transcript-window evidence in the right temporal order. Shared terms or a reverse transition do not qualify. Monotone page alignment over ordered transcript windows gives optional **approximate** original slide ranges; flattened/no-boundary text never produces an invented range.
- Ordered PDF pages / presentation-order PPTX slides and linked notes, including numeric fallback and bounded extraction. Search can suggest a second deck even when a manual deck is already present; it never changes attachments or starts a rebuild.
- Multiple rows in SlidesCard; append another deck, remove individual deck with guarded 8-second undo, reuse the previous lecture deck, edit/clear each range, or approve a suggested bundle. User taps append, never silently replace another manual file. All copy HE/EN. Range changes invalidate selected summary levels; old PDFs remain readable.
- URI-keyed range persistence, per-lecture freshness and complete upload/rebuild snapshots. Same-friendly-name local files get stable distinct upload names to avoid API filename-idempotency collisions.
- API: additive nullable `lecture_files.slide_range`, validated optional `slide_start`, `slide_end`, `approximate` query fields. Full source text remains stored; approved ranges focus summary text and figure selection, preserving original page numbers. Each named deck gets prompt budget in ordinary, repair and hierarchical paths. Replacing a deck preserves order and avoids the legacy duplicate.
- Owner-scoped GET slides capability preflight. Range snapshots refuse an old server **before** replacing any deck; old PDFs stay intact. Whole-deck multi-attachment works with the existing server. Focused ranges require the companion API commit to be deployed later; nothing is deployed here.

## Real read-only results

Read stored transcript segments/deck text via Turso read-only connector only. Raw fixtures remain OUTSIDE Git at `/workspace/deck-continuation-real/`; no job polling, reprocessing, attachment or DB writes. `results.json` is the detailed local diagnostic.

### Genetics

Only canonical lecture decks 2 (Families, 55 pages) and 3 (Mapping, 58 pages) were available for this analysis; exclude exercise files and duplicate formats.

- 25 October: deck 2, approximate original slides **11–27**.
- 1 November, entry titled “lecture 4”: deck 2 again, **44–54**, correctly flagged as continuation from the actual prior attachment.
- Separate 1 November entry titled “Lecture - 1.11.2026”: deck 3, **8–28**.
- These are two separate stored recordings at **08:16 and 09:06 Asia/Jerusalem**, not one transcript already containing both decks. An explicitly read-only combined-session preview, keeping their chronological offsets, suggests **deck 2 (35–53) + deck 3 (8–28)**. No records were merged. This supports a class-session transition, not proof that a single existing recording spans both.
- The early 18 October recording weakly suggests deck 3 from this incomplete two-deck pool; that is **not validated accuracy**, and needs the missing early decks/human review. Do not enable unattended attachment.

### Biochemistry

Only the 114-page `amino_acids2Proteins _26new.pdf` lecture deck is stored (plus exercise files). Its filename has no explicit deck sequence; do not infer “2” from the word `acids2Proteins`.

- 20 October → this deck, **7–30**.
- 25 October → the same deck, **30–32**.
- 1 November → the same deck, **72–95**.
- 18 October / 3 November → no substantive suggestion with the available candidate pool.
- This is evidence of reuse/progression within the available file, **not verification of the proposed deck-2/deck-3 transition**. The next Biochemistry lecture deck is absent from stored data; need that Moodle file before validating a two-deck boundary. No ranges above are human-verified ground truth.

Moodle access research: no installed/available Moodle connector or browser-dispatch tool in this executor. Existing memory described a previously signed-in Moodle browser, but no cookies/tokens/app credentials were read. Official supported acquisition is a signed-in file download or course ZIP when enabled: https://docs.moodle.org/500/en/Download_course_content . Parent can obtain the missing deck through an authorized Moodle browser session or user-provided file, not by extracting app credentials.

## Validation / limitations

`REAL_CONTINUATION_FIXTURES=/workspace/deck-continuation-real REAL_BIOTECH_FIXTURES=/workspace/biotech-real-verification ./gradlew testDebugUnitTest testReleaseUnitTest assembleDebug lintDebug --console=plain` with JDK 17. Final result: **124 debug + 124 release tests passed, zero failures/errors/skips; debug APK assembled; lint zero errors, 58 warnings and 4 informational findings; no missing English string keys.** No new component render tests were requested/run in this batch.

Both real diagnostic environments are required to avoid intentional skips. Existing Biotech PPT03/PPT04 assertions remain unchanged.

Tests cover explicit per-course order for unnumbered decks, reuse, ordered two-deck evidence, shared-term/reverse-order abstention, unknown pages/sequence, JSON ranges, filename collisions, PPTX presentation/notes ordering and the actual Genetics reuse/combined-session result. The combined-session test is explicitly NOT a merger feature.

No phone UI/Calendar/Moodle E2E or actual AI/PDF rebuild was run. No new screenshots for this batch. Approximate range scoring is heuristic, not calibrated or manually verified; scanned decks/no OCR, missing source decks and unrecognized source identity can abstain. Manual whole-deck selection remains available. API tests use isolated local SQLite and synthetic OOXML, with no production env/database connection.
