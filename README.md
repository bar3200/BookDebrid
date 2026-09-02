# BookDebrid

BookDebrid is a self-contained Android audiobook player powered by AllDebrid.
Install one APK, enter your own AllDebrid API key, find an audiobook, and listen
without running a separate server.

This project is a personal Android-focused fork of
[Freedify](https://github.com/BioHapHazard/Freedify). It retains Freedify's
Python/FastAPI audiobook integrations while replacing the primary mobile
experience with a native Jetpack Compose interface.

## What it does

- Searches AudiobookBay by title or author and accepts direct book URLs.
- Uses Open Library and Goodreads metadata for genre discovery, ratings, covers,
  descriptions, and related-book suggestions.
- Uploads magnets to AllDebrid, follows transfer progress, enumerates audio
  files, refreshes playable links, and removes cloud files.
- Reads embedded M4B chapter markers where the source file provides them.
- Remembers the current book, chapter, position, and playback speed.
- Integrates with Android media controls, Bluetooth, notifications, the lock
  screen, audio focus, and Android Auto browsing/playback.
- Keeps the Python backend inside the APK and binds it only to
  `127.0.0.1:8000`.

## Install

1. Open the latest successful
   [Build Signed Android APK](../../actions/workflows/android-signed-apk.yml)
   workflow run.
2. Download the versioned `BookDebrid-…-signed-apk` artifact and unzip it.
3. Install the enclosed versioned `BookDebrid` APK. Android may ask you to allow your
   browser or file manager to install unknown apps.
4. Launch BookDebrid and enter your AllDebrid API key on the first-run screen.

APK updates are signed with the same private release key and can install over
earlier persistently signed builds when the version code increases. Builds made
before persistent signing was introduced may require a one-time uninstall.

## Privacy and credentials

No AllDebrid API key is included in this repository or baked into the APK. The
app encrypts the entered key with Android Keystore and stores only encrypted
data in its private settings. UI QA exports intentionally omit API keys,
magnets, AllDebrid IDs, source links, and playable URLs.

The release keystore and passwords live only in GitHub Actions secrets. Losing
that signing key would prevent future versions from updating an existing
installation.

## Build locally

Requirements: JDK 17 and Android SDK Platform 35.

```bash
cd android
./gradlew :app:assembleDebug
```

The APK is generated at
`android/app/build/outputs/apk/debug/app-debug.apk`. The build includes
`arm64-v8a` for modern physical devices and `x86_64` for emulators.

Run the Android checks with:

```bash
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Backend tests use Python's unittest runner from the repository root.

## Android Auto

BookDebrid exposes a browsable **Continue listening → My Books → Chapters**
library through Android's media browser service. It supports play, pause, seek,
chapter changes, audio focus, Bluetooth controls, and resume state. Sideloaded
apps may require Android Auto developer mode and its **Unknown sources** option
before appearing in the car launcher. Do not operate the phone while driving.

## Limitations

- Search and downloads depend on AudiobookBay, AllDebrid, and public metadata
  services; upstream outages can temporarily affect results.
- Chapter names come from the downloaded audio file. BookDebrid preserves
  descriptive M4B titles when present, but does not invent names when a file
  contains only numbered markers.
- Strict OEM battery policies can stop long-running playback unless BookDebrid
  is allowed to run in the background.
- Music, podcasts, and other upstream Freedify features remain accessible from
  the legacy interface for migration, but the maintained Android experience is
  intentionally audiobook-first.

More implementation, signing, and troubleshooting details are in
[android/README.md](android/README.md).

## Upstream and license

BookDebrid is based on [Freedify](https://github.com/BioHapHazard/Freedify) and
retains its history and attribution. It is distributed under the repository's
[MIT License](LICENSE).
