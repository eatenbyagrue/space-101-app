# Space 101 FM — Android App

An unofficial Android app for streaming [Space 101.1 FM](https://www.space101fm.org/) (KMGP · Seattle).

## About

No-frills streaming app. One play button, two links. No ads, no tracking, no data collection.


## Sideloading

No app store required. You can install it directly on any Android phone (7.0+).

**Step 1 — Download the APK**

Grab the latest `app-debug.apk` from the [Releases](../../releases) page.

**Step 2 — Allow installs from unknown sources**

On your phone: **Settings → Apps → Special app access → Install unknown apps**, then allow your browser or file manager.

**Step 3 — Install**

Open the downloaded APK file on your phone and tap Install.

> You may see a warning that the app is from an unknown source — this is normal for apps not distributed via the Play Store.

## Building from source

Requires Android SDK and JDK 17+.

```bash
# Build
./gradlew assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

See [CLAUDE.md](CLAUDE.md) for full development notes.

## Stack

- Kotlin, Android SDK 24–36
- Media3 ExoPlayer (streaming, wake lock, audio focus)
- MediaSessionCompat (lock screen controls, car display, Bluetooth)

## Disclaimer

Unofficial fan-made app. Not affiliated with or endorsed by Space 101.1 FM / KMGP.
