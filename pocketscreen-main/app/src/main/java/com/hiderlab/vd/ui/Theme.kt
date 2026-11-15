package com.hiderlab.vd.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val Light = lightColorScheme(
    primary = Color(0xFF00696B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF0F2),
    onPrimaryContainer = Color(0xFF002021),
    secondary = Color(0xFF4A6364),
    secondaryContainer = Color(0xFFCCE8E9),
    tertiary = Color(0xFF4F5F7D),
    background = Color(0xFFF4FBFA),
    surface = Color(0xFFF4FBFA),
    error = Color(0xFFBA1A1A)
)

private val Dark = darkColorScheme(
    primary = Color(0xFF80D4D6),
    onPrimary = Color(0xFF003738),
    primaryContainer = Color(0xFF004F51),
    onPrimaryContainer = Color(0xFF9CF0F2),
    secondary = Color(0xFFB0CCCD),
    secondaryContainer = Color(0xFF324B4C),
    tertiary = Color(0xFFB7C7E9),
    background = Color(0xFF0E1415),
    surface = Color(0xFF0E1415),
    error = Color(0xFFFFB4AB)
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun PocketTheme(dynamic: Boolean, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, shapes = AppShapes, content = content)
}
