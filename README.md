# Shelf — website → installable Android APK

This repository turns the single-file **Shelf** web app (`www/index.html`) into a real,
installable Android APK. Every push builds a signed APK with **GitHub Actions** and
publishes it as a downloadable Actions artifact and (on tags) a **GitHub Release**.

There is no Gradle, no AndroidX, no Cordova and no Node build step. The APK is assembled
with the Android SDK build-tools (`aapt2`, `d8`, `zipalign`, `apksigner`) by
[`scripts/build-apk.sh`](scripts/build-apk.sh) — the same script runs locally and in CI.

---

## What you get

| | |
|---|---|
| App name | `Shelf` |
| Package | `com.shelf.mobile` |
| Min / target SDK | 23 (Android 6) / 34 (Android 14) |
| Output | `dist/Shelf-<version>.apk` — signed with APK Signature Scheme v1 + v2 |
| CI | `.github/workflows/build-apk.yml` (~2–3 min per build) |
| Native features | fullscreen video, back-button handling, share-to-app (`ACTION_SEND`), external links in the browser, adaptive launcher icon, dark theme |

---

## Repository layout

```
.github/workflows/build-apk.yml   GitHub Actions: build → artifact → release
www/
  index.html                     the web app (single source of truth — edit this)
  manifest.json                  PWA manifest
  sw.js                          offline shell cache for the web/PWA version
  icon-*.png                     PWA icons (generated)
app/
  AndroidManifest.xml            permissions, activity, share intent-filter
  java/com/shelf/mobile/MainActivity.java   WebView shell + asset loader
  res/values/*                   app name, dark theme, colors
  res/mipmap-*/                  launcher icons (generated)
scripts/
  build-apk.sh                   the whole APK build pipeline
  make-icons.py                  regenerate all PNG icons (needs Pillow)
  generate-signing-key.sh        create a persistent signing key
signing/                         gitignored — local key only
```

---

## 1. Build an APK on your machine

**Prerequisites:** JDK 11+, `zip`, `openssl`, and the Android command-line tools.

```bash
# Install Android command-line tools from:
# https://developer.android.com/studio#command-line-tools-only
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
yes | sdkmanager --licenses
sdkmanager --install "platforms;android-35" "build-tools;35.0.0"

bash scripts/generate-signing-key.sh   # once, creates signing/key.pk8 + cert.x509.pem
bash scripts/build-apk.sh
ls -lh dist/
```

Install it on a connected device/emulator:

```bash
adb install -r dist/Shelf-1.0.0.apk
```

Or copy the APK to the phone and open it (enable *Install unknown apps* for your file
manager / browser when prompted).

Useful environment overrides: `VERSION_NAME`, `VERSION_CODE`, `APP_NAME`,
`MIN_SDK`, `TARGET_SDK`, `BUILD_TOOLS_VERSION`, `APK_BASENAME`.

---

## 2. Put it on GitHub and let Actions build it

> **Note:** GitHub isn't connected to this workspace, so I can't create the remote
> repo or push for you. Push these files yourself (or hand me a scoped token):

```bash
cd shelf-android
git init
git add .
git commit -m "Shelf Android app + APK build workflow"
git branch -M main
git remote add origin https://github.com/<you>/shelf-android.git
git push -u origin main
```

The workflow then runs automatically on every push/PR to `main`.

### Add a persistent signing key (strongly recommended)

Without it, each CI run generates a **new ephemeral key**, so a newer APK cannot be
installed over an older one ("App not installed / signatures do not match").
Create the key once and store it in repository secrets:

```bash
bash scripts/generate-signing-key.sh

base64 -w0 signing/key.pk8        # Linux  → SHELF_SIGNING_KEY_B64
base64 -w0 signing/cert.x509.pem  # Linux  → SHELF_SIGNING_CERT_B64
# macOS: use `base64 -i file | tr -d '\n'`
```

In GitHub: **Settings → Secrets and variables → Actions → New repository secret**,
add `SHELF_SIGNING_KEY_B64` and `SHELF_SIGNING_CERT_B64`.

