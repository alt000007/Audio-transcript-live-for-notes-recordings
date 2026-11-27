// Loads the page three ways and fails on any script error: as a plain web
// page, inside an app shell that only offers the original bridge methods, and
// inside the full app shell. Run from the repository root after building.
import { chromium } from 'playwright'
import { readFileSync } from 'node:fs'

const html = readFileSync(new URL('../../android/app/src/main/assets/index.html', import.meta.url), 'utf8')
const b = await chromium.launch(process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : {})
let failed = 0
for (const mode of ['browser', 'old shell', 'full shell']) {
  const ctx = await b.newContext()
  if (mode !== 'browser') {
    await ctx.addInitScript((full) => {
      window.ScribeNative = { isNative: () => true, isRecording: () => false, startRecording() {}, stopRecording() {} }
      if (full) Object.assign(window.ScribeNative, {
        getKey: () => '', setKey: () => true, queueSnapshot: () => JSON.stringify({ jobs: [], organise: { auto: false } }),
        namedJobs: () => '[]', diagnostics: () => '{"events":[]}', isBatteryUnrestricted: () => true,
      })
    }, mode === 'full shell')
  }
  const page = await ctx.newPage()
  const errs = []
  page.on('pageerror', (e) => errs.push(e.message))
  await page.route('https://appassets.androidplatform.net/**', (r) => r.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: html }))
  await page.goto('https://appassets.androidplatform.net/assets/index.html', { waitUntil: 'load' })
  await page.click('#tab-notes'); await page.click('#tab-set'); await page.click('#tab-rec')
  await page.waitForTimeout(400)
  const orgVisible = await page.evaluate(() => !document.getElementById('org-card').hidden)
  const ok = !errs.length && orgVisible === (mode !== 'browser')
  if (!ok) failed++
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${mode}: organise card ${orgVisible ? 'shown' : 'hidden'}${errs.length ? ' — ' + errs.join('; ') : ''}`)
  await ctx.close()
}
await b.close()
console.log(failed ? `${failed} FAILED` : 'ALL PASSED')
process.exit(failed ? 1 : 0)
