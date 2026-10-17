# Scribe

Record a class, watch the transcript appear as it happens, turn it into
structured notes.

Built for phones on bad wifi: it uploads about **8.6 MB per hour** (measured,
not estimated), keeps the CPU mostly idle, and never loses a recording when the
network drops.

---

## Android app: sharing files to transcribe

Install: [latest APK](https://github.com/alt000007/Audio-transcript-live-for-notes-recordings/releases/latest-android).
Builds are now all signed with the same key, so each new APK installs over the
last and keeps everything. The first one after the old builds needs **one last
uninstall** (they were each signed with a random key); copy anything you want
to keep from the Notes tab first.

### How the queue works

1. Share one or many files to Scribe (or use *Choose a recording* / *Browse
   recordings*). Each file becomes a job and appears at once in **Notes ›
   Transcribing**. You can keep adding files while a batch runs.
2. The file is **copied into the app straight away**, so the original can be
   moved or deleted and nothing ever has to be shared twice. A fingerprint
   (SHA-256) is taken during the copy; sharing the same file again opens the
   existing transcript instead of doing it again.
3. The audio is decoded, mixed to 16 kHz mono, long silences are shortened
   (pauses over 0.8 s, with the times kept so timestamps still point at the
   original), and cut into **5-minute parts** encoded as 32 kbps AAC.
4. Parts transcribe **side by side** — by default 2 files at once and 3 parts
   at once, front of the queue first — so the first file is readable long
   before the last one starts.
5. Each finished part is saved immediately; each finished file lands in **Your
   recordings** straight away, where it can be opened, copied, shared,
   downloaded, edited, corrected with AI or turned into notes. A running file
   can be opened too, and its text grows as parts finish.
6. Jobs can be cancelled, retried (only failed parts are redone) and moved up
   or down. Statuses: *waiting* (with place in line), *working* (with
   progress), *done* (with how long it took), *failed* (with the reason).

It runs in a foreground service with a progress notification, so it carries on
with the screen off or the app closed. Optional notifications for each finished
file and for the whole batch (Settings). On Xiaomi phones, tap **Let Scribe work
in the background** in Settings once, or the system may pause it anyway.

If the app is closed, crashes, the phone reboots or the network drops, nothing
is lost: open Scribe and unfinished files carry on from the last saved part.
Rate limits and dropped connections are waits, never failures.

### Where things are saved

| What | Where |
|---|---|
| Copied audio, queue, job states, each part's text | `/data/data/app.scribe/files/jobs/` (app-private). `order.json` is the queue; `<job>/job.json` its state; `<job>/chunks/cN.json` each part's transcript; `<job>/result.json` the finished one. Every write goes to a temp file and is renamed over the old one. Audio copies are deleted once a file is done (unless *Keep the app's copy* is on). |
| Your recordings, notes, edits | The app's IndexedDB (`scribe-db`), as before |
| Groq key | Encrypted with a key held in the Android Keystore; never in plain text, never in the code. (Browser build: AES-GCM with a non-extractable WebCrypto key in IndexedDB.) |
| Offline model | `Android/data/app.scribe/files/models/` |

### Engines, cost and limits

Settings › *Shared and imported files* › **Engine**:

- **Automatic** (default): Groq's free plan, and the phone when Groq says to
  wait more than 5 minutes (allowance used up) — if the offline model is
  downloaded. Otherwise files just wait and resume on their own.
- **Groq only**: `whisper-large-v3-turbo`, the same model as before, so
  accuracy is unchanged. Free plan, no card: you cannot be charged. Limits
  (third-party reports, October 2026 — check your own at
  console.groq.com/settings/limits): about **20 requests/minute, 2,000/day,
  7,200 audio-seconds/hour (2 h of audio per hour) and 28,800/day (8 h)**. The
  app spaces requests to stay under the per-minute limit and waits out the
  others, showing *Waiting for the rate limit — 12 min*.
- **On this phone only**: whisper.cpp with the Small (190 MB) or Base (60 MB)
  model, downloaded once from Settings. Free with no limits, offline, private.
  But it is **noticeably less accurate** than Groq (especially in Italian and
  on technical words), much slower — often slower than real time on a phone —
  and heavy on battery. That is why it is a fallback, not the default.
  Needs a 64-bit phone with ARMv8.2 (any phone running a current Android).

### Speed: before and after

Measured with the real queue code on a desktop with simulated network and Groq
speed (`tests/queue-sim`, a 2 Mbit/s uplink, 128 kbps source files), not on a
phone:

| Batch | Before | After |
|---|---|---|
| 5 voice notes, 30 min total — whole batch | 2.2 min | 0.6 min |
| 5 voice notes — first result readable | 0.3 min | 0.2 min |
| 5 lectures, 320 min total — whole batch | 128 min* | 122 min |
| 5 lectures — first result readable | 3.5 min | 1.0 min |

\* Before, the batch stopped when the hourly allowance ran out and had to be
shared again; the figure assumes you re-shared at exactly the right moment.
For big batches the free plan's 2 hours of audio per hour is the ceiling
whatever the app does; silence trimming stretches it by the amount removed
(about a quarter in the test recording). Each job shows its own processing time
and speed in the queue, so you can compare on your phone.

### Judgment calls (defaults you can change)

- Engine *Automatic*; 2 files and 3 parts at once; 5-minute parts.
- Silence trimming on, conservative: only the middle of pauses longer than
  0.8 s is removed, and only when the level is near the room's noise floor.
- Notify when everything is done: on. Each file: off.
- Audio copies deleted after transcription; the transcript is kept.
- "Clear finished" hides done jobs from the queue but remembers their
  fingerprints. Deleting a recording from *Your recordings* forgets it, so
  sharing it again transcribes it again.
- The signing key is in the repo (`android/signing`) so builds can update each
  other. It is a sideload key for a personal app. To use a private one, add it
  as the `SCRIBE_KEYSTORE_B64` and `SCRIBE_KEYSTORE_PASSWORD` Actions secrets.
- The queue lives in the Android app. The browser version keeps its simpler
  one-file-at-a-time importer (a web page cannot work in the background).

---

## How it works

```
mic ──► MediaRecorder (Opus, 20 kbps)
         │
         │  cut at natural pauses, not on a timer
         ▼
    IndexedDB  ◄── everything lands here FIRST
         │
         │  queue drains when online, retries with backoff
         ▼
   Whisper large-v3-turbo  ──► segment text ──► live transcript
         │
         ▼
   structuring pass  ──► summary + topics + corrections
```

### Why audio is chunked at pauses

Whisper transcribes files, not streams. Something has to decide where one file
ends and the next begins.

Cutting on a fixed timer slices words in half. So the recorder watches the
microphone level and cuts when the speaker actually pauses — between 4 and 12
seconds, whichever comes first. Text appears a few seconds behind the speaker
rather than word by word, which is the honest trade for running on a free tier
with the battery intact.

Each segment is a **standalone, independently decodable audio file**. This is
why the recorder stops and restarts the `MediaRecorder` at every cut instead of
using `timeslice`: with `timeslice`, only the first chunk carries the container
header, so later chunks are not valid files and no transcription API can read
them. Restarting costs a few milliseconds at each boundary — which is precisely
why the boundary is placed during silence.

### Why the raw transcript is never overwritten

The cleanup pass may repair *transcription* and impose *structure*. It may not
add information. Every term it changes is recorded in a **corrections** list you
can open and audit against the raw text, and the verbatim transcript stays one
tap away.

Notes you cannot check are worse than a messy transcript, because you will study
from them without checking.

### Why the glossary matters more than you'd think

Course jargon, lecturer names and technical terms go in Settings → Glossary.
They are fed to Whisper **as a decoding hint while it transcribes**, not used to
find-and-replace afterwards. Steering the recogniser before it commits is far
more effective than repairing its output, and it costs nothing.

The last few seconds of transcript are passed the same way, so terminology stays
consistent across segment boundaries.

### Why it survives a dead connection

Audio is written to IndexedDB before anything is uploaded. Losing the network
mid-lecture delays the transcript; it never costs you the recording. The queue
restarts itself when connectivity returns, when the tab is reopened, and after a
crash. Failed segments keep their audio so you can retry them.

---

## Setup

### Quickest path — no backend, no account

```bash
npm install
npm run dev
```

Open the app, go to **Settings**, choose *Whisper via my own Groq key*, and paste
a key from [console.groq.com](https://console.groq.com) (free, no card). The key
is stored in your browser and sent straight to Groq.

Everything stays on the one device. No login, nothing to host.

### Full setup — accounts and sync across devices

1. Create a project at [supabase.com](https://supabase.com).
2. Run `supabase/schema.sql` in the SQL editor. It creates one table with
   row-level security scoped to the owner.
3. Deploy the two edge functions, which hold the API key server-side so it never
   reaches the browser:

   ```bash
   supabase secrets set GROQ_API_KEY=gsk_...
   supabase functions deploy transcribe
   supabase functions deploy structure
   ```

4. Copy `.env.example` to `.env` and fill in your project URL and anon key.
5. `npm run build` and deploy `dist/` anywhere static.

Sign-in is a one-time email link. There is no password.

---

## What is stored where

| | On your device | On the server |
|---|---|---|
| Audio | Until transcribed (or kept, if you ask) | Never persisted — segments pass through the function and are discarded |
| Transcript | Yes | Yes, if signed in |
| Notes | Yes | Yes, if signed in |
| Your own Groq key | Browser only | Never sent |

Without Supabase credentials the app has no server at all.

---

## Cost

Groq's free tier covers normal class use at no cost. If you exceed it,
`whisper-large-v3-turbo` runs at a few cents per hour of audio.

---

## Tuning

Settings → Segmenting:

- **Shortest segment** — lower it for a snappier live transcript, raise it for
  accuracy. Whisper does noticeably better with more context.
- **Longest segment** — a hard cap for lecturers who never pause.
- **Language** — naming it beats autodetect on short, noisy segments.

---

## Project layout

```
src/lib/audio/recorder.ts   pause-aware segmenting recorder
src/lib/audio/vad.ts        energy-based voice activity detection
src/lib/queue.ts            retrying, offline-tolerant upload queue
src/lib/transcribe/         Whisper adapters (shared key / your key)
src/lib/structure.ts        the cleanup + topic pass, and its guardrails
src/lib/db.ts               IndexedDB via Dexie — the source of truth
src/lib/sync.ts             one-way push to Supabase, text only
supabase/functions/         edge functions holding the API key
supabase/schema.sql         one table, RLS scoped to the owner
```

---

## Note on recording classes

Rules on recording lectures vary by institution and by country. Worth a look at
your school's policy before you come to depend on it.
