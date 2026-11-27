// Drives the app's page with a fake Android side: the queue, the phone's
// recorder folders, naming and renaming. Run from the repository root after
// `node standalone/build.mjs`:
//
//   npm i playwright && node tests/browser/organise-test.mjs
//
// Set CHROMIUM to a Chromium binary if Playwright's own is not installed.
import { chromium } from 'playwright'
import { readFileSync } from 'node:fs'

const html = readFileSync(new URL('../../android/app/src/main/assets/index.html', import.meta.url), 'utf8')
const b = await chromium.launch(process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : {})
const ctx = await b.newContext({ viewport: { width: 393, height: 830 }, isMobile: true, hasTouch: true })
const errs = []
const calls = []
await ctx.exposeFunction('__log', (c) => calls.push(c))
await ctx.addInitScript(() => {
  const H = 3600000
  const S = {
    key: 'gsk_x', hold: false, namesVersion: 0,
    organise: { auto: false, folders: [], subjects: [], format: '{subject} {n} - {theme}', renameFiles: true },
    jobs: [],
  }
  window.__fake = S
  const FOLDERS = [
    { path: 'MIUI/sound_recorder/', count: 6, totalMs: 9 * H, newCount: 6, newMs: 9 * H, recorder: true, examples: ['Registrazione 2026-10-01 09-00-00.m4a'] },
    { path: 'WhatsApp/Media/WhatsApp Voice Notes/', count: 40, totalMs: H / 2, newCount: 40, newMs: H / 2, recorder: false, examples: ['PTT-1.opus'] },
  ]
  // Six lectures, oldest first, as the app queues them.
  const LECTURES = [
    ['Registrazione 2026-10-01 09-00-00.m4a', 'Storia', 'La rivoluzione francese'],
    ['Registrazione 2026-10-01 11-00-00.m4a', 'Filosofia', 'Kant e la ragion pura'],
    ['Registrazione 2026-10-02 09-00-00.m4a', 'Storia', 'Napoleone'],
    ['Registrazione 2026-10-03 09-00-00.m4a', 'Storia', 'Il Congresso di Vienna'],
    ['Registrazione 2026-10-03 11-00-00.m4a', 'Filosofia', 'Hegel'],
    ['Registrazione 2026-09-30 09-00-00.m4a', 'Storia', "L'Illuminismo"],   // older, found later
  ]
  const snap = () => {
    const all = S.jobs
    const visible = all.filter((j) => !j.cleared)
    let p = 0
    visible.forEach((j) => { j.position = j.status === 'waiting' ? ++p : 0 })
    return JSON.stringify({
      jobs: visible, hiddenWaiting: 0, running: true, waitUntil: 0, blocked: null,
      settings: { engine: 'auto', filesAtOnce: 2, uploadsAtOnce: 3, trimSilence: true, notifyEach: false, notifyBatch: true },
      local: { supported: true, ready: false, model: 'small' },
      organise: {
        ...S.organise, allFilesAccess: false, canAsk: true,
        named: all.filter((j) => j.naming === 'done').length,
        namingPending: all.filter((j) => j.naming === 'pending').length, namingFailed: 0,
        renamePending: all.filter((j) => j.renamePending).length,
        restorable: all.filter((j) => j.appliedName).length,
        libraryQueued: all.filter((j) => j.status !== 'done').length,
        namesVersion: S.namesVersion, blocked: null,
      },
    })
  }
  const push = () => window.__scribeQueue && window.__scribeQueue(JSON.parse(snap()))
  const renumber = () => {
    const bySubject = {}
    S.jobs.filter((j) => j.naming === 'done').forEach((j) => (bySubject[j.subject] ||= []).push(j))
    for (const list of Object.values(bySubject)) {
      list.sort((a, b) => a.recordedAt - b.recordedAt)
      list.forEach((j, i) => {
        const t = `${j.subject} ${String(i + 1).padStart(2, '0')} - ${j.theme}`
        if (t !== j.title) { j.title = t; j.number = i + 1; if (S.organise.renameFiles) j.renamePending = true }
      })
    }
    S.namesVersion++
  }
  const queueLibrary = () => {
    if (!S.organise.auto || S.jobs.length) return
    LECTURES.forEach(([name, subject, theme], i) => {
      const m = /(\d{4})-(\d\d)-(\d\d) (\d\d)/.exec(name)
      S.jobs.push({ id: 'lib' + i, name, status: 'waiting', note: '', createdAt: Date.now(), recordedAt: Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4]),
        chunksDone: 0, chunksTotal: 3, prepared: true, durationMs: 5400000, processingMs: 0, imported: false, cleared: false,
        naming: '', title: '', subject: '', theme: '', number: 0, renamePending: false, appliedName: '', linked: true,
        _subject: subject, _theme: theme })
    })
  }
  let tick = 0
  setInterval(() => {
    if (S.hold || !S.jobs.length) return
    tick++
    const open = S.jobs.filter((j) => j.status === 'waiting' || j.status === 'processing').slice(0, 2)
    for (const j of open) {
      j.status = 'processing'
      if (tick % 2 === 0) j.chunksDone++
      j.processingMs += 200
      if (j.chunksDone >= 3) { j.status = 'done'; j.naming = 'pending' }
    }
    // Name one finished file per tick, as the app does, one at a time.
    const toName = S.jobs.find((j) => j.status === 'done' && j.naming === 'pending')
    if (toName) { toName.subject = toName._subject; toName.theme = toName._theme; toName.naming = 'done'; renumber() }
    push()
  }, 120)

  window.ScribeNative = {
    isNative: () => true, isRecording: () => false, startRecording() {}, stopRecording() {}, pickFile() {},
    diagnostics: () => JSON.stringify({ events: [] }),
    getKey: () => S.key, setKey: (k) => { S.key = k; return true },
    queueSnapshot: snap,
    jobSegments: (id) => JSON.stringify([{ s: 0, e: 5000, t: 'Testo di ' + id }]),
    markImported: (id) => { const j = S.jobs.find((x) => x.id === id); j.imported = true; j.cleared = true },
    setQueueSettings: () => {}, isBatteryUnrestricted: () => true,
    libraryFolders: () => { window.__log('folders'); setTimeout(() => window.__scribeLibraryFolders(FOLDERS), 50) },
    setOrganise: (json) => { window.__log('organise:' + json); S.organise = { ...S.organise, ...JSON.parse(json) }; queueLibrary(); push() },
    namedJobs: () => JSON.stringify(S.jobs.filter((j) => j.naming === 'done').map((j) => ({ id: j.id, title: j.title, subject: j.subject, number: j.number, theme: j.theme, recordedAt: j.recordedAt }))),
    renameOnPhone: (restore) => {
      window.__log('rename:' + restore)
      const targets = S.jobs.filter((j) => (restore ? j.appliedName : j.renamePending))
      targets.forEach((j) => { if (restore) j.appliedName = ''; else { j.appliedName = j.title; j.renamePending = false } })
      setTimeout(() => { window.__scribeRenamed(targets.length, targets.length, restore, false); push() }, 50)
    },
    requestAllFilesAccess: () => window.__log('allfiles'),
    retryNaming: () => {}, removeJob: (id) => window.__log('remove:' + id),
  }
})
const page = await ctx.newPage()
page.on('pageerror', (e) => errs.push('pageerror: ' + e.message))
page.on('console', (m) => { if (m.type() === 'error' && !/favicon/.test(m.text())) errs.push('console: ' + m.text()) })
await page.route('https://appassets.androidplatform.net/**', (r) => r.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: html }))
await page.goto('https://appassets.androidplatform.net/assets/index.html', { waitUntil: 'domcontentloaded' })
const assert = (c, m) => { if (!c) errs.push('ASSERT: ' + m) }

