package com.hiderlab.vd.ui

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object IconCache {
    private val cache = LruCache<String, ImageBitmap>(256)
    fun peek(pkg: String): ImageBitmap? = cache.get(pkg)
    suspend fun load(ctx: android.content.Context, pkg: String, px: Int): ImageBitmap? =
        cache.get(pkg) ?: withContext(Dispatchers.IO) {
            runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(px, px).asImageBitmap() }.getOrNull()
        }?.also { cache.put(pkg, it) }
}

@Composable
fun AppIcon(pkg: String, size: Dp = 44.dp) {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bmp by produceState(IconCache.peek(pkg), pkg) { value = IconCache.load(ctx, pkg, px) }
    val shape = RoundedCornerShape(size * 0.26f)
    val b = bmp
    if (b != null) {
        Image(b, contentDescription = null, modifier = Modifier.size(size).clip(shape))
    } else {
        Box(Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

/**
 * A hidden display drawn at its real proportions: tall for portrait, wide for landscape,
 * with the running app’s icon inside. The shape itself tells you how the app is laid out.
 */
@Composable
fun DisplayGlyph(w: Int, h: Int, pkg: String?, modifier: Modifier = Modifier) {
    val long = 68.dp
    val ratio = (w.toFloat() / h.toFloat()).coerceIn(0.4f, 2.5f)
    val gw = if (ratio >= 1f) long else long * ratio
    val gh = if (ratio >= 1f) long / ratio else long
    Box(modifier.size(long), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(gw, gh)
                .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (pkg != null) AppIcon(pkg, 20.dp)
        }
    }
}
