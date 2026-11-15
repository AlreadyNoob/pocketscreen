package com.hiderlab.vd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun RunningScreen(model: AppModel, onBrowse: () -> Unit) {
    val items = model.running
    if (items.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(40.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DisplayGlyph(720, 1600, null)
            Spacer(Modifier.height(20.dp))
            Text("Nothing running", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Apps you open land on their own hidden display and keep going in the background.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onBrowse) { Text("Browse apps") }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (items.size == 1) "1 hidden display" else "${items.size} hidden displays",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { model.stopAll() }) { Text("Stop all") }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.navigationBarsPadding(),
        ) {
            items(items, key = { it.pkg }) { r -> RunningCard(r, model) }
        }
    }
}

@Composable
private fun RunningCard(r: Running, model: AppModel) {
    val busy = r.pkg in model.busy
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DisplayGlyph(r.disp.w, r.disp.h, r.pkg)
            Column(Modifier.weight(1f)) {
                Text(r.label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    "${if (r.disp.landscape) "Landscape" else "Portrait"}, ${r.disp.w} × ${r.disp.h}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    since(r.startedAt, model.now),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = r.muted,
                        onClick = { model.toggleMute(r) },
                        label = { Text(if (r.muted) "Muted" else "Mute") },
                    )
                    Box(Modifier.weight(1f))
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        FilledTonalButton(onClick = { model.stop(r.pkg) }) { Text("Stop") }
                    }
                }
            }
        }
    }
}

private fun since(start: Long?, now: Long): String {
    if (start == null) return "Running"
    val m = ((now - start) / 60_000L).coerceAtLeast(0)
    return when {
        m < 1 -> "Just started"
        m < 60 -> "Running for $m min"
        else -> "Running for ${m / 60} h ${"%02d".format(m % 60)} min"
    }
}
