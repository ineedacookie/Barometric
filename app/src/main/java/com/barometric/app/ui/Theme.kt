package com.barometric.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF215E61), onPrimary = Color.White,
    primaryContainer = Color(0xFFD8EEEA), onPrimaryContainer = Color(0xFF143C3D),
    secondary = Color(0xFF6F649A), secondaryContainer = Color(0xFFEAE5F4),
    background = Color(0xFFF7F8F5), onBackground = Color(0xFF203137),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF203137),
    surfaceVariant = Color(0xFFEFF1EE), onSurfaceVariant = Color(0xFF5C6B70),
    outline = Color(0xFF77858A), outlineVariant = Color(0xFFD9E0DD),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF9CD5CA), onPrimary = Color(0xFF123C3D),
    primaryContainer = Color(0xFF214D4D), onPrimaryContainer = Color(0xFFCFEDE5),
    secondary = Color(0xFFCEC0EF), secondaryContainer = Color(0xFF403954),
    background = Color(0xFF131D20), onBackground = Color(0xFFE0EAE7),
    surface = Color(0xFF1B272B), onSurface = Color(0xFFE0EAE7),
    surfaceVariant = Color(0xFF28363A), onSurfaceVariant = Color(0xFFB3C4C7),
    outlineVariant = Color(0xFF394B4D),
)

@Composable fun BarometricTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = colors, content = content)
}

fun ratingColor(rating: Number): Color = Color.hsv((5 - rating.toFloat().coerceIn(0f, 5f)) * 24f, .60f, .88f)
val ratingNames = listOf("No headache", "Very mild", "Mild", "Moderate", "Severe", "Very severe")
