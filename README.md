# ForgeCut Studio

ForgeCut Studio is a lightweight native Android video editor built with Jetpack Compose and AndroidX Media3. It uses Media3 Transformer and ExoPlayer for fast on-device previewing, trimming, composition, and hardware-accelerated video rendering.

## Requirements

* Android 12 or newer (API level 31+)
* Target SDK: Android 14 (API level 34)

## Architecture & Tech Stack

* **UI Layer:** Jetpack Compose, Material 3, Dark Theme
* **Playback Engine:** AndroidX Media3 ExoPlayer (`1.4.1`) with `PlayerView`
* **Export & Processing Engine:** AndroidX Media3 Transformer (`1.4.1`) with `Composition` and `EditedMediaItemSequence`
* **Media Storage:** Modern Android `MediaStore` API (`Movies/ForgeCut`)
* **Language & Tooling:** Kotlin 2.0.20, Android Gradle Plugin 8.5.2, Java 17

## Project Roadmap

* **Phase 1: Core Timeline & Export** *(Completed)*
  * Video import via system Photo Picker (`PickMultipleVisualMedia`)
  * Multi-clip sequencing, reordering, and removal
  * Millisecond-accurate clip trimming using Material 3 `RangeSlider`
  * Real-time preview player with synchronized clip playback
  * Hardware-accelerated MP4 (H.264 / AAC) export direct to device gallery
* **Phase 2: Timeline Tools & Enhanced Preview (v0.2.0)** *(Completed)*
  * Custom dark editing theme with responsive transport controls
  * Multi-step state history with undo support
  * Playhead split tool (`splitAt`) for dynamic clip cutting
  * Clip duplication, reordering, and deletion
  * Per-clip audio muting for preview and export
  * Real-time video thumbnail strip on timeline cards
  * Export modal with H.264 vs H.265 selection, live progress indicator, and cancellation
* **Phase 3: Color & VFX** *(Planned)*
  * Color grading and LUTs
  * Chroma key (green screen)
  * Shape and alpha masks
  * Proxy media generation for smooth editing of high-res clips
  * Built-in effects library
* **Phase 4: Advanced Production** *(Planned)*
  * Subtitles and captions
  * Project save, load, and serialization
  * Reusable video templates
  * Advanced export codecs and container formats

## Download & Installation

Every commit and push to the repository automatically triggers GitHub Actions to build a fresh debug APK.

1. Navigate to the [Releases](https://github.com/BorgorNinja/ForgeCut-Studio/releases) page.
2. Download `ForgeCut.apk` from the latest release.
3. Open the APK on your Android device to install (allow "Install unknown apps" for your browser if prompted).

## Building from Source

### Prerequisites

* Java Development Kit (JDK) 17
* Android SDK (API 34)

### Build Command

Clone the repository and run:

```bash
./gradlew assembleDebug
```

The compiled APK will be located at:
```
app/build/outputs/apk/debug/app-debug.apk
```

## License

Personal project by [BorgorNinja](https://github.com/BorgorNinja).
