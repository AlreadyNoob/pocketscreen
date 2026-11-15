# PocketScreen

Run Android apps on **hidden virtual displays** and keep them alive in the background, even on phones whose system freezer kills background apps.

PocketScreen was built for phones like the Tecno CAMON 40 Pro, where a system freezer ("Hiber") freezes any app a few seconds after it leaves the foreground, no matter what battery settings say. An app running on its own virtual display never counts as "in the background", so it keeps running. PocketScreen creates those displays, launches apps on them, and lets you look at and control an app through a viewer when you want to.

> **Status:** personal project, built and tested mostly on one device. Expect rough edges and please open an issue with the details if something breaks.

## Requirements

- Android 8.0 or newer (`minSdk` 26). Hidden displays are driven with `am` commands, so newer Android versions are more likely to behave.
- [Shizuku](https://shizuku.rikka.app/) installed and running (wireless debugging is enough, no root).
- Permission for PocketScreen in Shizuku. The app has a button for it.

## Install

1. Download the latest `PocketScreen-<version>.apk` from the [Releases](../../releases) page.
2. Install it. Every build is signed with the same key, so new builds install over old ones.
3. Start Shizuku, open PocketScreen, tap **Grant Shizuku permission**, then **Connect service** if needed.

## Using it

The interface is written with Jetpack Compose and Material 3 (`ui/` package). [FRONTEND_API.md](FRONTEND_API.md) describes the controller it drives.

Flow, without any particular screen layout: pick an installed app, choose portrait or landscape, and a hidden display is created with the app running on it. Running apps can be opened in a viewer that mirrors the display and sends touches and keys to it, muted, given limits, or stopped.

### Features

- **One app per hidden display**, with orientation and size you choose.
- **Quick launch:** save an app with its orientation and size, then start it with one tap.
- **Per-app size:** a custom resolution and DPI for one app. Settings holds the default for everything else.
- **Refresh rate:** Auto (the phone's own rate), 30/60/90/120/144 Hz or any custom value, per app with a default in Settings. Needs Android 14+; Android rounds it to a rate the screen can show, so it can't exceed the phone's own maximum. Applies at launch.
- **Mute per app** (the app is unmuted on every launch; mute it again from its menu).
- **Auto-stop** per app, with defaults in Settings: stop at a battery level (when not charging) and/or after running for a set time. Presets or any custom value. Stopping an app also removes its display.
- **Stats:** approximate memory (PSS) and CPU share per app. CPU is an average over the last few minutes, as a share of the whole CPU.
- **Cleanup:** displays with nothing on them are removed automatically after about 30 seconds.
- **Viewer options:** the back gesture can control the app or be ignored, and the keyboard can be kept from popping up unless you ask for it.
- **Updates:** Settings → Updates checks GitHub for a newer release (also automatically, at most twice a day; can be turned off). It opens the release page, nothing installs by itself.
- **Debug log:** off by default, writes to a file only, and can be shared from Settings.

### Recents

By default apps are launched with the "exclude from Recents" flag so hidden apps do not clutter Recents. Turn off **Hide apps from Recents** in Settings if you would rather see them there.

The changes in each version are listed in [CHANGELOG.md](CHANGELOG.md).

## How it works

PocketScreen has two halves:

- **The app** (`AppController`, `ViewerActivity`, `HostService`) holds the app-side logic, keeps a foreground notification so Android does not kill it while displays exist, and checks the auto-stop limits every 30 seconds.
- **A service run through Shizuku** (`VdService`) runs as the shell user. It creates the virtual displays, reads their frames into the viewer, injects touch and key events, and runs `am`, `cmd` and `dumpsys` commands. The two talk over AIDL (`IVdService.aidl`).

## Building

The project is a standard Gradle Android project (Kotlin, Jetpack Compose, no XML layouts).

```bash
gradle assembleDebug     # Gradle 8.10+, JDK 17
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions (`.github/workflows/build.yml`) builds on every push and pull request, uploads the APK as a workflow artifact, and on pushes to `main` publishes a release named `v<appVersion>.<run number>`, with the matching section of `CHANGELOG.md` as its notes. Add a section to the changelog when you raise `appVersion`. The version base is `appVersion` in `gradle.properties`. The version code is the run number.

The debug build is signed with `app/hiderlab.jks`, a key that is committed to the repository on purpose so that builds keep the same signature. It is not a secret and not a release-grade key.

## Project layout

| File | What it does |
| --- | --- |
| `AppController.kt` | UI-free logic for the main screen: state, launching, stopping, refresh loop |
| `MainActivity.kt` | Empty launcher activity, wired to `AppController` |
| `ViewerActivity.kt`, `DirectInput.kt` | The viewer (display surface, touch, keyboard, back gesture) |
| `Vd.kt` | App-side logic: displays, launching, mute, auto-stop, presets, stats |
| `VdService.kt`, `IVdService.aidl` | The service that runs through Shizuku |
| `HostService.kt` | Foreground notification and the auto-stop checks |
| `AppSettings.kt`, `Debug.kt`, `Apps.kt` | Preferences, debug log, installed-app list and icons |
| `Updater.kt` | Update check against GitHub releases |

## Limitations

- It depends on hidden Android behavior, so it may not work the same on every phone or Android version.
- Memory and CPU are read from `dumpsys`, whose output differs between devices. A value that cannot be read is simply left out.
- Auto-stop only knows about apps PocketScreen launched, and it needs Shizuku and the service to be connected.
- Checks run every 30 seconds, so a stop can be up to 30 seconds late.
