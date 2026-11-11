# Changelog

Releases are named `v<version>.<build>`: the first two numbers are the version below, the last is the build number (also the app's version code). Each release page shows the notes of its version.

## 0.20

- Less background work: the notification, crash check and limits now share one scan every 30 s (60 s while the screen is off) instead of scanning every 5 s. Battery and temperature changes reuse the last scan. Time limits are checked at the exact moment they run out, and an auto-stop skips an app that has already quit.
- Update checker: Settings → Updates checks GitHub for a newer release, and the app looks by itself at most twice a day (can be turned off).
- Relaunching an app that is already running now asks first, but only when its size, refresh rate or orientation changed since it started. Otherwise you get the "already running" message.
- The notification's app count is read from the live display list, so it no longer keeps showing an app you already stopped. With nothing running it says "No apps running".
- Apps are launched with a fresh task so "Hide apps from Recents" also works for apps that already had an entry in Recents.
- The viewer attaches the display again when you come back to it, and reconnects to the service if it was dropped.
- Each hidden display is kept awake with its own wake lock.
- Auto-stop fixes, notification and UI polish.
- Removed the development notes file and the old `build-N` pre-release naming.

## 0.19

- New interface: indigo theme, redesigned main screen, Settings and per-app settings sheets, new icons.
- Per-app refresh rate (Android 14+), with a default in Settings.
- Multi-touch in the viewer and a smoother viewer.
- Fixed a false "crashed" notice for apps that were started on the main screen.
- Fixed the mute icon state and stop button text.

## 0.18

- Per-app keyboard and back-gesture options (they follow Settings unless changed).
- Launching an app that is already running no longer starts a second copy.
- Display removal after stopping an app is faster.
- Apps that die on their own are noted, with a button to relaunch them.
- The viewer handles back swipes itself instead of letting the system show the status bar.
- Phones that report no support for apps on secondary displays get a clear message instead of an app opening on the main screen.

## 0.17

- First tracked releases: hidden displays through Shizuku, quick launch, per-app size, auto-stop (battery, time, temperature), memory and CPU stats, and the debug log.
- Fixed the app crashing on launch, the CPU figure (now a share of the whole CPU) and the duplicate "Off" / "Default (Off)" options.
- Shows the time left next to the elapsed time when an app has a time limit.
- Apps are launched with "exclude from Recents" (Settings can turn this off).
- Proper README, a working CI build and one release per build.
