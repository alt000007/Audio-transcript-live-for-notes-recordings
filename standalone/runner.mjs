#!/usr/bin/env node
/**
 * Scribe — record a class, get a live transcript, turn it into notes.
 *
 *   node scribe.mjs
 *
 * That's the whole setup. It starts a local server, opens your browser, and
 * transcribes using the browser's own speech recognition — no account, no API
 * key, nothing to install. Node 18 or newer; no dependencies.
 *
 * Optional: add a free Groq key in Settings for Whisper-quality transcription
 * and AI-generated notes. This file proxies those calls so the browser never
 * makes a cross-origin request.
 *
 * Flags:
 *   --port 8000     listen somewhere else
 *   --no-open       don't launch a browser
 *   --eject         write scribe.html next to this file and exit
 */

import { createServer } from 'node:http'
import { writeFile } from 'node:fs/promises'
import { spawn } from 'node:child_process'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HTML = Buffer.from('__HTML_B64__', 'base64').toString('utf8')
const GROQ = 'https://api.groq.com/openai/v1'

const argv = process.argv.slice(2)
const flag = (name) => argv.includes(name)
const value = (name, fallback) => {
  const i = argv.indexOf(name)
  return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback
}

if (flag('--eject')) {
  const out = join(dirname(fileURLToPath(import.meta.url)), 'scribe.html')
  await writeFile(out, HTML)
  console.log(`Wrote ${out}`)
  process.exit(0)
}

const PORT = Number(value('--port', process.env.PORT ?? 8000))

function send(res, status, body, type = 'text/plain; charset=utf-8') {
  res.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store' })
  res.end(body)
}

/** Buffers the request body with a ceiling, so a runaway client cannot
 *  exhaust memory. Segments are seconds long; 12 MB is generous. */
function readBody(req, limit = 12 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = []
    let size = 0
    req.on('data', (c) => {
      size += c.length
      if (size > limit) { reject(new Error('Request body too large')); req.destroy(); return }
      chunks.push(c)
    })
    req.on('end', () => resolve(Buffer.concat(chunks)))
    req.on('error', reject)
  })
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`)

  if (url.pathname.startsWith('/api/groq/')) {
    if (req.method !== 'POST') return send(res, 405, 'Method not allowed')

    const key = req.headers['x-groq-key']
    if (!key || typeof key !== 'string') {
      return send(res, 401, JSON.stringify({ error: { message: 'No API key. Add one in Settings.' } }), 'application/json')
    }

    // Only the two endpoints the app uses, so this is not an open relay.
    const path = url.pathname.slice('/api/groq/'.length)
    if (path !== 'audio/transcriptions' && path !== 'chat/completions') return send(res, 404, 'Unknown endpoint')

    let body
    try { body = await readBody(req) } catch (e) { return send(res, 413, e.message) }

    try {
      const upstream = await fetch(`${GROQ}/${path}`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${key}`,
          ...(req.headers['content-type'] ? { 'Content-Type': req.headers['content-type'] } : {}),
        },
        body,
      })
      return send(res, upstream.status, await upstream.text(), upstream.headers.get('content-type') ?? 'application/json')
    } catch (e) {
      return send(res, 502, JSON.stringify({ error: { message: `Could not reach Groq: ${e.message}` } }), 'application/json')
    }
  }

  if (url.pathname === '/' || url.pathname === '/index.html') {
    return send(res, 200, HTML, 'text/html; charset=utf-8')
  }
  if (url.pathname === '/favicon.ico') {
    res.writeHead(204).end()
    return
  }
  send(res, 404, 'Not found')
})

server.on('error', (e) => {
  if (e.code === 'EADDRINUSE') {
    console.error(`\n  Port ${PORT} is already in use.`)
    console.error(`  Try:  node scribe.mjs --port ${PORT + 1}\n`)
    process.exit(1)
  }
  throw e
})

function openBrowser(target) {
  const cmd = process.platform === 'darwin' ? 'open'
    : process.platform === 'win32' ? 'cmd'
    : 'xdg-open'
  const args = process.platform === 'win32' ? ['/c', 'start', '', target] : [target]
  try {
    spawn(cmd, args, { stdio: 'ignore', detached: true }).unref()
  } catch {
    // No browser launcher on this machine — the printed URL still works.
  }
}

server.listen(PORT, () => {
  const target = `http://localhost:${PORT}`
  console.log(`\n  Scribe\n`)
  console.log(`    ${target}\n`)
  console.log(`  Allow the microphone when asked, then press record.`)
  console.log(`  No account or API key needed.\n`)
  console.log(`  Ctrl-C to stop.\n`)
  if (!flag('--no-open')) openBrowser(target)
})
