# Freedify Android

This module packages Freedify's existing FastAPI backend and web UI into one
Android application. Chaquopy embeds Python 3.13, the app starts Uvicorn on
`127.0.0.1:8000`, and an Android WebView loads that private local server. The
backend is never bound to the LAN.

## Install and use

1. Download `freedify-debug-apk` from a successful **Build Android Debug APK**
   GitHub Actions run, unzip the artifact, and install `app-debug.apk`. Android
   may ask you to allow installs from the browser or file manager used.
2. On first run, enter an AllDebrid API key. Create or revoke keys in your
   AllDebrid account; no key is included in the repository or APK.
3. Open **Audiobooks**, choose AllDebrid, and search AudiobookBay or paste a
   direct AudiobookBay URL. Magnet upload, polling, chapter enumeration,
   playback-link refresh, cloud magnet search, and deletion use the embedded
   backend.
4. Replace the key later from the app bar menu under **AllDebrid API key**.

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
  the background. Playback is still WebView-based: this version does not expose
  native lock-screen media controls, and Android/OEM battery policies may stop
  it after the app is removed from recents or after prolonged background use.
- Cleartext networking is disabled except for `127.0.0.1`/`localhost`; external
  AllDebrid and content requests use HTTPS.

This is a debug build and is signed with the standard per-builder Android debug
key. A distributable release should add a project-owned signing configuration
without committing its keystore or passwords.
