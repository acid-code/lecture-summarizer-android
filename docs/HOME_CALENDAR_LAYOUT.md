# Home and calendar import layout follow-up

Local follow-up on `feature/study-tools`, based on `4ed1456`. No API changes, deployment, push or merge are part of this follow-up.

## Home

The previous pale-navy rounded next-class card, greeting and section order are retained. Upcoming class rows use smaller existing 15/12 typography, a date/time line and an icon, not repeated large headings. Initially show one class; Show more reveals at most three, Show less collapses, and the full watch list holds the rest. Expanded rows show event titles. Counts and the “Which events are lectures?” action each get their own full-width layout, with unconstrained line wrapping and logical RTL alignment. Font size is never reduced to work around large-font settings.

The root cause of the missing/truncated-looking count/question was competing horizontal space: an unconstrained TextButton was measured beside a weighted count column. On narrow/200% layouts, that column could collapse toward zero width and grow into an unreadable tall block. The action now has its own row; the count fills its allocated width. Native text-layout assertions check no visual overflow or ellipsized lines for both.

## Inset-safe calendar actions

Calendar import uses a sticky primary action in the scaffold bottom bar. Its safe padding is the union of real navigation-bar and IME insets (not the sum). Body content consumes the scaffold padding and scrolls in a LazyColumn, including help text and search/selection controls. The full-width action has no fixed maximum height and its label wraps. The full-screen import dialog explicitly uses edge-to-edge inset handling. This avoids the old content-column/footer arrangement depending on the dialog's usable screen height and leaving the action under navigation/keyboard space.

## Recurring series only in the importer

- Read master RRULE/RDATE, DTSTART/time zone and title from CalendarContract.Events **only in the chosen calendars**; Instances supplies occurrence times plus ORIGINAL_ID/ORIGINAL_INSTANCE_TIME.
- Display one generic row per calendar/master ID: title, recurrence day(s), time and a conservative frequency label. Blank recurrence columns are not treated as repeating. RDATE/unsupported rules say “Recurring series”, never falsely “Weekly”.
- Hide one-off instances. Renamed/moved exceptions belong to their original series and use the master's generic title/schedule. Canceled instances remain excluded by the existing provider filter.
- Selecting a series includes its instances in the existing current-term window; future scans match the stable series key, not only the title. Another series with the same title is not silently selected. The existing provider limit of 1000 instances and partial-result warning remain.
- Select all selects the visible series without losing hidden selections; Clear all clears all selections. Reopening restores enabled saved picker selections.
- Legacy picker-generated exact-title rules migrate to available series, preserving course mappings and enabled/disabled state. Old individual instance selection keys migrate to their series. Migration is idempotent and the first preview uses the migrated scope. Unmappable legacy rules and explicitly authored custom rules remain untouched; historical lectures are never deleted.
- Saving a new picker selection disables deselected picker-owned rules in those calendars without disabling custom rules or turning on automatic downloads.

## Screenshot blocking

No explicit `FLAG_SECURE`, `SecureOn`, screenshot-disabling call or secure-window setting exists in the app's own main source. MainActivity, manifest/theme and Compose dialogs do not deliberately enable screenshot blocking (dialogs inherit their parent policy). No screenshot/security policy was changed. The reason for the phone's refusal remains unverified; device/work-profile policies or external viewers cannot be diagnosed from this source audit alone.

## Verification and artifacts

Before captures use the original 4ed1456 UI; the baseline harness is archived at `/workspace/lecture-summarizer/drafts/home-layout/before-4ed1456-test.kt`. Calendar source-choice baseline uses the exact pre-change content-column layout with synthetic state rather than a live device calendar.

After captures render production components, synthetic calendars/classes only, no network or personal calendar data. Matrix: HE RTL / EN LTR; 393×852 at 100%; 320×640 at 100%/200%; simulated 48dp three-button navigation bottom inset; and 240dp keyboard inset at 200%. These are native Robolectric/Compose renders, not physical-device screenshots. Screenshots and gallery: `/workspace/lecture-summarizer/screens/home-compact/`.

Tests cover full-width question/count text layout, primary-action bounds above the reserved navigation/keyboard area, recurring-series bulk/search selection, bounded expansion, actual CalendarContract projection/account scoping via a fake provider, exceptions, empty RRULEs, persistence, migration, reschedule identity and historical retention. Real phone/navigation mode, TalkBack and a live university calendar provider still need device verification.

Final local run: **174 debug + 174 release unit tests**, zero failures/errors/skips, including the real Biotech/continuation fixtures. **8 new native layout/interaction tests + the existing bulk-selection UI regression** pass. `assembleDebug` and `lintDebug` pass: zero lint errors, 65 warnings and 3 informational findings. All 54 renders were visually reviewed; contact sheets and an HTML gallery sit beside the images. The screenshot tests remain excluded from ordinary/release unit runs, as with the existing screenshot suites.
