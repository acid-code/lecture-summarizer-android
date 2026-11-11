# Local lecture captions on the phone

This is a build plan for **on-device captions inside the Android lecture-summarizer app**. It listens while the user records (or from a home “Live captions” entry), shows live Hebrew–English lines, and cleans them on the phone. Nothing in this plan changes the lecture-summarizer API, and nothing requires a desktop companion.

Hand this file to a later implementation session on its own. The decisions below are already made.

**Correction from the first draft:** this is phone-side only (not a PC program). Cleanup uses a **~1 GB** Instruct GGUF, not the 7.5 GB 12B weights.

## Outcome

The Android app does three things.

1. **With a lecture.** The user is already recording, or they create a lecture and then start. If they have agreed to the model, captions start on their own. The transcript stays with that lecture on the device.
2. **Without a lecture.** Opened from the app home screen. At the end the user saves a `.txt` file or dumps the text. Dump leaves nothing behind.
3. **Cleanup while the words are still on screen.** A small speech model writes each line immediately. A Hebrew language model (~1 GB on disk) then rewrites that same line in place.

The summary of a lecture still comes from the uploaded audio and the existing server. This transcript is for reading locally. Do not add an API endpoint, do not upload the model, and do not send the caption text to the server as part of this work.

## Models

Use two models. They do different jobs.

### Speech, live lines