// 1. Settings: folders, the recorder's pre-ticked, estimate, preview
await page.tap('#tab-set')
assert(!(await page.locator('#org-card').isHidden()), 'organise card hidden in the app')
await page.tap('#org-load')
await page.waitForSelector('#org-folders [data-folder]')
const ticked = await page.locator('#org-folders [data-folder]:checked').evaluateAll((els) => els.map((e) => e.dataset.folder))
const estimate = await page.textContent('#org-estimate')
console.log('1. folders ticked:', ticked, '\n   estimate:', estimate)
assert(ticked.length === 1 && ticked[0] === 'MIUI/sound_recorder/', 'the recorder folder should be pre-ticked, voice notes not')
assert(/6 recordings \(9\.0 h\).*about 2 days/.test(estimate), 'estimate wrong')
await page.fill('#org-format', '{subject}({n})({theme})')
const preview = await page.textContent('#org-preview')
assert(preview.includes('Storia(03)(La rivoluzione francese)'), 'format preview wrong: ' + preview)
await page.fill('#org-format', '{subject} {n} - {theme}')
await page.fill('#org-subjects', 'Storia, Filosofia')

// 2. Turn it on: confirm with the size of the job
page.once('dialog', (d) => { console.log('2. confirm:', d.message()); d.accept() })
await page.locator('#org-auto').check()
const sent = JSON.parse(calls.filter((c) => c.startsWith('organise:')).pop().slice(9))
console.log('   sent:', JSON.stringify(sent))
assert(sent.auto && sent.folders[0] === 'MIUI/sound_recorder/' && sent.subjects.join() === 'Storia,Filosofia' && sent.renameFiles, 'settings not sent')

