# Fast Downloader (IDM-style, Android)

Segmented, multi-connection download manager written in Kotlin + Jetpack Compose.

## Features
- Splits each file into up to 16 byte-range segments, downloaded in parallel (IDM-style acceleration)
- Per-connection segment progress bar, live speed and ETA
- Pause / resume (survives app restarts, resumes each segment exactly where it stopped)
- Download queue (3 files at once), auto retry with backoff
- Foreground service + notifications, so downloads continue in the background
- Add by URL (auto-fills from clipboard), by **Share → Fast Downloader** from any browser/app,
  or "Open with" on direct file links (.zip, .apk, .mp4, .pdf, ...)
- Finished files are saved to `Downloads/FastDownloader` via MediaStore (no storage permission needed)
- Falls back to a single connection when the server does not support ranges

## Build
1. Open the `FastDownloader` folder in Android Studio (Ladybug or newer, JDK 17).
2. Let Gradle sync (it will download Gradle 8.9 and dependencies), then Run on a device (Android 10+).

Or from a terminal with Gradle installed: `gradle wrapper && ./gradlew assembleDebug`.

## Notes
- Android has no browser-extension hook like desktop IDM; the Share sheet and "Open with" are the capture routes.
- Foreground services of type dataSync are time-limited by Android 15 (about 6 h per day); fine for normal use.
- Only download content you have the right to download.
