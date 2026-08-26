# Freedify Android

This module packages Freedify's existing FastAPI backend and web UI into one
Android application. Chaquopy embeds Python 3.13, the app starts Uvicorn on
`127.0.0.1:8000`, and an Android WebView loads that private local server. The
backend is never bound to the LAN.

## Install and use

1. Download `freedify-signed-apk` from a successful **Build Signed Android APK**
   GitHub Actions run, unzip the artifact, and install `app-release.apk`. Android
   may ask you to allow installs from the browser or file manager used.
2. On first run, enter an AllDebrid API key. Create or revoke keys in your
   AllDebrid account; no key is included in the repository or APK.
3. Open **Audiobooks**, choose AllDebrid, and search AudiobookBay or paste a
   direct AudiobookBay URL. Magnet upload, polling, chapter enumeration,
   playback-link refresh, cloud magnet search, and deletion use the embedded
   backend.
4. Replace the key later from the in-app **Settings** screen under
   **AllDebrid API key**. Updating it no longer reloads the player.

The credential is encrypted with an AES-GCM key held by Android Keystore. Only
the ciphertext and IV are kept in the app's private `SharedPreferences`; the
decrypted value is passed to the in-process Python backend and is not written to
the project or APK.

## Build locally

Prerequisites are JDK 17 and Android SDK Platform 35. From this directory:

```bash
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The build
currently packages `arm64-v8a` (physical modern Android devices) and `x86_64`
(emulators), so the debug APK is intentionally larger than a single-ABI APK.

To build the same signed release produced by CI, set all four
`FREEDIFY_SIGNING_*` environment variables used in `app/build.gradle.kts`, then
run `./gradlew :app:assembleRelease`. The output is
`app/build/outputs/apk/release/app-release.apk`. Release builds deliberately
fail when any signing value or the keystore file is missing.

## Persistent update signing

The downloadable APK is signed with one persistent project-owned key. Configure
these GitHub Actions repository secrets:

- `ANDROID_SIGNING_KEYSTORE_BASE64`: the complete keystore encoded as a
  single-line base64 value
- `ANDROID_SIGNING_KEY_ALIAS`: the signing-key alias
- `ANDROID_SIGNING_STORE_PASSWORD`: the keystore password
- `ANDROID_SIGNING_KEY_PASSWORD`: the signing-key password

For example, encode a keystore locally with `base64 < freedify-release.jks` on
macOS or `base64 -w 0 freedify-release.jks` on Linux, and paste the result into
the first secret. Never commit the keystore or any password. Keep an encrypted,
tested backup of the keystore and credentials: losing this signing identity
means future APKs cannot update existing installations.

Version `1.2.0` uses `versionCode` 4. Installations made from earlier workflow
debug artifacts have a different signature and therefore require a one-time
uninstall before installing this release. That uninstall removes the app's
stored settings, including the encrypted AllDebrid key. After installing this
signed APK once, later builds signed by the same persistent key can update it in
place as long as their `versionCode` increases.

## Android-specific behavior and limits

- The normal Python backend and static UI are copied into the APK at build time.
  App data and audiobook cache metadata are redirected to Android's private app
  storage.
- Selenium/ChromeDriver cannot run inside this packaging model. On Android,
  AudiobookBay search and detail loading use a direct HTTP/HTML fallback. If the
  site blocks that fallback, paste a direct AudiobookBay book URL; the error in
  the UI explains this fallback.
- Desktop-only optional packages (Selenium, webdriver-manager, Supabase,
  Gemini, Zeroconf, and an FFmpeg executable) are not bundled. The embedded app
  is focused on AllDebrid audiobook playback, whose returned audio files stream
  directly and do not require FFmpeg. Features which depend on those optional
  packages may be unavailable or use their existing non-AI fallback.
- A foreground media-playback service keeps the app process and localhost
  backend alive and makes WebView audio reasonably resilient when the app is in
  the background. A native Android media session mirrors title, artist,
  duration, position, playback speed, and play state, and routes notification,
  lock-screen, Bluetooth, and headset play/pause/seek/previous/next commands
  back to the web player. Android/OEM battery policies may still stop the
  process after the app is removed from recents or after prolonged background
  use.
- Android opens directly in audiobook mode. Search requests preserve the exact
  typed term, cancel stale in-flight searches, and filter unrelated fallback
  posts returned by AudiobookBay.
- AllDebrid exposes the torrent's file tree, so multi-file books become one
  chapter per audio file. Freedify keeps nested disc/folder names, orders
  numbered filenames naturally (`2` before `10`), and uses AudiobookBay's title,
  author, cover, and description for book-level metadata. For a single M4B,
  Freedify unlocks the media URL and uses HTTP byte ranges plus Mutagen to read
  its embedded MP4 chapter table without downloading the audio payload. Those
  entries become seek-bounded chapters over one continuous stream.
- Cleartext networking is disabled except for `127.0.0.1`/`localhost`; external
  AllDebrid and content requests use HTTPS.

The downloadable workflow artifact is a release APK. It is signed only from
GitHub Actions secrets; the private keystore and passwords are not stored in the
repository or APK source.
