# PocketScreen redesign progress

## Overview
Goal: a deliberate, cohesive redesign of the whole UI, delivered in small batches. Functionality, Shizuku/AIDL service, settings storage and business logic are untouched.

Architecture: Android app (Kotlin, **no XML layouts, no Compose**). All UI is built in code with Views.
- `Ui.kt` — design system: palette, shape tokens, buttons, cards, chips, FAB, sheets, menus, custom views (IconView, SwitchView, LogoView, PhoneIcon)
- `MainActivity.kt` (~1400 lines) — main screen (header, status chips, setup card, quick launch, running list), app picker, orientation/size dialogs, Settings, per-app settings
- `ViewerActivity.kt` — display viewer/controls
- `res/values/ic_launcher_background.xml`, `res/drawable/ic_launcher_foreground.xml` — launcher icon

## Checklist
- [x] Batch 1.1 — Stage 1: design tokens, new palette, restyled primitives
- [x] Batch 2.1 — Stage 2: main header + status strip (MainActivity onCreate/refresh, Ui.statusStrip)
- [x] Batch 2.2 — Stage 2: setup card + docked launch bar
- [x] Batch 3.1 — Stage 3: Running-now rows (renderTasks, crashRow, emptyNote, appIcon)
- [x] Batch 3.2 — Stage 3: Quick launch rows (renderPresets, IconView.PLAY)
- [x] Batch 4.1 — Stage 4: Settings screen (showSettings, settingRowBase, settingsRow, value pills)
- [x] Batch 4.2 — Stage 4: per-app settings (showAppSettings)
- [x] Batch 5.1 — Stage 5: sheet titles, app picker, orientation tiles, size editor (sheets/dialogs)
- [x] Batch 5.2 — Stage 5: Ui.menu, Ui.sheet, SwitchView
- [x] Batch 6 — Stage 6: ViewerActivity chrome, landscape/tablet layouts
- [x] Batch 7 — Stage 7: consistency sweep (leftover hardcoded radii/colors), polish

## Files modified
| File | Changes |
|---|---|
| `app/src/main/java/com/hiderlab/vd/Ui.kt` | Added shape tokens (R_CARD 16, R_GROUP_OUTER 16, R_GROUP_INNER 4, R_BTN 14, R_FIELD 12, R_PILL 8, R_CHIP 10, R_FAB 18). New indigo palette (light and dark). Primitives now use tokens: button, card/surface, group, pill, chip, input, FAB. Section labels are sentence case, not all caps. Large bold titles use headline weight. |
| `app/src/main/java/com/hiderlab/vd/MainActivity.kt` | Header: 44dp logo, 26sp title, 13sp subtitle. Settings and More buttons now sit in one rounded toolbar (R_BTN) instead of two circles. The three loose status chips are replaced by one connected status strip (`refresh()`). |
| `MainActivity.kt` / `Ui.kt` (Batch 2.2) | Setup card is now an accent-container panel with a "Finish setup" title, helper text and filled buttons. The floating Launch app button is now a full-width docked bar (20dp side margins, centered icon+label, neutral shadow). `Ui.fab` keeps its name but stretches to the width it is given. |
| `MainActivity.kt` (Batch 3.1) | Running-now rows: display info merged into one line ("Display 3 · Portrait 1080×2400 · 420 DPI"); mute button is a tonal rounded square (R_FIELD) instead of an outlined circle; app icon corners 24% (was 28%); crash "Relaunch" button uses R_CHIP with ripple; empty state is a dashed outline with no fill. |
| `MainActivity.kt` / `Ui.kt` (Batch 3.2) | Quick launch rows: meta line now "Portrait · 1080×2400 · 420 DPI" (same format as running rows); "Custom settings" text replaced by an accent "Custom" pill beside the name; new tonal accent play button (R_FIELD, 40dp) launches the app, row tap still launches too. Added `IconView.PLAY` (filled rounded triangle). |
| `MainActivity.kt` (Batch 4.1) | Settings sheet: 26sp headline with the app version as a subtitle (the footer version line is removed); 26dp gap between sections; setting rows have 14dp vertical padding, a 2dp gap and 1.1 line spacing between title and subtitle, 16dp gap before the trailing control; notes use 1.15 line spacing; limit and refresh-rate value pills use `R_CHIP` (was hardcoded 16dp) with tighter padding; `settingsRow(..., danger = true)` tints the title red ("Clear log file"). `settingRowBase` takes an optional `titleColor`. Note: the `section()` note spacing also affects per-app settings. |
| `MainActivity.kt` (Batch 4.2) | Per-app settings sheet: header is now the app's 48dp icon + 24sp name + 13sp scope note (was plain 22sp text); section spacing matches the Settings sheet (8dp first, 26dp between). |
| `MainActivity.kt` / `Ui.kt` (Batch 5.1) | Added `Ui.R_SHEET = 24` (sheet corners, was 28) and `Ui.sheetTitle(ctx, title, sub?)`: 24sp headline plus optional 13sp muted subtitle. All 8 sheet titles (app picker, orientation, unsupported, size editor, limit/refresh/tri option sheets, stop-all) now use it instead of 22sp text. Sheet side padding 24dp. App picker rows: 44dp icons with 24% rounded corners, ripple row background (R_FIELD), tighter padding. Orientation tiles use R_CARD instead of hardcoded 20dp. |
| `Ui.kt` (Batch 5.2) | `Ui.menu`: R_CARD corners, 6dp inner padding, 248dp wide, rows 48dp with inset rounded ripples (R_FIELD−2), 15sp text, muted labels 36dp. Sheet handle 40×4dp, scrim 0.55. `SwitchView`: 48×28dp squarer track (radius 36% of height) with a rounded-square thumb (6.5→10dp) instead of a pill and circle. |
| `ViewerActivity.kt` / `MainActivity.kt` (Batch 6) | Viewer top bar keeps its 48dp height (more display area) but back and keyboard buttons are 36dp tonal rounded squares (SURFACE2, R_FIELD−2) with ripples, title 15sp, subtitle 12sp. Main screen: content is a centered column of at most 600dp on wide screens (tablets, landscape); the docked Launch bar follows the same width. Phones under 640dp keep the 20dp margins. |
| `Ui.kt` / `MainActivity.kt` (Batch 7) | Consistency sweep. Size-editor preset chips used a hardcoded 26dp pill radius; they now use `R_CHIP`. `Ui.rippleCircle()` (default press feedback for every `IconView`) is now a rounded square at `R_FIELD` instead of a circle. Remaining intentional non-token shapes: status dots, dashed empty-state outline, handle bar. Light-theme contrast reviewed by value (muted text on BG and SURFACE2 about 5:1 or better; accent on accent container about 4.6:1). |
| `Ui.kt` (Batch 2.1) | Added `Ui.statusStrip()`: one card with equal-width dot+label segments and hairline dividers. `Ui.chip` is now unused by the main screen. |
| `app/src/main/res/values/ic_launcher_background.xml` | Background `#0C0D12` to match new dark BG |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | Strokes/fill `#5EE6C8` to `#9EA7FF` |

