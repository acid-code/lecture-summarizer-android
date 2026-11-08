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
