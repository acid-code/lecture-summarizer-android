# Search refresh, compact disclosures, and preserved Home (local v3)

Continues `feature/calendar-course-refresh`. No API changes, push, deployment or merge.

## Search/index refresh

The runtime v2 refresh was already called only from `SearchContent` on the Search tab, wired to `StudyIndex.reindexLocal`. Its isolated fixture was misleadingly rendered under a generic Settings header. There was no course/source refresh to move. The production component is now explicitly named `SearchIndexRefreshCard`, labelled “עדכון החיפוש”, and v3 screenshots show the **complete Search tab**, including its selected bottom-navigation item, query, progress/result, successful-update time and search results. It updates downloaded local search content, not remote sources. Course content timestamps remain unchanged, small and muted.

## Compact information and counts

* Info is an independent button with its own touch target; it opens a width-capped (340 dp), wrap-content explanation dialog, not a records screen.
* Count opens a wrap-content sheet with its explanation, one initial record and retry/dismiss actions. “Show all N classes” expands into a bounded scrollable list; “Show less” collapses it.
* No full-page navigation for these actions. Counts still derive from the actual queue rows.
* Native interaction tests verify info does not open a list, and open/expand the actual production count sheet through its buttons, checking its initial/expanded rows and measured bounds. Row assertions use the same bidi-isolated text as production. These native Robolectric tests are not presented as physical-phone/window-accessibility testing.

## Home comparison — what was and was not preserved

Compared `/workspace/lecture-summarizer/screens/home-he-light.png` with v2 `home-connected-he-light.png`. The latter was only a standalone status-card fixture (no full Home header/nav/cards), so it was not a like-for-like full-screen comparison. Nevertheless, v2 code did prepend a large status card above the next-class hero: the original hierarchy was **not fully preserved**.

V3 removes that prepend. The greeting, original tinted next-class hero and recording action come first, then processing, then new summaries; typography, colours, logo, settings action and bottom navigation remain unchanged. Connection feedback fits as a small footer **inside the existing hero**, with counts, watch-list action, an honest background-check state/estimate and inline errors. No upcoming class now says that, not “connect again”. The added footer increases hero height, so subsequent cards move down; this is not a pixel-identical connected state.

Proof with the exact same synthetic fixture as the original Home: the v3 **no-status baseline is pixel-for-pixel identical** to the original PNG (393×852, no difference bounding box). The connected variant retains the same hero content and navigation, with the new footer. V3 renders full Home and Search frames, not isolated cards pretending to be those screens.

## Verification

Native Hebrew/light renders use production Compose components and synthetic data. Screenshots and comparison metadata are under `/workspace/lecture-summarizer/screens/v3/`. No private calendar, mailbox, real account or production API was accessed by this pass. Real phone/window accessibility, account state, background work and model/API recovery remain outside this local visual verification.


### Completed checks

* `testDebugUnitTest`: 135 passed, zero skipped (real Biotech + continuation fixtures enabled).
* `testReleaseUnitTest`: 135 passed, zero skipped (same real fixtures).
* Native `CalendarV3ScreenshotsTest`: 3 passed, zero skipped; actual info/count interactions, compact-to-expanded rows/bounds and full-frame rendering.
* `assembleDebug` and `lintDebug`: successful. Lint: zero errors, 66 warnings, 3 informational findings (including the now-unused legacy `queue_info` resource).
* All 14 gallery screens visually reviewed in Hebrew/light. `screens/v3/index.html` is the gallery; `comparison.json` records pixel-identical baseline proof; `native-interaction-results.xml` retains the dedicated UI test result before normal Gradle tests replace their reports.
* `git diff --check`: clean. All newly added translatable keys have Hebrew and English entries (`app_name` remains intentionally untranslatable).

Remaining device checks: actual Search tab indexing a phone's downloaded transcripts/PDFs, slow/error recovery, background scheduling, popup accessibility/large font behaviour and live calendar/account state. The screenshot fixtures do not establish these end-to-end properties.
