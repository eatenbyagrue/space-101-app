# Space 101 FM — Android App

An unofficial Android app for streaming [Space 101.1 FM](https://www.space101fm.org/) (KMGP · Seattle).

## About

Simple, no-frills streaming app. Play button, live track info, nothing else. No ads, no tracking, no data collection.

## Building

Requires Android SDK and JDK 17+.

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Stack

- Kotlin, Android SDK 24–36
- Media3 ExoPlayer for audio streaming
- ICY metadata for live track info

## Disclaimer

This is an unofficial fan-made app, not affiliated with or endorsed by Space 101.1 FM / KMGP.
