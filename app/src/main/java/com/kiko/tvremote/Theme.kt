package com.kiko.tvremote

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val FallbackDark = darkColorScheme(
    primary = Color(0xFF4A6CF7),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2B3A7A),
    onPrimaryContainer = Color(0xFFDDE3FF),
    secondaryContainer = Color(0xFF2A2D3A),
    onSecondaryContainer = Color(0xFFE2E4F0),
    background = Color(0xFF111111),
    surface = Color(0xFF1A1A1E),
    surfaceVariant = Color(0xFF24242A),
    onSurfaceVariant = Color(0xFFB8BAC6),
)

@Composable
fun TvRemoteTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        FallbackDark
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
