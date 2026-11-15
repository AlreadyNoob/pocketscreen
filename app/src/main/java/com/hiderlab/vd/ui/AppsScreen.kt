package com.hiderlab.vd.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hiderlab.vd.Apps
import com.hiderlab.vd.Vd

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(model: AppModel) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Apps.App?>(null) }

    val groups = remember(model.apps, query) {
        model.apps
            .filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) }
            .groupBy { it.label.firstOrNull()?.takeIf { c -> c.isLetter() }?.uppercaseChar() ?: '#' }
    }

    Column(Modifier.fillMaxSize()) {
        SearchField(query) { query = it }
        Spacer(Modifier.height(8.dp))

        when {
            !model.appsReady -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            groups.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), Alignment.TopCenter) {
                Text(
                    if (query.isBlank()) "No launchable apps found." else "Nothing matches “${query.trim()}”.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
                groups.forEach { (letter, list) ->
                    stickyHeader(key = "h$letter") {
                        Text(
                            letter.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background)
                                .padding(horizontal = 24.dp, vertical = 6.dp),
                        )
                    }
                    items(list, key = { it.pkg }) { app ->
                        AppRow(
                            app = app,
                            running = model.isRunning(app.pkg),
                            busy = app.pkg in model.busy,
                            onClick = { selected = app },
                        )
                    }
                }
            }
        }
    }

    selected?.let { app ->
        LaunchSheet(
            app = app,
            running = model.isRunning(app.pkg),
            onDismiss = { selected = null },
            onLaunch = { landscape -> model.launch(app, landscape); selected = null },
            onStop = { model.stop(app.pkg); selected = null },
        )
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text("Search apps") },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = {
            if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Close, "Clear") }
        },
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
    )
}

@Composable
private fun AppRow(app: Apps.App, running: Boolean, busy: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppIcon(app.pkg, 44.dp)
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            if (app.landscape == true) {
                Text("Landscape", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when {
            busy -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            running -> Text("Running", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LaunchSheet(
    app: Apps.App,
    running: Boolean,
    onDismiss: () -> Unit,
    onLaunch: (landscape: Boolean) -> Unit,
    onStop: () -> Unit,
) {
    val ctx = LocalContext.current
    var landscape by remember { mutableStateOf(app.landscape == true) }
    val (w, h, dpi) = remember(landscape) { Vd.sizeFor(ctx, landscape, Vd.appCfg(ctx, app.pkg)) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AppIcon(app.pkg, 56.dp)
                Text(app.label, style = MaterialTheme.typography.titleLarge, maxLines = 2)
            }
            Spacer(Modifier.height(24.dp))
            if (running) {
                Text(
                    "Already running on a hidden display.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) { Text("Stop ${app.label}") }
            } else {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("Portrait" to false, "Landscape" to true).forEachIndexed { i, (label, value) ->
                        SegmentedButton(
                            selected = landscape == value,
                            onClick = { landscape = value },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) { Text(label) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "$w × $h px at $dpi dpi",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = { onLaunch(landscape) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Open on hidden display")
                }
            }
        }
    }
}
