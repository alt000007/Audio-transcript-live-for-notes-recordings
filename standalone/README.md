# Scribe — single file

The whole app in one HTML file. No build, no npm install, no account.

```bash
node serve.mjs
```

Open **http://localhost:8000**, allow the microphone, and add a free Groq key
from [console.groq.com](https://console.groq.com) under Settings.

That's it.

## Why not just open scribe.html?

Double-clicking the file gives you a `file://` origin, and browsers refuse
`getUserMedia` outside a secure context — the microphone will not work. The
page detects this and says so rather than failing silently.

`serve.mjs` has no dependencies (Node 18+). It does two things:

- serves the page on `localhost`, which browsers *do* treat as secure
- proxies calls to Groq, so the browser makes no cross-origin request and CORS
  cannot bite

Your API key goes from the proxy straight to Groq. It is never written to disk;
the browser keeps it in `localStorage` on your machine only.

## What it does

- Records and cuts audio at natural pauses (4s to your chosen cap)
- Transcribes each segment with `whisper-large-v3-turbo`
- Feeds your glossary and the preceding text to Whisper as a decoding hint
- Groups the result into topics with `llama-3.3-70b`, anchored to timestamps
- Exports Markdown with the notes *and* the untouched raw transcript

Transcripts are kept in this browser (text only, up to 40). Audio is discarded
as soon as its text comes back.

## What the full app adds

Accounts, cross-device sync, a proper library, offline queueing that survives a
crash, and installability as a PWA. See the README one level up.
