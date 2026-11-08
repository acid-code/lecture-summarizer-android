# UX plan implementation and rendered evidence

Read brief: `drafts/review-polish-37ce35d.md`, `drafts/ux-flows/PLAN.md`; reviewed `mockups/overview.png`. Mockup names/data are not production constants.

## Implemented journeys

1. Course +lecture sheet: record now / choose Panopto / select files. Panopto creates/reuses a local lecture only once a recording is chosen; file selection creates only after a nonempty result. Dismiss/back creates nothing. Audio/material imports use existing attach/upload validation.
2. Recorder: stop finalizes segments and enqueues the existing network-constrained UploadWorker; UI goes to the job, not an intermediate Attach screen. Pause remains. Timestamp bookmarks use the existing recording markers. Server/PDF bookmark highlighting is not implemented.
3. Ready lecture: PDF cover renderer with fallback, always-visible standard+detailed options (unavailable disabled), one Open summary action. Listen/transcript follows; slides/materials are collapsed. Share is top-bar; delete/save/repair in overflow. Previous PDFs remain available during transcript-only selected-level rebuilds.
4. Visual tokens: bundled Rubik variable font with explicit weight axes and OFL; navy selected/action roles, gold New marker, course tints restricted to notebook bands/dots, dark navy surfaces; 20dp bordered shadowless main cards. Status chips use icons+words with separate offline indication.
5. Home: next/current selected-rule class, processing (including queued recorded audio), unread-first ready summaries, 7-day calendar catch-up and setup. Only opted-in selected-calendar rules are queried; errors get retry instead of silently pretending there is no class. Catch-up carries the source occurrence time to the picker and can reuse its approved empty draft.
6. Local search: title/course plus persisted downloaded transcript/summary text. Normalized case/diacritic-insensitive all-word matching, debounced query, first 100 recent results. Existing cached PDFs/SRT can be indexed explicitly; no remote corpus scan or query transmission. Index removed on lecture/course deletion.
7. Rebuild note: actual selected levels, material count and transcript reuse after successful refresh; not a fabricated content diff.
8. Semester sheet: calendar selection/import, rule flag/settings, chosen courses and sequential per-course source configuration. This is not yet bulk mapping in one operation.

## Rendering

20 PNGs at `/workspace/lecture-summarizer/screens/{home,courses,summary,processing,search}-{he,en}-{light,dark}.png`, 393×852. Screens use production components (`HomeContent`, `MainTabs`, `NotebookCover`, `SummaryHero`, `LectureStatusChip`, `ProcessingSteps`, `SearchContent`) with deterministic **DEMO** test data and a synthetic PDF fixture. They do not impersonate a signed-in whole-app/device run. Surrounding test scaffolding is representative, not an exact full lecture screen reproduction.

Robolectric 4.14.1 / Roborazzi 1.39.0 Native Graphics, API 34, empty Application (no app tokens/accounts/network). Locale qualifiers and explicit RTL/light-dark theme. Opt-in `-Pscreenshots=true`; `LECTURE_SCREEN_DIR` controls output. See validation command in `LOCAL_VALIDATION.md`.

Emulator installation succeeded, but the box user lacks KVM access; no machine privileges or device permissions were changed. The supported native-graphics JVM path rendered components instead. Summary snapshots show the **PDF icon fallback**, not a faked cover image. Device PdfRenderer completion/external PDF viewer remain unverified. Visual samples reviewed across both locales/modes: layout, readable contrast, navy selected states, no gold body text and no obvious clipped primary actions at this test size. Accessibility/font scaling and lower-scroll content are not fully covered.

## Deliberate scope limits

No fabricated ETAs, calibrated percentage confidence, fake completed slide step, semantic rebuild diff, account names, calendar events or source recordings. No API change/deployment. No mail access. No live attachments updated. See follow-up document for PLAN features not yet implemented and UX decisions needing product review.
