package com.hiderlab.vd.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

@Composable
fun SetupScreen(model: AppModel) {
    val ctx = LocalContext.current
    val link = model.link

    Column(
        Modifier
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        Illustration()
        Spacer(Modifier.height(28.dp))
        Text("A second screen\ninside your phone", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "PocketScreen runs apps on hidden displays, so they keep going while you do something else. " +
                "It needs Shizuku to do that.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))

        Step(
            title = "Shizuku is running",
            detail = "Start it from the Shizuku app, with wireless debugging or root.",
            done = link != Link.NoShizuku,
            active = link == Link.NoShizuku,
        ) {
            Button(onClick = {
                val launch = ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
                ctx.startActivity(
                    (launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) { Text(if (ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG) != null) "Open Shizuku" else "Get Shizuku") }
        }
        Spacer(Modifier.height(12.dp))
        Step(
            title = "Allow PocketScreen",
            detail = if (model.denied)
                "Access was refused. In Shizuku, open Authorized apps and switch PocketScreen on."
            else "Shizuku will ask once.",
            done = link == Link.Connecting || link == Link.Ready,
            active = link == Link.NeedsPermission,
        ) {
            if (model.denied) {
                Button(onClick = {
                    ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)?.let { ctx.startActivity(it) }
                }) { Text("Open Shizuku") }
            } else {
                Button(onClick = { model.requestPermission() }) { Text("Allow access") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Step(
            title = "Connect",
            detail = "Starting the helper process.",
            done = link == Link.Ready,
            active = link == Link.Connecting,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(12.dp))
                TextButton(onClick = { model.retryConnect() }) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun Step(
    title: String,
    detail: String,
    done: Boolean,
    active: Boolean,
    action: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (active) cs.primaryContainer else cs.surfaceContainerLow, label = "stepBg")
    Surface(color = bg, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (done) cs.primary else cs.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (done) Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = cs.onPrimary)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (active) cs.onPrimaryContainer else if (done) cs.onSurfaceVariant else cs.onSurface,
                )
                if (active) {
                    Spacer(Modifier.height(4.dp))
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer)
                    Spacer(Modifier.height(14.dp))
                    action()
                }
            }
        }
    }
}

@Composable
private fun Illustration() {
    val outline = MaterialTheme.colorScheme.outline
    val accent = MaterialTheme.colorScheme.primary
    val fill = MaterialTheme.colorScheme.primaryContainer
    Canvas(Modifier.size(width = 140.dp, height = 120.dp)) {
        val stroke = 3.dp.toPx()
        // the phone you hold
        drawRoundRect(
            outline, Offset(stroke, stroke), Size(size.width * 0.5f, size.height - stroke * 2),
            CornerRadius(20.dp.toPx()), style = Stroke(stroke),
        )
        // the screen you can't see, same proportions, sitting just behind
        val w = size.width * 0.5f
        val h = size.height - stroke * 2
        val topLeft = Offset(size.width - w - stroke, stroke * 4)
        drawRoundRect(fill, topLeft, Size(w, h - stroke * 3), CornerRadius(20.dp.toPx()))
        drawRoundRect(
            accent, topLeft, Size(w, h - stroke * 3), CornerRadius(20.dp.toPx()),
            style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f))),
        )
    }
}
