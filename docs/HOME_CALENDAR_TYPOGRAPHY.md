# Smaller Home/calendar typography and realistic-density renders

Local follow-up to `1dcb519` on `feature/study-tools`. No API changes, push, merge, deployment, or screenshot-security-policy changes.

## Density audit

The previously reviewed `after-home-he-normal.png` was a genuine **100% font** capture at 393×852 mdpi (density 1). It was not captured with an enlarged font. The other two reviewed files (`after-lecture-question-he-large-small.png` and `after-series-picker-scrolled-he-large-small.png`) were deliberately **200% accessibility-font** cases on a narrow 320×640 mdpi canvas, not normal-phone previews. Viewing those as default-sized screenshots makes the UI appear much larger than its normal setting.

The new before/after set is rendered natively at **420dpi / density 2.625**, with an approximately **1080×2400** target display and logical configuration 411×914dp. The actual native Compose content capture is **1078×2399** pixels because Android/Robolectric rounds integer dp/window bounds. This is a 1–2px edge difference, not font inflation or bitmap scaling. The original PNGs were not resized or upscaled. Contact sheets are explicitly thumbnails.

The gallery defaults to **100%**, displays each phone at about 411 CSS px wide, and separates the **200% accessibility** cases behind a labelled selector. Both Hebrew RTL and English LTR have matching before/after captures using the same synthetic data and density. Baseline harness: `/workspace/lecture-summarizer/drafts/home-layout/phone-before-1dcb519-test.kt`.

## Scoped scale — not a global redesign

`CompactCalendarTypography` changes only Home and the calendar-import/watch journey. It retains Rubik, colors, card shapes and the surrounding app's default typography elsewhere:

| Role | New size / line height |
| --- | --- |
| Home greeting | 18 / 24sp (previously 28 / 34sp) |
| Screen and section titles | 16 / 22sp (previously 20 / 26sp) |
| Course/series row titles | 14 / 20sp (previously 15 / 22sp) |
| Body and primary actions | 14 / 20sp |
| Secondary text | 12 / 16sp |

Home outer spacing is 16dp, cards 12dp, and most calendar rows use 8dp padding. The action footer uses 16dp horizontal and 8dp vertical padding. Spacing shrinks, not touch targets: minimum interactive/primary-action size remains 48dp. The font profile does **not** override density or accessibility `fontScale`. Wrapping, full-width question/count rows, series-only matching and navigation/IME safety remain intact. There are no new maximum-line or fixed-height text constraints.

## Verification/artifacts

- Normal and 200% phone renders validate no visual overflow/ellipsized lines on the count/question, safe footer bounds, series selection/search/bulk actions and bounded Home expansion.
- A dedicated profile test verifies 14/12/16sp tokens, the 18sp greeting, unchanged Rubik, retained 200% accessibility scaling, and restoration of the original 15sp outer typography after leaving the scoped theme.
- The prior 320×640 normal/200% navigation/IME regression matrix is also rerun in a separate output directory, without overwriting the phone comparisons or older reviewed artifacts.

Phone gallery and density manifest: `/workspace/lecture-summarizer/screens/typography-phone/index.html` and `manifest.json` (52 PNGs / 26 paired states). Normal-font files are `before-…-he-normal.png` / `after-…-he-normal.png`, likewise `en-normal`. Large-font files end in `-large.png` and are not default previews.

Small-screen regression artifacts: `/workspace/lecture-summarizer/screens/typography-small-regression/`. All data are synthetic native Robolectric/Compose renders, not physical-phone screenshots. Physical-device, TalkBack and OEM nonlinear-font scaling behavior still need on-device verification.

Final verification: **174 debug + 174 release unit tests**, zero failures/errors/skips; **7 phone-density native tests + 8 small-screen native tests** pass; `assembleDebug` succeeds. Lint: zero errors, 65 warnings and 3 informational findings. The 52 phone comparison renders were visually reviewed.
