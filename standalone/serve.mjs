#!/usr/bin/env node
//
// Zero-dependency launcher for the single-file version of Scribe.
//
//   node standalone/serve.mjs
//   → http://localhost:8000
//
// It does two jobs:
//
//  1. Serves the page over http://localhost, which browsers treat as a secure
//     context. Opening the .html file directly with file:// will NOT work —
//     getUserMedia is refused on non-secure origins.
//
//  2. Proxies calls to Groq, so the browser never makes a cross-origin request
//     and CORS cannot bite. Your API key goes from this process straight to
//     Groq and is never written to disk.

import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const HERE = dirname(fileURLToPath(import.meta.url))
const PORT = Number(process.env.PORT ?? 8000)
const GROQ = 'https://api.groq.com/openai/v1'

function send(res, status, body, type = 'text/plain; charset=utf-8') {
  res.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store' })
  res.end(body)
}

/** Streams the request body into a Buffer, with a ceiling so a bad client
 *  cannot exhaust memory. Segments are seconds long; 12 MB is generous. */
function readBody(req, limit = 12 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = []
    let size = 0
    req.on('data', (c) => {
      size += c.length
      if (size > limit) {
        reject(new Error('Request body too large'))
        req.destroy()
        return
      }
      chunks.push(c)
    })
    req.on('end', () => resolve(Buffer.concat(chunks)))
    req.on('error', reject)
  })
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`)

  // --- Groq proxy -----------------------------------------------------------
  if (url.pathname.startsWith('/api/groq/')) {
    if (req.method !== 'POST') return send(res, 405, 'Method not allowed')

    const key = req.headers['x-groq-key']
    if (!key || typeof key !== 'string') {
      return send(res, 401, JSON.stringify({ error: { message: 'No API key. Add one in Settings.' } }), 'application/json')
    }

    // Only the two endpoints this app uses, so the proxy is not an open relay.
    const path = url.pathname.slice('/api/groq/'.length)
    if (path !== 'audio/transcriptions' && path !== 'chat/completions') {
      return send(res, 404, 'Unknown endpoint')
    }

    let body
    try {
      body = await readBody(req)
    } catch (e) {
      return send(res, 413, e.message)
    }

    try {
      const upstream = await fetch(`${GROQ}/${path}`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${key}`,
          ...(req.headers['content-type'] ? { 'Content-Type': req.headers['content-type'] } : {}),
        },
        body,
      })
      const text = await upstream.text()
      return send(res, upstream.status, text, upstream.headers.get('content-type') ?? 'application/json')
    } catch (e) {
      // Reaching Groq failed entirely — no network, DNS, or a blocked egress.
      return send(
        res,
        502,
        JSON.stringify({ error: { message: `Could not reach Groq: ${e.message}` } }),
        'application/json',
      )
    }
  }

  // --- Static ---------------------------------------------------------------
  if (url.pathname === '/' || url.pathname === '/index.html' || url.pathname === '/scribe.html') {
    try {
      const html = await readFile(join(HERE, 'scribe.html'))
      return send(res, 200, html, 'text/html; charset=utf-8')
    } catch {
      return send(res, 500, 'scribe.html is missing — keep it next to serve.mjs.')
    }
  }

  send(res, 404, 'Not found')
})

server.listen(PORT, () => {
  console.log(`\n  Scribe is running.\n`)
  console.log(`    →  http://localhost:${PORT}\n`)
  console.log(`  Open that in Chrome, Edge or Safari and allow the microphone.`)
  console.log(`  Add a free Groq key from https://console.groq.com in Settings.`)
  console.log(`  Ctrl-C to stop.\n`)
})
