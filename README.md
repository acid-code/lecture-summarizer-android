# Lecture Summarizer — Android

Native **Kotlin + Jetpack Compose (Material 3)** recorder for TAU lectures. Records 45–50 min lecture parts
in a **microphone foreground service** (keeps recording with the screen off — a PWA can't), lets you tap
**"Next slide / topic"** markers, attach optional slides (PDF/PPTX) and an optional course-context PDF,
uploads everything to the companion API ([lecture-summarizer-api](https://github.com/acid-code/lecture-summarizer-api)),
polls the job, and opens/shares the resulting study PDF.

The phone **never calls Gemini or any model** — only the API. (Free path = Gemini on the backend.)

## Run (Android Studio)
1. Open this folder in Android Studio (Ladybug or newer). JDK 17. It uses the Gradle wrapper (8.11.1, AGP 8.7.3, Kotlin 2.0.21).
2. Let it sync, pick a device (Android 8.0 / API 26+, target API 35), press **Run**.
3. CLI: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

## Configuration (`local.properties`, git-ignored)
```properties
sdk.dir=/path/to/Android/sdk
lecture.baseUrl=https://your-api.example.com   # default http://10.0.2.2:8000 (host machine from emulator)
lecture.apiToken=                               # optional bearer token — never commit it
lecture.mock=true                               # default true
```
All three can also be changed at runtime in **Settings** (gear icon on the Courses screen).

## Mock mode
On by default. No network at all: uploads are simulated, the job walks through the backend stages
(`uploaded → normalized → transcribed → slides_ingested → aligned → repaired → summarized → rendered`) every 2 s,
and a placeholder PDF is generated on-device. Turn it off in Settings once the API is up.

## Screens / flow
Courses → Lectures (per course) → **Record** (start/stop, elapsed, segment #, "Next slide" marker; also in the notification)
→ **Attach & upload** (slides, context PDF, upload) → **Job status** (progress, retry, open / share PDF) · Settings.

## Permissions
| Permission | Why |
|---|---|
| `RECORD_AUDIO` | record the lecture (runtime prompt) |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` | Android 14+ requires a typed mic foreground service |
| `POST_NOTIFICATIONS` | ongoing recording notification (Android 13+, runtime prompt) |
| `WAKE_LOCK` | keep CPU awake during long recordings |
| `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE_DATA_SYNC` | uploads via WorkManager |

## Recording safety
- AAC 16 kHz mono 32 kbps `.m4a` (~14 MB/hour) in app-private storage.
- Audio is rotated into **10-minute segments**; each finished segment is saved to the local DB immediately,
  so a crash/kill loses at most the current segment. Stop → Resume continues the same lecture (markers stay monotonic).
- Upload (`UploadWorker`, WorkManager): network-constrained, exponential backoff, idempotent — remembers the remote
  course/lecture IDs, which segments were uploaded, and the job ID, so retries resume.

## API contract used (PLAN.md §5)
`POST /v1/courses` · `POST /v1/lectures` · `POST /v1/lectures/{id}/slides` (multipart) · `POST /v1/lectures/{id}/context` (multipart PDF; stored on the lecture's course, also `POST /v1/courses/{id}/context`) ·
`POST /v1/lectures/{id}/audio/segments` (multipart: seq, started_at, duration, file) · `POST /v1/lectures/{id}/markers` `[{t_sec,page}]` ·
`POST /v1/lectures/{id}/process` → `{job_id}` · `GET /v1/jobs/{job_id}` → `{state,stage,progress,error}` (each poll advances one server stage) · `GET /v1/lectures/{id}/summary.pdf`.
Full contract table: lecture-summarizer-api README (`/v1` section). On Vercel the 4.5 MB body limit means large segments need Blob client upload + JSON `{url}` (TODO in this client).
Hebrew (RTL) content is handled by the backend/PDF; the app uses `supportsRtl` and Unicode everywhere.

## Privacy
Recordings, slides and PDFs stay in app-private storage (`allowBackup=false`) and are only sent to the API URL
**you** configure. No analytics, no third-party SDKs, no direct AI calls. Make sure lecturers consent to being recorded.
This repo is public: never commit real recordings (audio extensions are git-ignored) or tokens.

## Known gaps / TODO
- Resumable (tus/chunked) upload for very large segments; currently one multipart POST per 10-min segment.
- Auto-fallback to mock when the API is unreachable (currently a manual Settings switch).
- Marker → slide page mapping assumes "page = marker index + 2"; backend alignment refines it.
- Audio-focus / phone-call interruption handling; low-storage check; app icon.
