# Scribe — one file

```bash
node scribe.mjs
```

Your browser opens. Allow the microphone. Press record.

No account, no API key, no install, no dependencies. Node 18 or newer.

## Why a command and not a link

Browsers refuse microphone access outside a secure context, so the page has to
be served from `localhost` or HTTPS — double-clicking the HTML gives you a
`file://` origin and the mic will not work. `scribe.mjs` serves itself on
localhost and opens your browser, which is the shortest path that can legally
reach a microphone.

## Two engines

**Browser speech recognition** is the default. It costs nothing, needs no
account, and streams words as you speak. It only works in Chrome and Edge, it
needs a connection, and it has no notion of a glossary.

**Whisper on Groq** is the better engine. Add a free key from
[console.groq.com](https://console.groq.com) under Settings — no card — and the
app switches to `whisper-large-v3-turbo`. It is noticeably more accurate on
jargon and accents, your glossary is fed to it while it decodes, and it unlocks
the AI notes pass. Text arrives a few seconds behind the speaker instead of word
by word, because Whisper transcribes files rather than a stream.

Without a key the notes button copies the transcript plus instructions to your
clipboard, so you can paste it into any assistant and get the notes back.

Some Chromium builds expose the speech API but ship no speech service. The app
cannot detect that up front — it only fails once you record — so the error says
exactly that and points at the two fixes.

## Flags

```
node scribe.mjs --port 8080    listen elsewhere
node scribe.mjs --no-open      don't launch a browser
node scribe.mjs --eject        write scribe.html out and exit
```

## What it stores

Transcripts and notes live in that browser's `localStorage` (text only, most
recent 40). Audio is discarded as soon as its text comes back. A Groq key, if
you add one, stays in the browser and goes to Groq through the local proxy —
never to anyone else, never to disk.

## Editing it

`scribe.html` is the readable source; `scribe.mjs` is that file base64'd into a
server so it ships as one thing. After editing the HTML, run `node build.mjs`.

## What the full app adds

Accounts, cross-device sync, a real library, an offline queue that survives a
crash, and installability as a PWA. See the README one level up.
