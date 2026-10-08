# ForgeCut Studio
Android video editor (Android 12+). Trim, split, reorder, titles, transitions, MP4 export.

## Install on your phone (no Android Studio)
1. Push to `main`. The **Build & Release APK** action produces `ForgeCut.apk` on the Releases page.
2. Open Releases on your phone, download `ForgeCut.apk`, tap to install.

## Titles + transitions need the FFmpeg engine (one-time)
1. GitHub → **Actions** → **Build FFmpeg (one-time)** → **Run workflow** (~15-25 min).
2. When it finishes, re-run **Build & Release APK** (or push a commit). It downloads the engine and bundles it.
3. Reinstall the APK. Titles and transitions now export.

Without the engine the app still trims, splits, reorders and exports (Android hardware encoder).
Note: the FFmpeg engine includes libx264, which is GPL-licensed.
