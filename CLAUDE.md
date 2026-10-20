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

# Target the physical phone explicitly (do not use the emulator; it crashes this laptop)
adb -d install -r ...   # physical device

# Build signed release APK -> app/build/outputs/apk/release/app-release.apk
# (R8 minify + resource shrinking; needs keystore.properties, see below)
./gradlew assembleRelease

# View app logs
adb -d logcat -s RadioService
```

`adb` is at `~/Android/Sdk/platform-tools/adb` — add to PATH if needed.

Release signing: `keystore.properties` (gitignored, repo root) points at `~/.android/space101-release.jks`, alias `space101`. Both must be backed up off this laptop; if the key is lost, installed copies can't be updated without uninstalling. Without `keystore.properties`, `assembleRelease` produces an unsigned APK. Release and debug builds have different signatures, so switching a phone between them requires `adb -d uninstall com.space101.app` first.

No test suite is currently configured.

## Architecture

**Two-file app** — all logic lives in `app/src/main/java/com/space101/app/`:

- **`MainActivity.kt`** — Single activity. Binds to `RadioService` via `ServiceConnection` and a direct binder pattern (`RadioBinder`). Handles play/pause, displays track metadata, manages Android 13+ notification permission. Callbacks (`statusCallback`, `metadataCallback`) are set on bind and cleared on `onStop`.

- **`RadioService.kt`** — Foreground service using **Media3 ExoPlayer**. Uses `ProgressiveMediaSource` with `DefaultHttpDataSource` (sends `Icy-MetaData: 1` header). Audio focus is handled automatically by ExoPlayer (`setAudioAttributes(..., handleAudioFocus = true)`), which pauses playback when other apps request focus (speech-to-text, calls, etc.). Now-playing comes from ICY stream metadata — see Track Metadata below.

## Track Metadata

As of 2026-10-19 the Icecast stream sends ICY metadata (`icy-metaint: 16000`, `StreamTitle='Artist - Title'`). `RadioService` reads `IcyInfo.title` in `onMetadata`, splits on the first " - ", and pushes it to the UI, notification and MediaSession. A blank title clears the track, and every (re)connect clears it until the stream resends it (about one second). Spinitron would still add show names and album art but is no longer needed for now-playing.

## Tech Stack

- **Language**: Kotlin (JVM target 1.8)
- **Min SDK**: 24 (Android 7.0) / Target SDK: 36 (Android 15)
- **Build**: Gradle 8.13.2 with Kotlin DSL; dependency versions centralized in `gradle/libs.versions.toml`
- **Key library**: Media3 ExoPlayer 1.3.1 (`media3-exoplayer`); MediaSession and notification via `androidx.media` (MediaSessionCompat)
- **Layout**: ConstraintLayout with ViewBinding; portrait-only orientation
- **HTTP**: Cleartext traffic allowed in manifest (required for the unencrypted stream URL)

## UI Design Notes

Palette is sampled from the station logo: background `#0B1424`, logo blue `#365F93`, sage `#99B9AA` (all in `res/values/colors.xml`). The logo lives at `res/drawable-nodpi/station_logo.webp` (transparent cutout of `../kmgp/data/space101fm-logo-lg.jpg`) and is also used for the launcher icon and lock-screen/notification art. The app draws edge-to-edge; `MainActivity` pads the root view by the system-bar insets. The play button has a pulse-ring animation while live. Track info uses a marquee `TextView` for long strings.

## Playback States

`RadioService.status` is the single source of truth (`STOPPED`, `BUFFERING`, `PLAYING`, `RECONNECTING`, `PAUSED`, `FAILED`). Play always goes through `startForegroundService(ACTION_PLAY)` so the service is started rather than just bound, and every play reconnects to the live edge. Errors and server-side stream ends back off and retry up to 8 times before `FAILED`. A headphone unplug pauses and keeps the service in the foreground, and a Bluetooth reconnect resumes playback.
