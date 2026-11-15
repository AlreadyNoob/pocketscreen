# Frontend API

The old interface is gone. This file lists what the app can do and how to reach it, and says nothing about how a screen should look or be arranged. Layout, navigation, copy, colors, icons and theming are all open.

The project has no XML layouts, no Compose and no UI library dependencies (`app/build.gradle.kts` lists only Shizuku). A new frontend can use Views in code or add a UI toolkit to Gradle.

## What exists

| Piece | State |
| --- | --- |
| `MainActivity.kt` | Empty launcher activity. Creates an `AppController` and forwards lifecycle. Replace its body. |
| `AppController.kt` | All logic the old main screen had, with no views. |
| `ViewerActivity.kt` | Working viewer with a placeholder bar. See "Viewer". |
| `Vd.kt`, `AppSettings.kt`, `Apps.kt`, `Updater.kt`, `Debug.kt` | Unchanged. Called directly for reads and settings. |
| `HostService.kt`, `VdService.kt`, `DirectInput.kt`, AIDL | Unchanged. Not UI. |
| `res/` | Only the launcher icon and the status-bar icon remain. |

Not present anywhere: any theme, palette, widget toolkit, dialog or menu helper.

## Wiring the main screen

```kotlin
controller = AppController(applicationContext)
controller.onStatus = { status -> ... }
controller.onSnapshot = { snap -> ... }
controller.onEvent = { event -> ... }
controller.onUpdateAvailable = { release -> ... }
controller.attach()        // onCreate
controller.start()         // onResume
controller.stop()          // onPause
controller.detach()        // onDestroy
```

Call everything on the main thread. Callbacks arrive on the main thread. The controller refreshes itself every 30 s while started, and shortly after any change to settings, quick-launch entries or sizes, so a screen only has to render what the callbacks give it.

### Callbacks

- `onStatus(Status)` on every refresh: `shizukuRunning`, `permitted`, `serviceConnected`, `displayCount`. Also the moment to re-read quick-launch entries. Launching is only possible when `serviceConnected`.
- `onSnapshot(Snapshot)` whenever the running list changes:
  - `rows: List<Row>` each is `Row(display: Vd.Disp, taskId, pkg)`: one app on one display
  - `emptyDisplays: List<Vd.Disp>` displays with nothing on them (the controller destroys these after about 30 s on its own)
  - `crashes: List<Vd.Crash>` apps that died unexpectedly and were not dismissed
  - `autoStops: List<Vd.AutoStop>` apps closed by a limit and not dismissed. Has `kind`, `detail`, `ranMin`, `whenMs`
  - `stats: Map<String, Vd.Stat>` memory (MB) and CPU share by package. Arrives a moment after the rows; empty when "show stats" is off
  - `runningAppCount`, `sharesDisplay(row)`
- `onEvent(Event)` things that happen during `launchApp`: `Launching`, `AlreadyLaunching`, `AlreadyRunning`, `NeedsRelaunchConfirm(pkg, landscape, changes)`, `Unsupported`, `NotOnHiddenDisplay`. None of them carries text; the frontend decides what, if anything, to tell the user.
- `onUpdateAvailable(Updater.Release)` from the automatic check (`version`, `build`, `page` URL, `notes`).

### Actions

| Call | Effect |
| --- | --- |
| `grantShizukuPermission()` | Asks Shizuku for permission. Result arrives as a refresh. |
| `connectService()` | Forces a reconnect to the Shizuku service. |
| `refresh()` / `refreshTasks()` | Re-read state / re-read only the running list. |
| `launchApp(pkg, landscape)` | Creates a display and starts the app. Reports through `onEvent`. |
| `relaunchWithNewSettings(pkg, landscape)` | Answer to `NeedsRelaunchConfirm`: stop, then launch again. |
| `relaunchCrashed(crash)`, `relaunchAutoStopped(stop)` | Clear the record and launch again. |
| `dismissCrash(pkg)`, `dismissAutoStop(pkg)` | Clear the record. |
| `stopApp(pkg)` | Close the app and remove its display. |
| `toggleMute(pkg)`, `restoreAllSound()` | Sound control. |
| `moveToOwnDisplay(row)` | Only meaningful when `snap.sharesDisplay(row)`. |
| `destroyDisplay(id)`, `stopAll()` | Remove an empty display / everything. |
| `clockLimitChanged()` | Call after changing any "stop at time" value. |
| `validateSize(cfg)` | Returns `null` or `SizeProblem.WIDTH/HEIGHT/DPI`. Ranges are in `AppController.Limits`. |
| `checkForUpdates { Updater.Result -> }` | `Newer(release)`, `UpToDate`, `Failed(why)`. |
| `debugLogText()` | Saved debug log. |
| `versionName` | App version string. |

