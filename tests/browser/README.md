# Browser tests

They load the page the APK bundles and stand in for the Android side with a
fake `ScribeNative`, so the screens can be checked without a phone.

```
node standalone/build.mjs
npm i playwright
node tests/browser/smoke-test.mjs      # loads as web page, old app shell, full app shell
node tests/browser/organise-test.mjs   # folders, naming, renumbering, subjects, renaming
```

Set `CHROMIUM=/path/to/chrome` if Playwright's own browser is not installed.
