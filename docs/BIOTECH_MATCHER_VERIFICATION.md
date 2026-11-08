# Matcher V2: real-data regression results

Root cause: V1 already used course-deck IDF, but **summed absolute matching vocabulary mass**. The long PPT05 contains 3,071 unique non-stop words, versus 410/491/483 in PPT03/04/02. Its lengthy Hebrew notes match general spoken vocabulary. Dates, recency, Moodle IDs and candidate order were not scoring inputs, so “latest deck”/date proximity did not cause this failure. Plain IDF-normalized coverage alone fixes 28/10 but still wrongly favors PPT05 for 02/11.

V2 normalizes IDF mass by deck vocabulary, uses a small denominator prior to avoid tiny-deck overconfidence, and geometrically balances independently evidenced Unicode scripts. One verbose language channel cannot drown out scientific anchors in another. It is script-neutral, not a hardcoded Hebrew/English or Biotech mapping. Repeated words still do not inflate scores. Only explicit teaching dates (not upload/modified dates or IDs) can incur a future-date penalty; the real fixtures have no such dates and pass without that penalty. Ties have deterministic ID ordering.

| Real lecture | Top suggestion | Score | Runner-up | Score |
|---|---|---|---|---|
| 28/10 | **PPT03** | 18.50 | PPT04 | 12.56 |
| 02/11 | **PPT04** | 20.82 | PPT05 | 13.44 |

Both expected real top suggestions now have regression assertions, including shuffled candidate order. Existing synthetic fixtures and new verbose-note, bilingual and future-date tests pass: 103 tests in each debug/release variant, no skipped tests with the private fixture environment supplied.

**Both still ABSTAIN under the unchanged 1.6× clear-winner gate.** This is intentional: the top suggestions are correct, not calibrated certainty. Do not weaken ambiguity thresholds to fit two examples. Android remains suggestions-only regardless; no lecture or live attachment was changed. The candidate pool remains only four known real decks; wider-course/other-course validation is still needed. Raw fixtures remain outside Git.

---

## Historical V1 investigation (before this fix)

# Real Biotech slide-matcher verification (read-only)

The existing V1 matcher was run against the actual stored transcripts for Biotech 28/10 and 02/11 and four known real course decks (PPT02–PPT05). Inputs were read from the lectures database without updates. Raw transcripts/deck text remain private external fixtures, not repository files.

| Lecture | Required fixture | Actual clear winner | Actual score/shared terms | Required deck score/shared terms |
|---|---|---|---|---|
| 28/10 | PPT03 | **PPT05** | 993.22 / 676 | PPT03: 182.83 / 155 |
| 02/11 | PPT04 | **PPT05** | 1400.58 / 935 | PPT04: 157.80 / 133 |

**Neither real fixture passes the expected mapping.** A green diagnostic test is not an accuracy assertion. The pool is four known decks, not an exhaustive Moodle catalog. PPT05 (Keytruda example) contains 55,377 extracted characters, versus 5,420/6,370/6,609 for PPT02/PPT03/PPT04; absolute-overlap/long-deck bias is a plausible contributor, not a proven sole cause.

The existing live attachments were PPT04 for 28/10 and PPT05 for 02/11. They were **not changed**, nor were lectures re-summarized or jobs polled. Automatic matching in this Android branch is downgraded to suggestions-only. It is not safe to enable unattended slide attachment based on the current “clear winner” margin.

Reproduce locally with private `decks.json` (rows: name,text) and `transcripts.json` (rows: id,title,job_id,updated_at,segments JSON string):

```sh
REAL_BIOTECH_FIXTURES=/path/to/private-fixtures ./gradlew testDebugUnitTest --tests '*RealBiotechMatcherTest' --rerun-tasks
```

Without external fixtures the diagnostic is intentionally skipped. With them it prints candidate names, counts and scores only, never transcript content. Synthetic matcher unit tests still cover algorithm behavior; they do not overrule these real failures.
