# FastDownloader

Android download manager with IDM-style acceleration.

## How it's fast
- The file is split into up to 32 byte ranges; **8 parallel connections** pull ranges from a shared queue, so fast connections automatically take on more work.
- Resumes after pause, network loss or app kill (per-range progress is saved).
- Falls back to a single stream when the server doesn't support `Range`.
- Up to 3 files download at once; extra ones wait in the queue.
- Foreground service + wake lock keep downloads running with the screen off.

## Using it
- Tap **New download** and paste a link, or
- copy a link (a "Download copied link?" prompt appears when you return to the app), or
- use **Share → FastDownloader** from your browser.
Finished files are saved in `Downloads/FastDownloader`.

## Build the APK
Push this folder to the **root** of a GitHub repo. The **Build APK** workflow runs on push (or run it manually
from the Actions tab) and publishes the artifact **FastDownloader-apk**.

To build locally: `gradle assembleDebug` (Gradle 8.9, JDK 17, Android SDK 34).

## Tuning
`DownloadTask.MAX_THREADS` (default 8) and `Downloads.MAX_ACTIVE` (default 3). Some servers throttle or
block many connections per IP, so more threads isn't always faster.
