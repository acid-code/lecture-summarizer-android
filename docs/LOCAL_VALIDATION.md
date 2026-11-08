# Local validation

Command (JDK 17):

```sh
REAL_BIOTECH_FIXTURES=/workspace/biotech-real-verification ./gradlew testDebugUnitTest testReleaseUnitTest assembleDebug lintDebug --console=plain
```

Final build: **successful**.

- Debug: {'tests': 100, 'failures': 0, 'errors': 0, 'skipped': 0}
- Release: {'tests': 100, 'failures': 0, 'errors': 0, 'skipped': 0}
- Android lint: {'Error': 0, 'Warning': 48}
- Hebrew/English resource parity: 565 English string entries; no missing translatable keys.
- APK permission audit: READ_CALENDAR present; WRITE_CALENDAR absent. No `gmail.readonly` or removed mailbox implementation marker in DEX. Debug default mock is false; release mock getter remains forced false.
- `git diff --check`: clean.

The opt-in real-data diagnostic ran in both variants with no skip. Its green test means successful execution, **not successful deck accuracy**: both actual Biotech expectations fail (PPT05 is chosen twice). See BIOTECH_MATCHER_VERIFICATION.md.

These are JVM/build/lint checks, not device/provider/end-to-end tests. No screenshots were rendered. Authenticated folder/session behavior, CalendarContract integration, background scheduling, undo/race behavior, PDF rebuild completion and complete light/dark locale layouts remain unverified. Plural-form polish remains despite formatting lint improvements.
