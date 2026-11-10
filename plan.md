# Local lecture captions on a computer

This is a build plan for a program that runs on a personal computer. It listens to a lecture, writes live Hebrew–English captions, and fixes them on the same machine. Nothing in this plan runs on the phone, and nothing in this plan changes the lecture-summarizer API.

Hand this file to a later implementation session on its own. The decisions below are already made.

## Outcome

A local window on the computer does three things.

1. **With a lecture.** The user is already recording, or they create a lecture and then start. If they have agreed to the model, captions start on their own. The transcript stays with that lecture on this computer.
2. **Without a lecture.** Opened from the home screen of this program. At the end the user saves a `.txt` file or dumps the text. Dump leaves nothing behind.
3. **Cleanup while the words are still on screen.** A small speech model writes each line immediately. A Hebrew language model then rewrites that same line in place.

The summary of a lecture still comes from the uploaded audio and the existing server. This transcript is for reading locally. Do not add an API endpoint, do not upload the model, and do not send the caption text to the server as part of this work.

## Models

Use two models. They do different jobs.

### Speech, live lines

Default: [`ivrit-ai/zipformer2-66m-streaming-he`](https://huggingface.co/ivrit-ai/zipformer2-66m-streaming-he).

- 66M Zipformer2 transducer, Hebrew fine-tune, about 70 MB in int8 sherpa-onnx.
- It already runs as on-device Hebrew transcription at live-transcribe.ivrit.ai.
- It commits audio in **1.28 second** chunks. That chunk is the latency floor, whatever the CPU speed is.
- On the ScribeIL board (v1.5, 15 Oct 2026, five Hebrew sets) it scores **9.3%** average word error rate. The best open Hebrew model on that board, ivrit-ai Whisper large-v3, scores 6.8%. Turbo scores 7.1%.
- License of the upstream weights: CC-BY-4.0 (Yakov Kolani / thewh1teagle). Confirm the file’s license card before shipping.

Higher accuracy, still local, when a 1.28 s lag is acceptable to trade for fewer errors: [`ivrit-ai/whisper-large-v3-turbo`](https://huggingface.co/ivrit-ai/whisper-large-v3-turbo) through faster-whisper or whisper.cpp. Force `language=he`. Its language-detection and translation heads were damaged by the Hebrew fine-tune. It is not a streaming transducer: feed it windows of a few seconds. On a computer with a GPU this is fast enough for near-live captions. Prefer the Zipformer when the machine is CPU-only.

### Cleanup, the line rewrite

Default on a computer: [`dicta-il/DictaLM-3.0-Nemotron-12B-Instruct`](https://huggingface.co/dicta-il/DictaLM-3.0-Nemotron-12B-Instruct-GGUF), file `DictaLM-3.0-Nemotron-12B-Instruct-Q4_K_M.gguf` (**7.49 GB**).

- Continued from NVIDIA Nemotron Nano 12B on about 100B Hebrew tokens plus English. Apache-2.0.
- Hybrid state-space backbone, so a long prompt costs less memory than a normal 12B transformer.
- Dicta’s Hebrew chat suite: summarization 33.3, translation 13.5, nikud **76.1**. Nikud is the closest published signal that it can spell Hebrew. It is not a code-switch test.
- English benchmarks of the base model are largely retained, so fixing English words inside a Hebrew line is plausible. It has not been measured on this exact task.

Smaller computer, or a machine that cannot hold 7.5 GB: [`dicta-il/DictaLM-3.0-1.7B-Instruct`](https://huggingface.co/dicta-il/DictaLM-3.0-1.7B-Instruct) at Q4_K_M, **1.11 GB** ([community GGUF](https://huggingface.co/EMD123/DictaLM-3.0-1.7B-Instruct-Q4_K_M-GGUF)). Same training recipe, much weaker generation (summarization 9.7, nikud 52.8). Use the Instruct weights. Do not use the Thinking variant. Thinking spends tokens before the answer and misses the live budget.

Backup rewriter if the 12B GGUF is awkward to serve: Gemma 3 12B instruct. On the same suite it summarizes a bit better (39.5) and spells Hebrew less reliably (nikud 51.8).

### What the cleanup model must do

Given one caption line, plus the lecture’s slide terms when a lecture is open:

- Fix Hebrew spelling and grammar.
- Fix English spelling and grammar.
- Turn English words that were spelled in Hebrew letters back into Latin English (`דיפ לרנינג` → `deep learning`) when that is the spoken term.
- Leave Hebrew as Hebrew and leave English that is already in Latin letters as English.
- Do not translate the line. Do not add nikud.

Nobody has published a score for that Hebrew-letter English restoration. A slide glossary has to do the reliable part. The language model handles grammar and terms the glossary does not contain.

### Do not use these for this task

They fail the Hebrew lecture, or they solve the opposite problem (English sentences with a Hebrew word dropped in).

| Model | What goes wrong |
| --- | --- |
| Hebrish Sabich, Jachnun, Whisper-Hebrish, Whisper-Hebrish-Small-ACFT | English-matrix dictation. They write Hebrew in Latin letters and are specified to never write Hebrew script. Whisper-Hebrish-Small is a fine-tune of `whisper-small.en`. |
| Gemma 3 1B, stock Qwen3 1.7B, Llama 3.3 70B as a Hebrew speller | Nikud scores around 3–4. They can sound fluent and still misspell Hebrew, or they translate the line. |
| Qwen3 thinking checkpoints in Dicta’s comparison | Translation near 0, nikud under 5, and the reasoning trace adds delay. |
| Stock Whisper tiny/base | Hebrew collapses. A small listening test put whisper-base near 53% word error. |
| Nemotron 3.5 0.6B, Google Speech, GPT-4o transcribe on Hebrew media | ScribeIL: 33.5% (17.4% after a Hebrew fine-tune), 26.6%, and 16.3% overall with 38.9% on Kan captions. |
| DictaBERT | Hebrew morphology only. It does not rewrite a sentence. |
| ivrit Whisper as the cleanup model | Right Hebrew recognizer. Hebrew training text usually spells English loans in Hebrew letters, so it tends to reproduce the failure this plan is fixing. |
| DictaLM 3.0 24B Thinking | Best open Hebrew quality under 70B (nikud 86.9, summarization 56.9). The thinking trace makes it the wrong live rewriter. |

## Hardware

Check memory before captions start. Do not guess from “computers have a lot of RAM.”

| Machine | Speech | Cleanup | What to expect |
| --- | --- | --- | --- |
| Recent laptop, CPU only, 16 GB RAM | Zipformer | 1.7B Q4 | Speech is easy. A 30–60 token rewrite is on the order of 1–2 seconds. |
| 16 GB RAM, no GPU, trying 12B Q4 | Zipformer | 12B only if about 10 GB is free | Weights are 7.49 GB. It can load and still be too slow for a live line (several seconds). Prefer 1.7B here. |
| Apple silicon, 16 GB unified memory or more, or an 8 GB GPU | Zipformer, or Whisper turbo if a GPU is present | **12B Q4, this is the quality choice** | About 0.4–1.5 seconds to rewrite one finalized line. These speeds are engineering estimates, not a published benchmark of this task. |

Before starting, read free RAM.

- **12B:** need about **10 GB free** (weights, runtime, and the speech model). If the computer has less than 16 GB installed, do not offer 12B.
- **1.7B:** need about **2.5 GB free**, and at least 6 GB installed. A 4 GB machine does not get the cleanup model.
- If free memory is short right now, say so and name the action: close other programs, then try again. Captions do not start the cleanup model on that attempt.
- The speech model can still run when cleanup cannot. Tell the user that the lines will be the raw recognition.
- Check again at the start of every session. Free memory moves. A passed check from yesterday is not a pass today.
- If the user starts anyway while memory is tight, skip cleanup for that session. Do not let the language model push the recorder out of memory.

Disk, once, before the download: the chosen GGUF plus about 256 MB. The Zipformer files are negligible next to that.

## Download, agreement, and freeing space

- Nothing is downloaded on install, on launch, or at the start of a lecture.
- The first time captions would start, show an agreement. It states the size, that the download happens once, that a session uses extra CPU and battery or power, and that the file can be deleted later from Settings.
- The agree button is the only thing that starts the download.
- Download over Wi-Fi or an unmetered network only. On a computer, treat a metered tether the same way: wait, do not use it. There is no “download on a metered connection” button.
- If the file is already complete (larger than 900 MB for the 1.7B Q4, or the 12B file’s real size), do not download again.
- Settings keeps **Free this space** after the file is there. That cancels an in-progress download, deletes the file and any partial file, and clears the agreement so a later run cannot fetch it on its own. Getting it back requires the agreement again.
- Write to a `.partial` file and rename only after the byte count matches. A short file is not “installed.”

Store the files under the program’s own data directory, for example `models/zipformer/` and `models/dictalm-3.0-12b-instruct-q4.gguf`. Do not put them in the lecture folder.

## Pipeline

```
microphone
  → Zipformer, 1.28 s chunks
  → a line, cut on a pause or on sentence punctuation
  → show that line immediately
  → glossary replacements on that line
  → DictaLM rewrites the line
  → replace the on-screen line in place
  → keep both the raw line and the cleaned line
```

Run cleanup only on a finished line, not on every partial hypothesis. Partials flicker.

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

Expected shape of the answer: `האלגוריתם של gradient descent מעדכן את המשקלים`

The glossary runs first and is allowed to override the model. Build it from the lecture’s slide text when a lecture is open. Match a Hebrew-letter span to a slide term by a phonetic key (Hebrew letters mapped to rough Latin sounds, then a small edit distance against the slide vocabulary). Common function words stay Hebrew. If the slides do not contain the term, leave the span for the language model. The glossary match should finish in well under 50 ms.

If the model returns a translation, an empty string, or a line whose length is wildly different, keep the glossary-adjusted line and do not show the model output.

Call the model with a short context: the system rule, the slide terms (cap the list), and the one line. Do not send the whole lecture on every line. A 12B model on a GPU stays near a second only if the prompt stays small.

## Right-to-left and left-to-right

Each line chooses its own direction when it is drawn. The window’s language does not decide the line.

- Count Hebrew letters and Latin letters. Digits and punctuation count for neither.
- More Hebrew letters → right-to-left. English words inside that line still read left-to-right, which is ordinary bidi.
- More Latin letters → left-to-right.
- A tie, or a line with no letters, keeps the direction of the line above. The first line of a session, if it has no letters, follows the UI language.

Apply the same rule every time the transcript is shown, including after a restart. The `.txt` export is plain UTF-8. Direction is not stored in that file.

## Controls

### Recording and an existing lecture

The big Pause and Stop belong to the recording.

- **Pause recording** pauses the microphone and the captions together.
- **Resume recording** restores the caption state from before that pause. If captions had been stopped, they stay stopped.
- **Stop recording** ends the lecture. The transcript stays on the lecture.

Captions have their own thin row, so they can pause while the microphone keeps going.

- Running: “Captions on”, **Pause**, **Stop**.
- **Pause** freezes the text, microphone continues. The row becomes **Continue**.
- **Stop** turns captions off for the rest of this recording. Lines already written stay. The row becomes **Start again**.
- There is no second big red Stop.

Captions start on their own only when all of these are true: the user has agreed, the files are on disk, and the memory check passed. Otherwise the recording proceeds and the row explains why captions are off.

### Home, no lecture

Put **Live captions** on the home screen, under the greeting, including when no lecture is scheduled.

That screen has its own Pause, Continue, and Stop. Text is held in memory for the session. Stop then offers two actions:

- **Save as .txt** writes the cleaned lines to a file the user picks, or to the program’s export folder if they do not pick.
- **Dump** deletes the session. No file, no lecture.

### New lecture

Create the lecture first, the same way a new recording already creates one, then start. Captions follow the recording rules above and are stored on that lecture from the first line.

## What is stored on a lecture

Next to the lecture, a UTF-8 JSON file the program owns, one object per line:

- `t0`, `t1` in seconds from the start of the session
- `raw` the speech-model text
- `text` the line after glossary and cleanup
- `cleaned` true when the language model’s rewrite was accepted

The lecture screen reads `text` and paints direction per line. Deleting the lecture deletes this file. Freeing the model does not delete transcripts.

Do not replace the audio. Do not mark the lecture as summarized. Do not call the API.

## Build order

1. **Memory and files.** Detect installed RAM and free RAM. Agreement, one-time download, partial file, “already complete ⇒ do not fetch”, free-space action. No microphone yet.
2. **Speech only.** Zipformer in, lines out, pause and stop, per-line direction. Dump or save `.txt` from the home screen.
3. **Lecture file.** Attach the JSON transcript to a lecture. Start automatically when a recording starts and the gates pass. Recording pause and stop include captions. The caption row can pause or stop captions alone.
4. **Glossary.** Slide terms in, Hebrew-letter spans out, before any language model.
5. **Cleanup model.** Load 12B or 1.7B based on the memory check. Rewrite finished lines in place. Reject translations. Skip cleanup, not the recording, when memory is short.
6. **Hand test** the lines in the next section on the real machine. Keep a note of latency for one line on that machine.

## Hand tests

Run these on the computer that will be used. Pass means the on-screen line and the saved `text` match the intent, and the direction is the one in the table.

| Spoken line, as the speech model might write it | Intent | Direction |
| --- | --- | --- |
| האלגוריתם של גרדיאנט דיסנט מעדכן את המשקלים | Hebrew sentence, `gradient descent` in Latin if the slides say so | Right to left |
| דיפ לרנינג הוא מקרה פרטי של למידה | `deep learning` restored, rest stays Hebrew | Right to left |
| The gradient descent step updates the weights | English sentence, grammar allowed to be fixed, not translated | Left to right |
| אז ה gradient descent כבר כתוב באותיות לטיניות | Leave the Latin term, fix Hebrew around it | Right to left |
| בסדר | Hebrew, do not translate to “okay” | Right to left |
| 12 | No letters, follow the previous line’s direction | Previous line |

Also check:

- Starting a recording with no agreement does not download and does not caption.
- Agreeing on a metered connection waits.
- A second session does not download again.
- Free this space removes the file, and the next session does not download until a new agreement.
- Pause captions, keep talking, confirm the new audio is recorded and no new line appears. Continue, confirm lines resume.
- Stop captions, keep talking, confirm no new lines. Pause and resume the recording, confirm captions stay off.
- Stop the recording, reopen the lecture, confirm the lines are still there and directions are unchanged.
- Home session, Dump, confirm no file. Home session, Save as .txt, confirm a plain text file and no lecture.
- With the cleanup model’s memory short, the speech lines still show and a sentence asks the user to close other programs.

## Out of scope

- The phone app: its buttons, its home screen, and a model file on the phone.
- Any change under `lecture-summarizer-api`.
- Sending this transcript to the server or skipping the server’s transcription.
- DictaLM 24B, Hebrish, and the other models in the failure table.
- Training or fine-tuning. If the hand tests show the 12B model inventing technical spellings, extend the glossary. Do not start a training run as part of this plan.
