# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Space 101 is a native Android streaming radio app for Space 101.1 FM (KMGP · Seattle). It streams audio from `http://kmgp.broadcasttool.stream/xstream.mp3` and parses ICY metadata for live track info.

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Clean
./gradlew clean

# Install on connected device/emulator
adb install app/build/outputs/apk/debug/app-debug.apk
```

No test suite is currently configured.

## Architecture

**Two-file app** — all logic lives in `app/src/main/java/com/space101/app/`:

- **`MainActivity.kt`** — Single activity. Binds to `RadioService` via `ServiceConnection`. Handles play/pause button, displays streaming metadata (artist/title), and manages Android 13+ notification permission request.

- **`RadioService.kt`** — Foreground service using **Media3 ExoPlayer** for HLS/MP3 streaming. Parses ICY metadata from the stream response headers to extract artist/title. Exposes a callback interface (`RadioServiceCallback`) that `MainActivity` implements for UI state updates (playing, paused, buffering, metadata changes). Runs a persistent foreground notification with playback controls.

The service/activity communication uses a direct binder pattern (`LocalBinder`), not broadcasts or `LiveData`.

## Tech Stack

- **Language**: Kotlin (JVM target 1.8)
- **Min SDK**: 24 (Android 7.0) / Target SDK: 36 (Android 15)
- **Build**: Gradle 8.13.2 with Kotlin DSL; dependency versions centralized in `gradle/libs.versions.toml`
- **Key library**: Media3 ExoPlayer 1.3.1 (`media3-exoplayer`, `media3-session`, `media3-ui`)
- **Layout**: ConstraintLayout with ViewBinding enabled; portrait-only orientation
- **HTTP**: Cleartext traffic allowed in manifest (required for the unencrypted stream URL)

## UI Design Notes

Dark space theme (`#0A0A1A` background, blue/purple accents `#8888FF`/`#6666AA`). The play button has a pulse-ring animation defined in `res/drawable/`. Track info uses a marquee `TextView` for long strings.