Default: [`ivrit-ai/zipformer2-66m-streaming-he`](https://huggingface.co/ivrit-ai/zipformer2-66m-streaming-he) via **sherpa-onnx** (Android JNI / AAR).

- 66M Zipformer2 transducer, Hebrew fine-tune, about **70 MB** in int8.
- It already runs as on-device Hebrew transcription at live-transcribe.ivrit.ai.
- It commits audio in **1.28 second** chunks. That chunk is the latency floor.
- On the ScribeIL board (v1.5, 15 Oct 2026) it scores **9.3%** average WER (vs Whisper large-v3 HE at 6.8%).
- License of the upstream weights: CC-BY-4.0 (Yakov Kolani / thewh1teagle). Confirm the file’s license card before shipping.

Do **not** pull Whisper large / turbo onto the phone for v1. Too heavy for the live path; Zipformer is the phone speech default.

### Cleanup, the line rewrite (~1 GB)

Default: [`dicta-il/DictaLM-3.0-1.7B-Instruct`](https://huggingface.co/dicta-il/DictaLM-3.0-1.7B-Instruct) at **Q4_K_M**, about **1.11 GB** ([community GGUF](https://huggingface.co/EMD123/DictaLM-3.0-1.7B-Instruct-Q4_K_M-GGUF) or an official GGUF if published).

- Same DictaLM 3.0 training recipe as the larger Instruct models; weaker generation than 12B (summarization 9.7, nikud 52.8) but sized for phones.
- Serve with **llama.cpp Android** (or an equivalent on-device GGUF runtime the app already can vendor). Prefer CPU + optional GPU/NPU backends the library supports; do not require a separate PC.
- Use the **Instruct** weights. Do **not** use Thinking variants (reasoning traces blow the live budget).

**Out of default for phone:** DictaLM 12B Q4 (~7.5 GB). Too large for typical device free space and RAM. Do not offer it in Settings for v1.

Backup rewriter only if 1.7B GGUF packaging fails: a similarly sized Hebrew-capable Instruct GGUF (~1 GB). Do not fall back to Hebrish / English-matrix models.

### What the cleanup model must do

Given one caption line, plus the lecture’s slide terms when a lecture is open:

- Fix Hebrew spelling and grammar.
- Fix English spelling and grammar.
- Turn English words that were spelled in Hebrew letters back into Latin English (`דיפ לרנינג` → `deep learning`) when that is the spoken term.
- Leave Hebrew as Hebrew and leave English that is already in Latin letters as English.
- Do not translate the line. Do not add nikud.

The slide glossary does the reliable term restoration; the LM handles grammar and unknown terms.

### Do not use these for this task

| Model | What goes wrong |
| --- | --- |
| Hebrish Sabich, Jachnun, Whisper-Hebrish, Whisper-Hebrish-Small-ACFT | English-matrix dictation; never write Hebrew script. |
| Gemma 3 1B, stock Qwen3 1.7B, Llama 3.3 70B as a Hebrew speller | Poor Hebrew spelling / translate the line. |
| Qwen3 thinking / DictaLM Thinking checkpoints | Delay + weak nikud/translation on live rewrite. |
| Stock Whisper tiny/base | Hebrew collapses (~50%+ WER in small tests). |
| DictaBERT | Morphology only; does not rewrite a sentence. |
| ivrit Whisper as the cleanup model | Tends to keep English loans in Hebrew letters. |
| DictaLM 3.0 12B / 24B as phone defaults | Too large for v1 on-device (~7.5 GB+). |

## Hardware (phone)

Check memory and free disk **before** captions start. Use `ActivityManager` / `ActivityManager.MemoryInfo` and `StatFs` on the models directory—not a desktop RAM table.

| Device class | Speech | Cleanup | What to expect |
| --- | --- | --- | --- |
| Mid/high Android, ≥6 GB RAM, ≥2.5 GB free | Zipformer | 1.7B Q4 | Speech is easy. A short rewrite may take ~1–3 s on CPU; keep cleanup off the UI thread. |
| Low RAM (≤4 GB installed) or &lt;2 GB free | Zipformer | **skip cleanup** | Show raw recognition; explain that cleanup needs free memory / a larger device. |
| Very tight free disk (&lt;~1.3 GB for the GGUF + headroom) | Zipformer only after download fits | no download | Agreement offers download only when space is enough. |

Before starting:

- **1.7B Q4:** need about **2.5 GB free RAM** to load safely with the speech model, and enough **disk** for ~1.11 GB + ~256 MB headroom.
- If free memory is short, say so (close other apps) and **do not** start cleanup. Speech can still run.
- Check again at the start of every session.
- If the user starts anyway while memory is tight, skip cleanup for that session. Never let the LM push the recorder out of memory (OOM the recording process).

## Download, agreement, and freeing space

- Nothing is downloaded on install, on launch, or at the start of a lecture.
- The first time captions would start, show an agreement: size (~1.1 GB cleanup + ~70 MB speech), one-time download, CPU/battery cost, deletable later from Settings.
- The agree button is the only thing that starts the download.
- Download over **Wi‑Fi / unmetered** only (`NetworkCapabilities.NET_CAPABILITY_NOT_METERED`). No “download on cellular” button in v1.
- If the cleanup file is already complete (size ≥ ~900 MB for the 1.7B Q4, or the exact published byte size), do not download again. Same for Zipformer assets once complete.
- Settings keeps **Free this space**: cancel in-progress download, delete model files and partials, clear the agreement. Getting models back requires agreeing again.
- Write to a `.partial` file and rename only after the byte count matches.

Store under the app’s private files dir, e.g. `files/models/zipformer/` and `files/models/dictalm-3.0-1.7b-instruct-q4.gguf`. Do not put them in the lecture media folder.

## Pipeline

```
microphone (existing recorder)
  → Zipformer, 1.28 s chunks
  → a line, cut on a pause or on sentence punctuation
  → show that line immediately
  → glossary replacements on that line
  → DictaLM 1.7B rewrites the line
  → replace the on-screen line in place
  → keep both the raw line and the cleaned line
```

Run cleanup only on a finished line, not on every partial hypothesis.

Cleanup prompt, one line, no JSON:

```
You fix one caption line from a Hebrew university lecture that mixes in English.
Keep Hebrew in Hebrew and English in Latin letters.
Fix Hebrew spelling and grammar, and English spelling and grammar.
If an English word was spelled with Hebrew letters, write it in Latin letters.
Do not translate. Do not add nikud. Return the line only.

Slide terms: gradient descent, backpropagation
Line: האלגוריתם של גרדיאנט דיסנט מעדכן את המשקלים
```

Expected shape: `האלגוריתם של gradient descent מעדכן את המשקלים`

Glossary runs first and may override the model. Build from lecture slide text when a lecture is open. Phonetic key + small edit distance vs slide vocabulary; common function words stay Hebrew. Match should finish in well under 50 ms.

If the model returns a translation, empty string, or wildly different length, keep the glossary-adjusted line.

Keep the prompt small: system rule, capped slide terms, one line.

## Right-to-left and left-to-right

Each line chooses its own direction when drawn (Compose / bidi), independent of the app language.

- Count Hebrew letters vs Latin letters (digits/punctuation count for neither).
- More Hebrew → RTL (English runs stay LTR inside via ordinary bidi).
- More Latin → LTR.
- Tie / no letters → previous line’s direction; first line follows UI locale if empty.

`.txt` export is plain UTF-8; direction is not stored in that file.

## Controls

### Recording and an existing lecture

Reuse the existing Pause / Stop recording controls.

- **Pause recording** pauses the microphone and the captions together.
- **Resume recording** restores the caption state from before that pause. If captions had been stopped, they stay stopped.
- **Stop recording** ends the lecture. The transcript stays on the lecture.

Captions have their own thin row so they can pause while the mic keeps going:

- Running: “Captions on”, **Pause**, **Stop**.
- **Pause** freezes the text; mic continues → row becomes **Continue**.
- **Stop** turns captions off for the rest of this recording; existing lines stay → **Start again**.

Captions auto-start only when: user agreed, files on disk, memory/disk checks passed. Otherwise recording proceeds and the row explains why captions are off.

### Home, no lecture

Put **Live captions** on the home screen (under the greeting), including when no lecture is scheduled.

That screen has its own Pause / Continue / Stop. Text is in memory for the session. Stop then offers:

- **Save as .txt** (SAF picker or app export folder).
- **Dump** deletes the session (no file, no lecture).

### New lecture

Create the lecture first (same as today’s new recording), then start. Captions follow the recording rules and are stored on that lecture from the first line.

## What is stored on a lecture

Next to the lecture, a UTF-8 JSON file the app owns, one object per line:

- `t0`, `t1` in seconds from the start of the session
- `raw` the speech-model text
- `text` after glossary and cleanup
- `cleaned` true when the LM rewrite was accepted

The lecture screen reads `text` and paints direction per line. Deleting the lecture deletes this file. Freeing the model does not delete transcripts.

Do not replace the audio. Do not mark the lecture as summarized. Do not call the API.

## Build order

1. **Memory, disk, files.** Detect free RAM/disk. Agreement, one-time download, partial file, completeness check, free-space action. No microphone yet.
2. **Speech only.** Zipformer in, lines out, pause/stop, per-line direction. Dump or save `.txt` from home.
3. **Lecture file.** Attach JSON transcript to a lecture. Auto-start when recording starts and gates pass. Recording pause/stop include captions; caption row can pause/stop alone.
4. **Glossary.** Slide terms → Hebrew-letter span fixes before any LM.
5. **Cleanup model.** Load 1.7B Q4 when memory allows. Rewrite finished lines in place. Reject translations. Skip cleanup (not recording) when memory is short.
6. **Hand test** on a real phone; note per-line latency.

## Hand tests

Run on a physical Android device. Pass = on-screen line and saved `text` match intent, and direction matches the table.

| Spoken line, as the speech model might write it | Intent | Direction |
| --- | --- | --- |
| האלגוריתם של גרדיאנט דיסנט מעדכן את המשקלים | Hebrew sentence, `gradient descent` in Latin if slides say so | RTL |
| דיפ לרנינג הוא מקרה פרטי של למידה | `deep learning` restored, rest Hebrew | RTL |
| The gradient descent step updates the weights | English sentence, not translated | LTR |
| אז ה gradient descent כבר כתוב באותיות לטיניות | Keep Latin term, fix Hebrew around it | RTL |
| בסדר | Hebrew, do not translate to “okay” | RTL |
| 12 | No letters → previous direction | Previous |

Also check:

- Recording with no agreement → no download, no captions.
- Agree on metered network → wait (no download).
- Second session → no re-download.
- Free this space → next session needs a new agreement.
- Pause captions, keep talking → audio recorded, no new lines; Continue resumes.
- Stop captions, keep talking → no new lines; pause/resume recording keeps captions off.
- Stop recording, reopen lecture → lines and directions persist.
- Home Dump → no file; Home Save → plain `.txt`, no lecture.
- Cleanup memory short → raw speech lines still show + prompt to free memory.

## Out of scope

- A separate desktop / PC companion app.
- Any change under `lecture-summarizer-api`.
- Sending this transcript to the server or skipping the server’s ASR.
- DictaLM 12B/24B as phone defaults; Hebrish and the other failure-table models.
- Training or fine-tuning. If hand tests show bad technical spellings, extend the glossary first.