Never commit `signing/` — it is already in `.gitignore`.

### Download the installable APK

* **Every build:** *Actions → Build Android APK → (run) → Artifacts → `Shelf-APK-*`* (a zip containing the `.apk`).
* **Public download link:** push a version tag, or use *Run workflow → ☑ Also publish a GitHub Release*:

  ```bash
  git tag v1.0.0 && git push origin v1.0.0
  ```

  This creates a Release with `Shelf-1.0.0.apk` attached — share that URL with anyone
  so they can download and install the APK directly on their phone. `VERSION_CODE` is
  taken from `github.run_number`, so updates install cleanly over older builds.

---

## 3. Updating the website

Edit `www/index.html` (or replace it with your own single-file app), commit and push.
The next Actions run bundles the new HTML automatically — no native rebuild by hand.

To change the app name, package, icons, or SDK levels:

| What | Where |
|---|---|
| Display name | `app/res/values/strings.xml` + `APP_NAME` in the workflow |
| Package id | `package=` in `app/AndroidManifest.xml`, `package` + folder in `app/java/` |
| Origin/host | `BASE_HOST` in `MainActivity.java` (used only as a synthetic origin) |
| Icons | run `python3 scripts/make-icons.py` after editing that script |
| min/target SDK | `app/AndroidManifest.xml` **and** `MIN_SDK`/`TARGET_SDK` in `scripts/build-apk.sh` |

---

## How the wrapper works

`MainActivity` serves `assets/www/*` from a **synthetic HTTPS origin**
(`https://shelf.appassets.local/`) using `WebViewClient.shouldInterceptRequest`.
No request for that host ever reaches the network — it is answered straight from the
APK. This avoids all the usual `file://` problems:

* `localStorage` has a stable, secure origin, so saved channels/history persist.
* Cross-origin `fetch()` to the API sends a normal `Origin` header (the API replies
  with `access-control-allow-origin: *`).
* YouTube iframe embeds get a valid HTTPS `Referer`, plus a plain Chrome user agent
  (no `; wv` marker), which is required for the iframe player to work.

Other details: `/sw.js` deliberately returns 404 inside the WebView (a service worker
would bypass the asset interceptor), `target="_blank"`/external top-level links open
in the user's browser, `onShowCustomView` handles fullscreen video, and an
`ACTION_SEND` intent-filter lets you share a YouTube link to Shelf, mapping it to the
app's existing `?url=` launch handler.

**Network is still required at runtime.** Only the app shell is bundled offline. The
web app inside `www/index.html` fetches its data from
`https://royal-river-0d53.tempmail99666.workers.dev` (a third-party Cloudflare Worker)
and loads thumbnails from `i.ytimg.com`, fonts from Google Fonts, and the player from
`youtube.com`. If that Worker is down or blocked, lists will appear empty regardless of
the APK — that is a property of the original website, not the Android wrapper. Change
`const API = ...` in `www/index.html` to point at your own backend if needed.

---

## Troubleshooting

| Problem | Fix |
|---|---|
| `ERROR: Android SDK not found` | Set `ANDROID_SDK_ROOT`; see step 1. |
| `App not installed` on update | Add the two signing secrets above, then rebuild both APKs with the same key (or uninstall the old app). |
| Black video player / YouTube "Error 153" | YouTube sometimes rejects embedded players with unusual referrers. Confirm the device has working internet, try the in-app **YouTube** link (opens the site/app), or change `USER_AGENT` in `MainActivity.java`. |
| Blank page | Check `www/index.html` exists and is valid; `unzip -l dist/*.apk \| grep assets/www` should list it. |
| Fonts/images missing | They are remote resources; the device needs internet. The app falls back to system fonts. |
| CI: `sdkmanager` license errors | The workflow runs `yes \| sdkmanager --licenses` — re-run the job if Google's download was flaky. |
| Want a Play Store build | Switch `apksigner` to an upload key and build an AAB (`aapt2` → `bundletool`); this repo targets sideload APKs. |
