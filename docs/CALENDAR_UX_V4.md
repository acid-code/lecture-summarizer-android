# Settings auto-sync counter restoration (local v4)

Continues `feature/calendar-course-refresh` after c1aac2d. No push, merge, deployment or API changes.

## Restored appearance

Compared the actual 7dca316 `AutoSyncSettings.kt` and `StatusChip` implementation, rather than approximating the old style. Restored the original four labels and their order: imported, existing, waiting, not found. The chips again use `labelMedium`, the original 16dp info glyph, 10dp horizontal/7dp vertical padding, a 6dp glyph-to-label gap, the softly tinted background and original not-found error colour. No large outlined buttons, extra separators or new decorative category icons. The chips are 30dp high, with 8dp flow gaps. Counter rows explicitly start at the physical left as requested; Hebrew contents retain correct RTL/bidi layout. The queue-state row also uses its original status primitive.

Queued/failed counters remain available in the same small style when nonzero, without two permanent zero-count items enlarging the original idle layout. Counts still derive from actual queue rows; the underlying retry/dismiss callbacks and their errors are unchanged.

## Interactions retained

The existing info glyph opens the compact explanation dialog. The label/count opens the compact records sheet; Show all expands it and Show less collapses it. The glyph and count are independent sibling touch regions with accessibility button semantics, without nesting a new button inside a chip. Native UI tests use actual touch input at their centres to verify that info does not open the records sheet, and that the count opens the correct records. The info/count sheet implementations are unchanged.

## Visual evidence

`/workspace/lecture-summarizer/screens/v4/index.html` contains nine visually reviewed Hebrew/light renders: before c1aac2d, restored section, reconstructed 7dca316 reference, counter crops, active queued/failed states and compact/expanded popups. `comparison.json` records the pixel comparison.

The reference is reconstructed from old source using the unchanged production `StatusChip` and explicit physical-left placement. It is not a capture from the historical APK or a physical phone. The restored original-four counter group is pixel-identical to that reference: 321 x 68 pixels at mdpi, no RGB differences. A native test also compares every ARGB pixel. Frames use the production Settings card and synthetic queue data; action callbacks in screenshot fixtures do not perform sync work.

## Checks

* Debug unit tests: 138 passed, zero skipped.
* Release unit tests: 138 passed, zero skipped.
* Real Biotech and deck-continuation fixtures enabled in both runs.
* Existing v3 native UI regressions: 3 passed.
* New v4 native UI/render tests: 2 passed, including pixel equality, real tap targets and popup expansion.
* `assembleDebug` and `lintDebug`: successful; zero lint errors, 62 warnings, 3 informational findings.
* `git diff --check`: clean.

Not verified on a physical phone: live background-sync outcomes, TalkBack, enlarged fonts and gesture/touch behaviour away from the tested centres. No live account/calendar changes were made.
