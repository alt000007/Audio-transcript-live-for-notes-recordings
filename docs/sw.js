// Precaches the app shell so Scribe opens without a connection.
//
// The page itself is network-first: a cache-first document means a published
// fix never reaches anyone who already opened the app, which is exactly the
// trap v1 fell into. Static assets stay cache-first, and anything
// cross-origin is passed straight through so transcription never hits a cache.
const CACHE = 'scribe-v3'
const ASSETS = ['./', './index.html', './manifest.webmanifest', './icon.svg']

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(ASSETS)).then(() => self.skipWaiting()))
})

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  )
})

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url)
  if (e.request.method !== 'GET' || url.origin !== self.location.origin) return

  const isPage = e.request.mode === 'navigate' || e.request.destination === 'document'

  if (isPage) {
    e.respondWith(
      fetch(e.request)
        .then((res) => {
          const copy = res.clone()
          caches.open(CACHE).then((c) => c.put('./index.html', copy)).catch(() => {})
          return res
        })
        .catch(() => caches.match('./index.html').then((hit) => hit || caches.match('./'))),
    )
    return
  }

  e.respondWith(caches.match(e.request).then((hit) => hit || fetch(e.request)))
})