## Design documentation
Direction: calm, tool-like, squarer. Replaces the old teal + pill-shaped look with indigo and 8–18dp corners.
- Dark: BG `#0C0D12`, SURFACE `#14161D`, SURFACE2 `#1E212B`, OUTLINE `#2B2F3B`, TEXT `#ECEEF5`, MUTED `#9096A8`, ACCENT `#9EA7FF` (on `#12154A`), ACCENT_CONT `#2A2F6B`
- Light: BG `#F4F5FA`, SURFACE `#FFFFFF`, SURFACE2 `#E9EBF4`, OUTLINE `#D8DBE8`, TEXT `#14161F`, MUTED `#5A6074`, ACCENT `#4650E0` (on white), ACCENT_CONT `#E0E2FF`
- Status: OK / WARN / BAD unchanged in meaning (green/amber/red)
- Android 12+ dynamic wallpaper colors still override the palette when enabled in Settings
- Type: sans-serif-medium for emphasis, sans-serif bold for 24sp+ headlines, section labels 13sp medium muted
- Radii: only via `Ui.R_*` tokens. Do not hardcode new radii.
- Grouped lists: 16dp outer corners, 4dp inner corners, 2dp gaps

## Outstanding / known issues
- **Not compiled (batch 5.1 too: no Kotlin/Gradle here).** Batch 5.1 uses only existing imports; check `Ui.sheetTitle` call sites first if the build fails.
- **Not compiled (earlier note).** The build environment had no Gradle/Android SDK and no network. Changes are small and token-based, but build with `gradle assembleDebug` (or the GitHub workflow) and report any error.
- Hardcoded radii/colors still exist outside Ui.kt primitives (e.g. 24dp circular header buttons in MainActivity, custom icon views, sheets, menu). Cleaned up in later batches.
- Dynamic-color users do not see the new accent (by design).

## Exact next task
All planned batches are done. Build with `gradle assembleDebug` (or the GitHub workflow), fix any compile errors (none of batches 1-7 were compiled), then review on a device in light and dark, portrait and landscape. Report anything that looks wrong so it can be fixed as a small follow-up batch.

## Superseded
(done)
Earlier `REDESIGN_PROGRESS (1).md` / `(2).md` copies in the upload are stale; this file is the source of truth.
