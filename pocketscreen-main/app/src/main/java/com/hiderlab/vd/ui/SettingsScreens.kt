package com.hiderlab.vd.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hiderlab.vd.AppController
import com.hiderlab.vd.AppController.Limits
import com.hiderlab.vd.AppSettings
import com.hiderlab.vd.Apps
import com.hiderlab.vd.Debug
import com.hiderlab.vd.Updater
import com.hiderlab.vd.Vd

private val OVERRIDE = listOf("Default" to -1, "On" to 1, "Off" to 0)

/** Width, height, DPI with a match-ratio switch. Calls [onSave] only with a valid config. */
@Composable
private fun SizeEditor(c: AppController, initial: Vd.SizeCfg, onSave: (Vd.SizeCfg) -> Unit) {
    var cfg by remember { mutableStateOf(initial) }
    var saved by remember { mutableStateOf(false) }
    val problem = c.validateSize(cfg)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SwitchRow(
            "Match phone screen ratio", "Height follows the phone's own shape.",
            cfg.matchRatio
        ) { cfg = cfg.copy(matchRatio = it); saved = false }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IntField(
                "Width", cfg.width, Limits.WIDTH_MIN..Limits.WIDTH_MAX, Modifier.weight(1f), "px"
            ) { cfg = cfg.copy(width = it); saved = false }
            if (!cfg.matchRatio) IntField(
                "Height", cfg.customH, Limits.HEIGHT_MIN..Limits.HEIGHT_MAX, Modifier.weight(1f), "px"
            ) { cfg = cfg.copy(customH = it); saved = false }
            IntField(
                "DPI", cfg.dpi, Limits.DPI_MIN..Limits.DPI_MAX, Modifier.weight(1f)
            ) { cfg = cfg.copy(dpi = it); saved = false }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = problem == null, onClick = { onSave(cfg); saved = true }) { Text("Save size") }
            Spacer(Modifier.width(12.dp))
            Text(
                if (saved) "Saved. Applies to displays created from now on." else "Applies to displays created from now on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Hz(initial: Int, allowDefault: Boolean, onChange: (Int) -> Unit) {
    LimitSetting(
        title = "Refresh rate",
        hint = "Needs Android 14 or newer. Android rounds it to a rate the screen can show. Applies at launch.",
        initial = initial, allowDefault = allowDefault,
        range = Limits.HZ_MIN..Limits.HZ_MAX, unit = "Hz", offLabel = "Auto", startAt = 60,
        onChange = onChange
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                }
            )
        }
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) { content() }
    }
}

// ------------------------------------------------------------------ global settings

@Composable
fun SettingsScreen(c: AppController, onBack: () -> Unit) {
    val ctx = LocalContext.current
    Screen("Settings", onBack) {
        SectionHeader("Default display size", Modifier.padding(top = 8.dp))
        SizeEditor(c, Vd.sizeCfg(ctx)) { Vd.saveSizeCfg(ctx, it) }

        SectionHeader("Refresh rate")
        Hz(AppSettings.refreshRate(ctx), allowDefault = false) { AppSettings.setRefreshRate(ctx, it) }

        SectionHeader("Auto-stop")
        Text(
            "Defaults for every app. Each app can override them. Checks run about every 30 seconds.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LimitSetting(
            "Stop at battery level", "Only when not charging.",
            AppSettings.autoStopBattery(ctx), false, Limits.BATTERY_MIN..Limits.BATTERY_MAX, "%", startAt = 20
        ) { AppSettings.setAutoStopBattery(ctx, it) }
        LimitSetting(
            "Stop at temperature", "Battery temperature, charging or not.",
            AppSettings.autoStopTemp(ctx), false, Limits.TEMP_MIN..Limits.TEMP_MAX, "°C", startAt = 45
        ) { AppSettings.setAutoStopTemp(ctx, it) }
        LimitSetting(
            "Stop after running", "Per app, counted from when it was launched.",
            AppSettings.autoStopMinutes(ctx), false, Limits.MINUTES_MIN..Limits.MINUTES_MAX, "min", startAt = 60
        ) { AppSettings.setAutoStopMinutes(ctx, it) }
        TimeSetting("Stop at time", AppSettings.autoStopAt(ctx), false) {
            AppSettings.setAutoStopAt(ctx, it); c.clockLimitChanged()
        }

        SectionHeader("Viewer")
        SwitchRow(
            "Back gesture controls the app", "Off: the back gesture is ignored inside the viewer.",
            AppSettings.backGesture(ctx)
        ) { AppSettings.setBackGesture(ctx, it) }
        SwitchRow(
            "Keep the keyboard hidden", "It only appears from the keyboard button in the viewer.",
            AppSettings.suppressKeyboard(ctx)
        ) { AppSettings.setSuppressKeyboard(ctx, it) }

        SectionHeader("General")
        SwitchRow(
            "Hide apps from Recents", "Launch apps with the exclude-from-Recents flag.",
            AppSettings.hideFromRecents(ctx)
        ) { AppSettings.setHideFromRecents(ctx, it) }
        SwitchRow(
            "Show memory and CPU", "Turning this off makes refreshing faster.",
            AppSettings.showStats(ctx)
        ) { AppSettings.setShowStats(ctx, it) }
        SwitchRow(
            "Use system colors", "Android 12 and newer: take colors from the wallpaper.",
            AppSettings.dynamicColor(ctx)
        ) { AppSettings.setDynamicColor(ctx, it) }
        var restore by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { c.restoreAllSound(); restore = true }) { Text("Restore sound for all apps") }
        if (restore) Text(
            "Done.", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp)
        )

        SectionHeader("Updates")
        UpdatesSection(c)

        SectionHeader("Debug")
        DebugSection(c)

        HorizontalDivider(Modifier.padding(vertical = 20.dp))
        Text(
            "PocketScreen ${c.versionName}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun UpdatesSection(c: AppController) {
    val ctx = LocalContext.current
    var result by remember { mutableStateOf<Updater.Result?>(null) }
    var busy by remember { mutableStateOf(false) }
    SwitchRow(
        "Check automatically", "At most twice a day. Nothing installs by itself.",
        Updater.autoEnabled(ctx)
    ) { Updater.setAutoEnabled(ctx, it) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            c.checkForUpdates { result = it; busy = false }
        }) { Text(if (busy) "Checking…" else "Check now") }
        when (val r = result) {
            is Updater.Result.Newer -> {
                Text("Version ${r.release.version} is out", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = {
                    if (r.release.page.isNotBlank())
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.release.page)))
                }) { Text("Open") }
            }
            Updater.Result.UpToDate -> Text("You're up to date", style = MaterialTheme.typography.bodyMedium)
            is Updater.Result.Failed -> Text(
                "Couldn't check (${r.why})", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            null -> {}
        }
    }
}