// 3. Everything transcribes and is named; renumbered when an older one lands
await page.waitForFunction(() => window.__fake.jobs.length && window.__fake.jobs.every((j) => j.naming === 'done'), null, { timeout: 20000 })
await page.waitForFunction(() => document.querySelectorAll('#saved [data-open]').length === 6, null, { timeout: 5000 })
await page.waitForTimeout(500)
await page.tap('#tab-notes')
const titles = await page.locator('#saved .t').allTextContents()
console.log('3. Your recordings:', titles)
for (const t of ['Storia 01 - L\'Illuminismo', 'Storia 02 - La rivoluzione francese', 'Storia 03 - Napoleone', 'Storia 04 - Il Congresso di Vienna', 'Filosofia 01 - Kant e la ragion pura', 'Filosofia 02 - Hegel']) {
  assert(titles.includes(t), 'missing title ' + t)
}
const stored = await page.evaluate(async () => (await STORE.all()).map((r) => `${r.subject}|${r.number}|${r.title}`).sort())
assert(stored.length === 6 && stored.every((x) => !x.startsWith('|')), 'names not saved to storage: ' + stored)

// 4. Subject chips: Storia in order
await page.locator('#saved-subjects [data-subject="Storia"]').tap()
const storia = await page.locator('#saved .t').allTextContents()
console.log('4. Storia only:', storia)
assert(storia.length === 4 && storia[0].startsWith('Storia 01') && storia[3].startsWith('Storia 04'), 'subject filter or order wrong')
await page.locator('#saved-subjects [data-subject=""]').tap()

// 5. Renaming on the phone: banner, one tap, then gone; restore
await page.waitForSelector('#rename-banner:not([hidden])')
const banner = (await page.textContent('#rename-banner')).replace(/\s+/g, ' ')
console.log('5. banner:', banner)
await page.tap('#rename-now')
await page.waitForFunction(() => document.getElementById('rename-banner').hidden, null, { timeout: 3000 })
assert(calls.includes('rename:false'), 'rename not requested')
await page.tap('#tab-set')
await page.waitForSelector('#org-restore:not([hidden])')
page.once('dialog', (d) => d.accept())
await page.tap('#org-restore')
await page.waitForTimeout(200)
assert(calls.includes('rename:true'), 'restore not requested')
console.log('   status:', await page.textContent('#org-status'))

// 6. Reload: titles come back from storage, nothing re-imported wrongly
await page.reload({ waitUntil: 'domcontentloaded' })
await page.waitForTimeout(800)
const afterReload = await page.evaluate(async () => (await STORE.all()).map((r) => r.title).sort())
console.log('6. after reload:', afterReload.length, 'rows')
assert(afterReload.length === 6 && afterReload.includes('Storia 01 - L\'Illuminismo'), 'titles lost on reload')

console.log(errs.length ? '\nERRORS:\n  ✗ ' + errs.join('\n  ✗ ') : '\nALL PASSED')
await b.close()
process.exit(errs.length ? 1 : 0)