## Data read and written directly

Opening a viewer for a display: `startActivity(Intent(this, ViewerActivity::class.java).putExtra("display", id))`. `id` is `Vd.Disp.id`.

**Installed apps** (`Apps`): `Apps.list: List<Apps.App>` where `App(label, pkg, landscape: Boolean?)` (`landscape` is the app's own preferred orientation if it declares one, `null` otherwise) and a lowercase `key` for searching; `Apps.ready`; `Apps.load(ctx, force, then)`; `Apps.label(ctx, pkg)`; `Apps.icon(ctx, pkg, onReady): Bitmap?` returns a cached bitmap or `null` and calls `onReady` when it is ready.

**Quick launch** (`Vd`): `presets(ctx): List<Pair<pkg, landscape>>`, `presetOf(ctx, pkg): Boolean?`, `savePreset(ctx, pkg, landscape)`, `removePreset(ctx, pkg)`.

**Display size** (`Vd`): `SizeCfg(matchRatio, width, customH, dpi)`. Default for all: `sizeCfg(ctx)` / `saveSizeCfg(ctx, cfg)`; factory default is `SizeCfg(true, Vd.DEF_WIDTH, 1600, Vd.DEF_DPI)`. Per app: `appCfg(ctx, pkg): SizeCfg?` (`null` means "use the default") / `setAppCfg(ctx, pkg, cfg or null)`. Resolved result: `sizeFor(ctx, landscape, cfg = null): Triple(width, height, dpi)`.

**Per-app facts** (`Vd`): `isMuted(ctx, pkg)`, `remainingText(ctx, pkg)`, `sessionText(ctx, pkg)`, `limitsText(ctx, pkg)`, `fmtMinutes(mins)`, `clockText(ctx, minuteOfDay)`. These return ready-made English strings, or `null` when there is nothing to show. Use or ignore.

**Settings** (`AppSettings`, and `Updater.autoEnabled/setAutoEnabled`, `Debug.enabled/setEnabled/clear`). Each setting has a getter and a setter taking `Context`. Per-app overrides share a convention:

- Auto-stop limits (battery %, temperature °C, minutes, clock time): global `autoStop*` and per-app `app*`. Global: `0` = off. Per app: `-1` = follow the global value, `0` = off. Clock time is stored as `minuteOfDay + 1` (so `0` stays "off"); pass `value - 1` to `Vd.clockText`. Resolved values: `effectiveBattery/Temp/Minutes/At(ctx, pkg)`.
- Refresh rate: global `refreshRate` (`0` = Auto, otherwise Hz up to `AppSettings.MAX_HZ`), per app `appRefreshRate` (`-1` = follow global). Needs Android 14+ to have an effect. Resolved: `Vd.refreshFor(ctx, pkg)`.
- Keyboard suppression and back gesture: global booleans, per app `-1` (follow), `0`, `1`. Resolved: `effectiveSuppressKeyboard/BackGesture`.
- Other globals: `showStats`, `hideFromRecents`, `dynamicColor` (was used only by the removed theme; nothing reads it now).

Ranges for typed values are in `AppController.Limits`.

## Viewer

`ViewerActivity` owns the display surface, touch forwarding (multi-touch, rotation aware), the soft/physical keyboard bridge, back-gesture handling, edge-gesture exclusion, immersive mode, refresh-rate request, per-app back/keyboard settings, and scaling the display into the space left above the keyboard. None of that is visual design and it should be kept.

The only visual part is a bare placeholder bar. To replace it, edit `buildChrome(root)` and the three hooks next to it:

- `onTitle(text)`: the app name(s) on the display
- `onSubtitle(text, isError)`: once-a-second status; `isError` when input injection is failing
- `onKeyboardState(visible)`: the soft keyboard opened or closed

`leave()` returns to `MainActivity`. `toggleKeyboard()` opens or closes the soft keyboard. `root` is a vertical `LinearLayout` that receives the system-bar and keyboard padding; the display box must stay a child that takes the remaining height, because `fit()` scales the display to that box.
