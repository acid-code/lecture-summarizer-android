# Local checkpoint and remaining UX queue

Branch: `feature/email-pull-biotech` (legacy name; implementation is calendar-only). Base: `37ce35d`. No push, merge, release upload or deployment is authorized for this batch.

## Delivered in this checkpoint

- Calendar/account-scoped rule preview, approval, guarded local import/update/undo, optional background scans, per-course source configuration, matching/preview and migrated existing defaults.
- Review P1: local per-level material freshness and transcript-only rebuilds retaining PDFs; truthful constrained slide-search states and suggestions-only policy; Hebrew/English main-journey localization, semantic density labels and bidi helpers; non-actionable status chips and semantic colors; release has no developer menu and ignores old unlock preference; summary/listen before collapsed materials; factual empty states; current-term calendar picker/progress/result preview.
- Old Gmail/email rules prototype removed. No mailbox read or calendar write capability added.

## Not delivered / next clean batch

The later `drafts/ux-flows/PLAN.md` was read and `mockups/overview.png` reviewed, but its new redesign is **not implemented** at this checkpoint:

1. Unified +lecture chooser with no pre-choice draft; PDF hero and always-visible level toggle; top-bar share/overflow delete/course long-press actions.
2. New processing step tracker, automatic upload after recorder stop; Rubik, 28/20/15/12 typography, rounded-20 bordered shadowless cards, full light/dark visual pass.
3. Home/Courses/Search bottom navigation, cross-lecture search, missed-lecture Panopto catch-up, rebuild “what changed” notes and semester setup sheet.
4. Rendered Hebrew/English light/dark screenshots. No emulator/screenshot renderer was configured; mockups are not passed off as app screenshots.
5. Complete conversion of the remaining legacy calendar-parser conventions into generated editable rule rows; currently explicit configuration is generic, while the prior route is preserved until opt-in.
6. Matcher accuracy repair and real fixture assertions before automatic slide matching can be reconsidered. Full on-device/provider E2E, plural-form polish and accessibility/bidi visual QA.

UX decisions needing product review: approximate term dates and 1,000-instance limit; title-glob versus keyword semantics; explicit rule ordering; local empty-lecture approval/undo and advanced auto-approval; feature flag also controlling per-course source mapping; full selected-level rebuild on the first versioned legacy refresh; old summaries treated conservatively rather than inventing historical provenance; automatic slide suggestions instead of attachments.
