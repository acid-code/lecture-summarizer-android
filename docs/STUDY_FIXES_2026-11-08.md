# Study/calendar fixes — 8 November 2026

## Calendar navigation and safety
- Calendar import now has its own non-exported `CalendarImportActivity`, with the same `enableEdgeToEdge()` policy as MainActivity. It no longer runs inside a separate full-screen Compose Dialog inset host.
- Its Scaffold bottom bar consumes the real navigation-bar and IME insets; list content consumes Scaffold padding. AppScaffold explicitly uses safeDrawing. Tutor composer also consumes nav/IME insets. New-entry choices scroll rather than clipping on short screens.
- Audit: onboarding weighted scroll + safe Scaffold padding; record/listen/detail/settings scroll or apply Scaffold padding; source/Moodle lists reserve their footer; share import has no pinned footer; Material dialogs/sheets retain their native inset handling. No extra hard-coded 48dp production spacer was added.
- New screenshot test dispatches **48 physical pixels** through the platform View inset host, with no `footerInsets` override. Old phone typography tests supplied 48dp (126px at 420dpi) directly to the footer, bypassing the Dialog/Activity host problem. Samsung hardware confirmation is still required.

## Calendar drafts (urgent regression)
- Root cause: selecting each series called `CalendarRuleApplier.apply()` for every semester occurrence, creating an empty local DRAFT lecture, including future dates. Search refresh only made the inflated counts more apparent; it does not create rows.
- Selection stores watch rules, selected calendars, and target courses only. Calendar approvals now store watch-only fingerprints, not lecture placeholders. Source automation creates a lecture only after a real recording is matched for import.
- The cleanup requires an old calendar link, an exact selected-calendar event/rule fingerprint, the original title/start/course, a completely default empty DRAFT, and no audio, PDF, transcript/index, game/chat cache or material file. Unknown provenance, user edits, remote ids, jobs, attachments and partial recording files are preserved.
- Cleanup writes recoverable private JSON backups under `migration-backups/calendar-drafts-v1/` before removing **rows only**, never material files. It runs on startup (only with permission and chosen-calendar evidence) and calendar scans. Missing evidence means preserve and retry later, not guess.
- Simulation: 44/33/32/30 → 6/6/6/6. 115 proven empty calendar drafts removed; all 24 real lectures retained. Refresh/indexing afterwards adds zero rows.
- Read-only production check for the reported device: Algorithms 5 (0 structurally empty), Biochemistry 6 (0), Genetics 6 (0), Intro ML 6 (0), Intro Neurobiology 6 (0), Neuro 3 (0), Biotechnology 7 (1). No future-dated rows. The one empty Biotechnology row (`41a70d60…`, “שיעור 21/10”) has no provable calendar provenance and was **not changed**. No server deletes were performed.

## Summary variants and status
- All three densities are Compact / Standard / Detailed. Existing variants switch from on-disk cache; PDFs and first-page renders preload. Taps and preload share one per-file download lock; the latest tap wins. Switching no longer restarts a 12-round availability poll. Legacy unnamed PDFs resolve their density from their source job instead of leaving Standard disabled/unselected.
- Missing variant → confirmation → only that density queued. Old PDFs remain usable. Per-density tokens/progress survive process death; older workers cannot clear newer requests. Completed chips become usable automatically.
- Header, course list and Home all read the same persistent state, reconciled against server density job metadata while visible. Active work wins over Ready/Done; failures show Failed with retry. One compact failure line belongs in the summary card, not repeated in every chip; chips show only an error icon.
- Move to course is available in the lecture overflow and course-list long-press/overflow. Remote lectures update the server relationship first; failures leave the local lecture in place. Local-only lectures move without an API call. Lecture identity is unchanged, so slides, all PDFs, transcript, game/chat and unread state follow it.
- Home displays watched-course count, not semester-instance count. Disabled background checking gets an actionable Turn on line; active checks show the estimated next check.

## Tutor diagnosis
- Broad questions had no domain keywords, while retrieval favored the opening chunks and the classifier conflated missing evidence/uncertainty with off-topic. The client also sent app locale rather than question language, explaining the English refusal for Hebrew text.
- The exact reported lecture exists: 779 ASR segments, repaired transcript and summary, plus two slide-file rows. Thus “no transcript” is not the explanation. Read-only samples concern pKa, amino-acid charge and pH-dependent alpha helices.
- Narrow whole-lecture overview/summary/key-points/explain/quiz patterns are always on-topic; mixed appended unrelated requests still go through the topic guard. Broad retrieval spans the whole transcript. Exact citations and independent grounding verification remain mandatory. Both client and server infer language from the current question.
- The local regression suite uses realistic Hebrew transcript-only material. It does not claim a live Gemini smoke test.

## Reported resummarization (`3ad45896…`)
- Read-only production snapshot: compact job `4819c0f2…` is at PDF, standard `9608c2b8…` at PDF, detailed `9b146ddd…` at Summary. All three have `error=NULL`, not a server Failed state; neither PDF-stage job has a persisted PDF artifact.
- PDF progress says “Building PDF · ASR incomplete — reprocess needed”. Coverage is ~87–88%, with recorded ASR holes. The older detailed artifact also records raw audio deleted.
- Confirmed: the APK's generic local-worker failure was incorrectly presented alongside stale Done, with the same freshness/error message repeated in chips. Fixed status/repetition and resume bookkeeping. The exact underlying PDF-stage stall cannot be proven from these DB rows alone; no invented quota/rendering diagnosis is claimed. No live retry or server mutation was performed by this executor.

## Embedded memory cards
- The existing summary prompt includes 8–16 concise term/definition pairs when supported. Hierarchical/legacy summaries reuse their already-generated glossary definitions; no additional Gemini call.
- Pairs are stored in summary JSON and delivered in a bounded UTF-8-safe `X-Study-Pairs` PDF response header. Android caches them alongside the PDF and feeds the offline game directly; older servers retain glossary fallback.

## Validation
See final execution report for test counts, commits, APK path and screenshots. No Android push, merge, release or API deployment was executed by this local executor.
