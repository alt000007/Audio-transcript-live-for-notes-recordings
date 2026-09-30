#!/usr/bin/env node
//
// Builds two things from scribe.html:
//
//   standalone/scribe.mjs   the page baked into a runnable local server
//   docs/index.html         the same page, plus PWA wiring, for GitHub Pages
//
// Run after editing scribe.html:  node build.mjs

import { readFile, writeFile, chmod } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const DOCS = resolve(HERE, '..', 'docs')

const html = await readFile(join(HERE, 'scribe.html'), 'utf8')
const runner = await readFile(join(HERE, 'runner.mjs'), 'utf8')

// ── 1. the single-file local runner ───────────────────────────────────────
// Base64 rather than a template literal: the page is full of backticks and
// ${...}, and escaping those by hand is a bug waiting to happen.
const mjs = runner.replace('__HTML_B64__', Buffer.from(html, 'utf8').toString('base64'))
await writeFile(join(HERE, 'scribe.mjs'), mjs)
await chmod(join(HERE, 'scribe.mjs'), 0o755)

// ── 2. the hosted, installable copy ───────────────────────────────────────
// Only this build gets a manifest and a service worker; the local runner
// serves a single route and would 404 on both.
const PWA_HEAD = `<link rel="manifest" href="./manifest.webmanifest">
<link rel="icon" href="./icon-192.png">
<link rel="apple-touch-icon" href="./icon-192.png">
<meta name="theme-color" content="#0b0f14">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-title" content="Scribe">`

const PWA_TAIL = `<script>
  // Registration is best-effort: the app works fine without it, and it is
  // unavailable in private windows and on insecure origins.
  if ('serviceWorker' in navigator) {
    addEventListener('load', () => navigator.serviceWorker.register('./sw.js').catch(() => {}));
  }
</script>
</body>`

let docs = html.replace('</head>', PWA_HEAD + '\n</head>')
docs = docs.replace('</body>', PWA_TAIL)
if (!docs.includes('manifest.webmanifest') || !docs.includes('serviceWorker')) {
  throw new Error('PWA injection failed — scribe.html no longer has the expected head/body tags.')
}
await writeFile(join(DOCS, 'index.html'), docs)

console.log(`standalone/scribe.mjs  ${(mjs.length / 1024).toFixed(1)} KB`)
console.log(`docs/index.html        ${(docs.length / 1024).toFixed(1)} KB`)
