# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Space 101 is a native Android streaming radio app for Space 101.1 FM (KMGP · Seattle). It streams audio from `http://kmgp.broadcasttool.stream/xstream.mp3`. This is an unofficial fan-made app, not yet endorsed by the station.

## Build & Install

```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device (-r = reinstall)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Target emulator or physical device explicitly when both are connected
adb -e install -r ...   # emulator
adb -d install -r ...   # physical device

# View app logs
adb -e logcat -s RadioService
```

`adb` is at `~/Android/Sdk/platform-tools/adb` — add to PATH if needed.

No test suite is currently configured.

## Architecture

**Two-file app** — all logic lives in `app/src/main/java/com/space101/app/`:

- **`MainActivity.kt`** — Single activity. Binds to `RadioService` via `ServiceConnection` and a direct binder pattern (`RadioBinder`). Handles play/pause, displays track metadata, manages Android 13+ notification permission. Callbacks (`playerStateCallback`, `metadataCallback`) are set on bind and cleared on `onStop`.

- **`RadioService.kt`** — Foreground service using **Media3 ExoPlayer**. Uses `ProgressiveMediaSource` with `DefaultHttpDataSource` (sends `Icy-MetaData: 1` header). Audio focus is handled automatically by ExoPlayer (`setAudioAttributes(..., handleAudioFocus = true)`), which pauses playback when other apps request focus (speech-to-text, calls, etc.). ICY `onMetadata` callback fires but currently returns empty titles — see Metadata section below.

## Track Metadata (Known Issue)

The ICY stream does **not** include track info — the server responds with no `icy-metaint` header, and `onMetadata` fires with `StreamTitle=''`. Track data is sourced from **Spinitron** (the station uses Spinitron for playlist logging). The Spinitron API requires a station-issued API key. Pending permission from the station — see project memory for details.

## Tech Stack

- **Language**: Kotlin (JVM target 1.8)
- **Min SDK**: 24 (Android 7.0) / Target SDK: 36 (Android 15)
- **Build**: Gradle 8.13.2 with Kotlin DSL; dependency versions centralized in `gradle/libs.versions.toml`
- **Key library**: Media3 ExoPlayer 1.3.1 (`media3-exoplayer`, `media3-session`, `media3-ui`)
- **Layout**: ConstraintLayout with ViewBinding; portrait-only orientation
- **HTTP**: Cleartext traffic allowed in manifest (required for the unencrypted stream URL)

## UI Design Notes

Dark space theme (`#0A0A1A` background, blue/purple accents `#8888FF`/`#6666AA`). The play button has a pulse-ring animation defined in `res/drawable/`. Track info uses a marquee `TextView` for long strings. Bottom links have `72dp` bottom margin to clear the gesture navigation bar.
