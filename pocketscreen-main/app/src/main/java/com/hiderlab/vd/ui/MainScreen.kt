package com.hiderlab.vd.ui

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hiderlab.vd.AppController
import com.hiderlab.vd.Apps
import com.hiderlab.vd.Vd

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    ui: UiState,
    c: AppController,
    onViewer: (Int) -> Unit,
    onSettings: () -> Unit,
    onApp: (String) -> Unit
) {
    val ctx = LocalContext.current
    val rev = ui.rev
    val snap = ui.snap
    val status = ui.status
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ui.message) {
        ui.message?.let { snackbar.showSnackbar(it); ui.message = null }
    }

    var picker by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Apps.App?>(null) }
    var confirmStopAll by remember { mutableStateOf(false) }

    val ready = status?.serviceConnected == true
    val runningPkgs = snap.rows.map { it.pkg }.toSet()
    val presets = remember(rev) { Vd.presets(ctx) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PocketScreen") },
                actions = {
                    if (snap.rows.isNotEmpty() || snap.emptyDisplays.isNotEmpty()) {
                        TextButton(onClick = { confirmStopAll = true }) { Text("Stop all") }
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (ready) picker = true else ui.message = "Connect to Shizuku first" },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Launch app") }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { inner ->
        LazyColumn(
            Modifier
                .padding(inner)
                .fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ui.update?.let { r ->
                item(key = "update") {
                    NoticeCard(
                        title = "Update available: ${r.version}",
                        body = null,
                        primary = "View release" to {
                            if (r.page.isNotBlank()) ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.page)))
                            ui.update = null
                        },
                        secondary = "Later" to { ui.update = null },
                        tone = MaterialTheme.colorScheme.secondaryContainer
                    )
                }
            }

            if (status != null && !ready) {
                item(key = "setup") { SetupCard(status, c) }
            }

            items(snap.crashes.filter { it.pkg !in runningPkgs }, key = { "crash_" + it.pkg }) { cr ->
                NoticeCard(
                    title = "${Apps.label(ctx, cr.pkg)} stopped unexpectedly",
                    body = listOfNotNull(
                        DateUtils.getRelativeTimeSpanString(cr.whenMs).toString(),
                        cr.reason.takeIf { it.isNotBlank() }
                    ).joinToString(" · "),
                    primary = "Relaunch" to { c.relaunchCrashed(cr) },
                    secondary = "Dismiss" to { c.dismissCrash(cr.pkg) },
                    tone = MaterialTheme.colorScheme.errorContainer
                )
            }
            items(snap.autoStops.filter { it.pkg !in runningPkgs }, key = { "stop_" + it.pkg }) { a ->
                NoticeCard(
                    title = "${Apps.label(ctx, a.pkg)} was stopped by a limit",
                    body = listOfNotNull(
                        a.kind.takeIf { it.isNotBlank() },
                        a.detail.takeIf { it.isNotBlank() },
                        "ran ${Vd.fmtMinutes(a.ranMin)}",
                        DateUtils.getRelativeTimeSpanString(a.whenMs).toString()
                    ).joinToString(" · "),
                    primary = "Relaunch" to { c.relaunchAutoStopped(a) },
                    secondary = "Dismiss" to { c.dismissAutoStop(a.pkg) },
                    tone = MaterialTheme.colorScheme.tertiaryContainer
                )
            }

            if (snap.rows.isEmpty()) {
                if (ready) item(key = "empty") { EmptyState() }
            } else {
                item(key = "running_h") { SectionHeader("Running · ${snap.runningAppCount}") }
                items(snap.rows, key = { "${it.display.id}_${it.pkg}" }) { row ->
                    RunningCard(
                        row = row,
                        stat = snap.stats[row.pkg],
                        shared = snap.sharesDisplay(row),
                        rev = rev,
                        c = c,
                        onOpen = { onViewer(row.display.id) },
                        onConfig = { onApp(row.pkg) }
                    )
                }
            }

            if (snap.emptyDisplays.isNotEmpty()) {
                item(key = "emptydisp_h") { SectionHeader("Empty displays") }
                items(snap.emptyDisplays, key = { "disp_" + it.id }) { d ->
                    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(d.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { c.destroyDisplay(d.id) }) { Text("Remove") }
                        }
                    }
                }
            }

            item(key = "quick_h") { SectionHeader("Quick launch") }
            item(key = "quick") {
                if (presets.isEmpty()) {
                    Text(
                        "Save an app when you launch it and it shows up here for one-tap starts.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(presets, key = { it.first }) { (pkg, landscape) ->
                            QuickTile(
                                pkg = pkg,
                                landscape = landscape,
                                running = pkg in runningPkgs,
                                onClick = {
                                    val disp = snap.rows.firstOrNull { it.pkg == pkg }?.display?.id
                                    if (disp != null) onViewer(disp)
                                    else if (ready) c.launchApp(pkg, landscape)
                                    else ui.message = "Connect to Shizuku first"
                                },
                                onRemove = { Vd.removePreset(ctx, pkg); ui.rev++ }
                            )
                        }
                    }
                }
            }
        }
    }

    if (picker) {
        AppPicker(onDismiss = { picker = false }) { app -> picker = false; pending = app }
    }
    pending?.let { app ->
        LaunchDialog(app, onDismiss = { pending = null }) { landscape, save ->
            if (save) Vd.savePreset(ctx, app.pkg, landscape)
            c.launchApp(app.pkg, landscape)
            pending = null
            ui.rev++
        }
    }
    if (confirmStopAll) {
        AlertDialog(
            onDismissRequest = { confirmStopAll = false },
            title = { Text("Stop everything?") },
            text = { Text("This closes every app and removes every hidden display.") },
            confirmButton = {
                TextButton(onClick = { c.stopAll(); confirmStopAll = false }) { Text("Stop all") }
            },
            dismissButton = { TextButton(onClick = { confirmStopAll = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SetupCard(s: AppController.Status, c: AppController) {
    val ctx = LocalContext.current
    val t: Triple<String, String, Pair<String, () -> Unit>> = when {
        !s.shizukuRunning -> Triple(
            "Shizuku isn't running",
            "Start Shizuku (wireless debugging is enough), then come back here.",
            "Open Shizuku" to {
                ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    ?.let { ctx.startActivity(it) }
                Unit
            }
        )
        !s.permitted -> Triple(
            "Allow PocketScreen in Shizuku",
            "PocketScreen needs Shizuku permission to create hidden displays.",
            "Grant permission" to { c.grantShizukuPermission() }
        )
        else -> Triple(
            "Connecting to the service…",
            "If this takes more than a few seconds, try connecting again.",
            "Connect" to { c.connectService() }
        )
    }
    val (title, body, action) = t
    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Button(onClick = action.second) { Text(action.first) }
        }
    }
}

@Composable
private fun NoticeCard(
    title: String,
    body: String?,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>,
    tone: Color
) {
    Card(colors = CardDefaults.cardColors(tone)) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (!body.isNullOrBlank()) Text(
                body, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = secondary.second) { Text(secondary.first) }
                TextButton(onClick = primary.second) { Text(primary.first) }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Nothing running", style = MaterialTheme.typography.titleMedium)
        Text(
            "Launch an app and it keeps running on its own hidden display, even with the screen off.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun RunningCard(
    row: AppController.Row,
    stat: Vd.Stat?,
    shared: Boolean,
    rev: Int,
    c: AppController,
    onOpen: () -> Unit,
    onConfig: () -> Unit
) {
    val ctx = LocalContext.current
    val pkg = row.pkg
    // Re-read the per-app facts on every refresh.
    val muted = remember(rev) { Vd.isMuted(ctx, pkg) }
    val facts = remember(rev) {
        listOfNotNull(
            Vd.sessionText(ctx, pkg)?.let { "running $it" },
            Vd.remainingText(ctx, pkg),
            stat?.memMb?.let { "$it MB" },
            stat?.cpu?.let { "${"%.0f".format(it)}% CPU" }
        ).joinToString(" · ")
    }
    val limits = remember(rev) { Vd.limitsText(ctx, pkg) }
    var menu by remember { mutableStateOf(false) }

    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(pkg, 48.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        Apps.label(ctx, pkg), style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        row.display.label + if (row.display.fps > 0) " · ${row.display.fps} Hz" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (facts.isNotEmpty()) Text(
                        facts, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (limits != null) Text(
                        limits, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("App settings") }, onClick = { menu = false; onConfig() })
                        if (shared) DropdownMenuItem(
                            text = { Text("Move to its own display") },
                            onClick = { menu = false; c.moveToOwnDisplay(row) }
                        )
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(onClick = onOpen) { Text("Open") }
                OutlinedButton(onClick = { c.toggleMute(pkg) }) { Text(if (muted) "Unmute" else "Mute") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { c.stopApp(pkg) }) {
                    Text("Stop", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickTile(pkg: String, landscape: Boolean, running: Boolean, onClick: () -> Unit, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .width(84.dp)
                .clip(RoundedCornerShape(20.dp))
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AppIcon(pkg, 56.dp)
            Text(
                Apps.label(ctx, pkg), style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                if (running) "Open" else if (landscape) "Landscape" else "Portrait",
                style = MaterialTheme.typography.labelSmall,
                color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Remove from quick launch") }, onClick = { menu = false; onRemove() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPicker(onDismiss: () -> Unit, onPick: (Apps.App) -> Unit) {
    val ctx = LocalContext.current
    var ready by remember { mutableStateOf(Apps.ready) }
    LaunchedEffect(Unit) { Apps.load(ctx) { ready = true } }
    var q by remember { mutableStateOf("") }
    val list = remember(q, ready) {
        val s = q.trim().lowercase()
        if (s.isEmpty()) Apps.list else Apps.list.filter { it.key.contains(s) || it.pkg.contains(s) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        OutlinedTextField(
            value = q, onValueChange = { q = it },
            placeholder = { Text("Search apps") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )
        if (!ready) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(list, key = { it.pkg }) { app ->
                    ListItem(
                        headlineContent = { Text(app.label) },
                        supportingContent = { Text(app.pkg, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { AppIcon(app.pkg) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { onPick(app) }
                    )
                }
            }
        }
    }
}

@Composable
private fun LaunchDialog(app: Apps.App, onDismiss: () -> Unit, onLaunch: (landscape: Boolean, save: Boolean) -> Unit) {
    val ctx = LocalContext.current
    val saved = remember(app.pkg) { Vd.presetOf(ctx, app.pkg) }
    var landscape by remember { mutableStateOf(saved ?: app.landscape ?: false) }
    var save by remember { mutableStateOf(saved != null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { AppIcon(app.pkg, 48.dp) },
        title = { Text(app.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Orientation", style = MaterialTheme.typography.labelLarge)
                Choice(listOf("Portrait" to false, "Landscape" to true), landscape) { landscape = it }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { save = !save },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = save, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                    Text("Save to quick launch")
                }
            }
        },
        confirmButton = { Button(onClick = { onLaunch(landscape, save) }) { Text("Launch") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun RelaunchDialog(e: AppController.Event.NeedsRelaunchConfirm, c: AppController, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val what = e.changes.joinToString(", ") {
        when (it) {
            AppController.Change.SIZE -> "size"
            AppController.Change.REFRESH_RATE -> "refresh rate"
            AppController.Change.ORIENTATION -> "orientation"
        }
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Relaunch ${Apps.label(ctx, e.pkg)}?") },
        text = { Text("It's already running, but its $what changed since it started. Relaunching applies the new settings and restarts the app.") },
        confirmButton = {
            TextButton(onClick = { c.relaunchWithNewSettings(e.pkg, e.landscape); onDone() }) { Text("Relaunch") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Keep running") } }
    )
}
