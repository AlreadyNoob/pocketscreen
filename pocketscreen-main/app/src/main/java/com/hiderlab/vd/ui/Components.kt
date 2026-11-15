package com.hiderlab.vd.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hiderlab.vd.Apps
import com.hiderlab.vd.Vd

@Composable
fun AppIcon(pkg: String, size: Dp = 44.dp) {
    val ctx = LocalContext.current
    var bmp by remember(pkg) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(pkg) { bmp = Apps.icon(ctx, pkg) { bmp = it } }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 20.dp, bottom = 4.dp)
    )
}

@Composable
fun <T> Choice(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, v) ->
            FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(label) })
        }
    }
}

@Composable
fun SwitchRow(title: String, desc: String?, initial: Boolean, onChange: (Boolean) -> Unit) {
    var on by remember { mutableStateOf(initial) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { on = !on; onChange(on) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (desc != null) Text(
                desc, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = on, onCheckedChange = null)
    }
}

@Composable
fun IntField(
    label: String,
    initial: Int,
    range: IntRange,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    onValid: (Int) -> Unit
) {
    var text by remember { mutableStateOf(initial.toString()) }
    val parsed = text.toIntOrNull()
    val ok = parsed != null && parsed in range
    val support: (@Composable () -> Unit)? =
        if (!ok) ({ Text("${range.first}–${range.last}") }) else null
    val suf: (@Composable () -> Unit)? = suffix?.let { s -> ({ Text(s) }) }
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            if (s.length <= 6 && s.all { it.isDigit() }) {
                text = s
                s.toIntOrNull()?.takeIf { it in range }?.let(onValid)
            }
        },
        label = { Text(label) },
        suffix = suf,
        singleLine = true,
        isError = !ok,
        supportingText = support,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

/**
 * A limit with the storage convention used by AppSettings: -1 follows the global value (only when
 * [allowDefault]), 0 is off, anything above is a custom value.
 */
@Composable
fun LimitSetting(
    title: String,
    hint: String? = null,
    initial: Int,
    allowDefault: Boolean,
    range: IntRange,
    unit: String,
    offLabel: String = "Off",
    startAt: Int,
    onChange: (Int) -> Unit
) {
    var v by remember { mutableIntStateOf(initial) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (hint != null) Text(
            hint, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val mode = if (v < 0) -1 else if (v == 0) 0 else 1
        val opts = buildList {
            if (allowDefault) add("Default" to -1)
            add(offLabel to 0)
            add("Custom" to 1)
        }
        Choice(opts, mode) { m ->
            val nv = when (m) {
                -1 -> -1
                0 -> 0
                else -> if (v > 0) v else startAt
            }
            v = nv
            onChange(nv)
        }
        if (v > 0) {
            IntField(
                label = "Value", initial = v, range = range, suffix = unit,
                modifier = Modifier.width(180.dp)
            ) { nv -> v = nv; onChange(nv) }
        }
    }
}

/** Clock-time limit. Stored as minuteOfDay + 1; 0 = off; -1 = follow the global value. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeSetting(title: String, initial: Int, allowDefault: Boolean, onChange: (Int) -> Unit) {
    val ctx = LocalContext.current
    var v by remember { mutableIntStateOf(initial) }
    var dialog by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            "Stops the app the next time the clock reaches this time, once.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val mode = if (v < 0) -1 else if (v == 0) 0 else 1
        val opts = buildList {
            if (allowDefault) add("Default" to -1)
            add("Off" to 0)
            add("Custom" to 1)
        }
        Choice(opts, mode) { m ->
            if (m == 1) dialog = true else { v = m; onChange(m) }
        }
        if (v > 0) OutlinedButton(onClick = { dialog = true }) { Text(Vd.clockText(ctx, v - 1)) }
    }
    if (dialog) {
        val start = if (v > 0) v - 1 else 7 * 60
        val st = rememberTimePickerState(
            initialHour = start / 60,
            initialMinute = start % 60,
            is24Hour = android.text.format.DateFormat.is24HourFormat(ctx)
        )
        AlertDialog(
            onDismissRequest = { dialog = false },
            confirmButton = {
                TextButton(onClick = {
                    v = st.hour * 60 + st.minute + 1
                    onChange(v)
                    dialog = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { dialog = false }) { Text("Cancel") } },
            text = { TimePicker(state = st) }
        )
    }
}