@Composable
private fun DebugSection(c: AppController) {
    val ctx = LocalContext.current
    var note by remember { mutableStateOf<String?>(null) }
    SwitchRow(
        "Debug log", "Written to a file on this phone only. Off by default.",
        Debug.enabled(ctx)
    ) { Debug.setEnabled(ctx, it) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            val t = c.debugLogText()
            if (t.isEmpty()) note = "The log is empty."
            else ctx.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, t.takeLast(100_000)),
                    "Share debug log"
                )
            )
        }) { Text("Share") }
        OutlinedButton(onClick = {
            val t = c.debugLogText()
            if (t.isEmpty()) note = "The log is empty."
            else {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("PocketScreen log", t.takeLast(100_000)))
                note = "Copied."
            }
        }) { Text("Copy") }
        TextButton(onClick = { Debug.clear(ctx); note = "Cleared." }) { Text("Clear") }
    }
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
}

// ------------------------------------------------------------------ per-app settings

@Composable
fun AppScreen(pkg: String, c: AppController, onBack: () -> Unit) {
    val ctx = LocalContext.current
    Screen(Apps.label(ctx, pkg), onBack) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(pkg, 56.dp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(Apps.label(ctx, pkg), style = MaterialTheme.typography.titleLarge)
                Text(
                    pkg, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            "Size and refresh rate apply the next time the app is launched. Everything set to Default follows Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp)
        )

        SectionHeader("Quick launch")
        var quick by remember { mutableStateOf(Vd.presetOf(ctx, pkg)) }
        SwitchRow("In quick launch", null, quick != null) { on ->
            if (on) { Vd.savePreset(ctx, pkg, false); quick = false } else { Vd.removePreset(ctx, pkg); quick = null }
        }
        quick?.let { land ->
            Choice(listOf("Portrait" to false, "Landscape" to true), land) {
                Vd.savePreset(ctx, pkg, it); quick = it
            }
        }

        SectionHeader("Display size")
        var custom by remember { mutableStateOf(Vd.appCfg(ctx, pkg) != null) }
        SwitchRow("Custom size for this app", null, custom) {
            custom = it
            if (!it) Vd.setAppCfg(ctx, pkg, null) else Vd.setAppCfg(ctx, pkg, Vd.sizeCfg(ctx))
        }
        if (custom) SizeEditor(c, Vd.appCfg(ctx, pkg) ?: Vd.sizeCfg(ctx)) { Vd.setAppCfg(ctx, pkg, it) }

        SectionHeader("Refresh rate")
        Hz(AppSettings.appRefreshRate(ctx, pkg), allowDefault = true) { AppSettings.setAppRefreshRate(ctx, pkg, it) }

        SectionHeader("Auto-stop")
        LimitSetting(
            "Stop at battery level", "Only when not charging.",
            AppSettings.appBattery(ctx, pkg), true, Limits.BATTERY_MIN..Limits.BATTERY_MAX, "%", startAt = 20
        ) { AppSettings.setAppBattery(ctx, pkg, it) }
        LimitSetting(
            "Stop at temperature", null,
            AppSettings.appTemp(ctx, pkg), true, Limits.TEMP_MIN..Limits.TEMP_MAX, "°C", startAt = 45
        ) { AppSettings.setAppTemp(ctx, pkg, it) }
        LimitSetting(
            "Stop after running", null,
            AppSettings.appMinutes(ctx, pkg), true, Limits.MINUTES_MIN..Limits.MINUTES_MAX, "min", startAt = 60
        ) { AppSettings.setAppMinutes(ctx, pkg, it) }
        TimeSetting("Stop at time", AppSettings.appAt(ctx, pkg), true) {
            AppSettings.setAppAt(ctx, pkg, it); c.clockLimitChanged()
        }

        SectionHeader("Viewer")
        var back by remember { mutableStateOf(AppSettings.appBackGesture(ctx, pkg)) }
        Text("Back gesture controls the app", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(6.dp))
        Choice(OVERRIDE, back) { back = it; AppSettings.setAppBackGesture(ctx, pkg, it) }
        Spacer(Modifier.height(16.dp))
        var kb by remember { mutableStateOf(AppSettings.appSuppressKeyboard(ctx, pkg)) }
        Text("Keep the keyboard hidden", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(6.dp))
        Choice(OVERRIDE, kb) { kb = it; AppSettings.setAppSuppressKeyboard(ctx, pkg, it) }
    }
}
