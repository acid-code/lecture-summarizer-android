# Study tools — feature/study-tools

Local branch from `425fe47`; no push, deploy or merge. Existing Home, Settings and Search layout stays unchanged. Two lecture-specific study actions appear after listen/transcript and before materials. All new copy is Hebrew-default with English resources; both light and dark themes use existing app typography, navy actions, rounded bordered cards and RTL layout.

## Memory game

The only concept source is the lecture's existing summary glossary (`term` / `explanation`). No concepts or definitions are invented. Download once while online; later play is fully offline from a private on-device cache per lecture and density. Summary completion/refresh workers update the cache best-effort without risking existing PDF availability; a failed glossary fetch preserves cached data. A manual update is available in the game. Cache metadata includes summary job, time and a content fingerprint.

A round uses at most four pairs (two columns, 120dp cards). Terms and definitions may each be selected first. Matching uses concept identity, never visible text equality. Duplicate/blank/self-equal definitions are removed. At least two valid concepts are required. Large concept sets get multiple rounds, avoiding a last one-pair round. Mismatches remain visible until explicitly closed; no timer or pressure. Matched cards remain readable with check icons; long text has a full-content dialog. Flip state and attempts survive activity recreation. Clear cached study data/chat when a lecture or course is deleted.

## Lecture tutor

The client sends the question, current app language and up to three prior **successful user questions** (400 characters each) for follow-up disambiguation. It never sends assistant messages as evidence or allows arbitrary client sources. Device/bearer headers use the existing API client. Answers appear only after the server's topic/coverage, citation and support checks; no unverified streaming. Sources expand to verbatim quotes. Suggested concept prompts only fill the input—nothing is sent until the user presses Send.

A private per-lecture local history stores at most 20 turns, including server-returned source quotes. It remains readable offline, can be explicitly cleared, and is deleted with the lecture. Failed requests keep the typed question. Missing deployment, unavailable provider, ownership, not-ready, offline and rate-limit conditions get actionable localized messages; Retry-After drives the countdown.

**API deployment is required for tutor availability.** Until the new route is deployed, its unavailable state explains the server update. Memory already works against the existing summary endpoint. No Google Cloud OAuth setup, mail permission, new API key or Gemini secret in Android is needed. Existing server keys and multi-key/model failover are reused. See the API repository's `docs/study-tools.md` for guards, limits and deployment details.

The tutor is intentionally conservative and can answer “not covered” for missed retrieval, image-only slides or insufficient evidence. Strict system instructions plus several server checks are defense in depth, not a guarantee against every possible prompt injection/hallucination. The existing shared API token/device-ID model is not cryptographic per-user authentication or Sybil-proof rate limiting.

## Verification

Final normal run: 156 debug unit tests and 156 release unit tests pass (including existing real-data slide fixtures), `assembleDebug` passes and `lintDebug` has zero errors (62 warnings, 3 informational findings). Test-only MockWebServer verifies real HTTP payloads, bearer/device headers, glossary parsing and Retry-After.

Native screenshot/interaction run: 6 passing Robolectric/Compose tests verify matching/mismatch/completion, source expansion, saved-state restore and the long-definition dialog. 56 native component renders (14 states × HE/EN × light/dark) were visually reviewed; the final matched-card contrast was corrected. They are **not physical-device screenshots** and use synthetic lecture data only.

Gallery: `/workspace/lecture-summarizer/screens/study-tools/index.html`.
Manifest and XML: `manifest.json`, `native-interaction-results.xml` in that directory. Native render command:

```sh
JAVA_HOME=/home/box/jdk17 PATH=/home/box/jdk17/bin:$PATH \
LECTURE_SCREEN_DIR=/workspace/lecture-summarizer/screens/study-tools \
./gradlew testDebugUnitTest -Pscreenshots=true --tests '*StudyToolsScreenshotsTest' --console=plain
```

Unverified: live Gemini end-to-end/adversarial behavior and failover against a real provider; physical-device flow, TalkBack, large-font layout, process death beyond saved-state recreation, and production migration. Backend tests use temporary local DBs and scripted model outputs; the separate SDK failover test intercepts all network calls locally.

## UX choices for product review

- Four untimed pairs per round; mismatches close manually, with no automatic timed flip.
- Matched cards stay readable and long definitions open fully instead of shrinking text.
- Source-grounded short answers only; prominent not-covered/refused states and expandable quotes.
- Local history per lecture (20 turns), explicit clear action, no automatic question submission.
- A glossary download is explicit when no cached concepts exist; empty sets never silently generate concepts.
