#!/usr/bin/env node
// Bakes scribe.html into scribe.mjs so the app ships as one runnable file.
// Run after editing scribe.html:  node build.mjs

import { readFile, writeFile, chmod } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const html = await readFile(join(HERE, 'scribe.html'), 'utf8')
const runner = await readFile(join(HERE, 'runner.mjs'), 'utf8')

// Base64 rather than a template literal: the page is full of backticks and
// ${...}, and escaping them by hand is a bug waiting to happen.
const out = runner.replace('__HTML_B64__', Buffer.from(html, 'utf8').toString('base64'))
await writeFile(join(HERE, 'scribe.mjs'), out)
await chmod(join(HERE, 'scribe.mjs'), 0o755)
console.log(`scribe.mjs built — ${(out.length / 1024).toFixed(1)} KB`)
