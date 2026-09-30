# Scribe

Record a class, watch the transcript appear as it happens, turn it into
structured notes.

Built for phones on bad wifi: it uploads about **8.6 MB per hour** (measured,
not estimated), keeps the CPU mostly idle, and never loses a recording when the
network drops.

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
