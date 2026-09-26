package com.iamode.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object IAColors {
    val Violet = Color(0xFF6D4AFF)
    val VioletDark = Color(0xFFB9A8FF)
    val Green = Color(0xFF1E9E63)
    val Amber = Color(0xFFD98A00)
    val Red = Color(0xFFD93A3A)
    val Blue = Color(0xFF2F6FEB)
    val Grey = Color(0xFF7A7A85)
}

private val Light = lightColorScheme(
    primary = IAColors.Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E1FF),
    onPrimaryContainer = Color(0xFF22135E),
    secondary = Color(0xFF5B5870),
    background = Color(0xFFFAF9FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF1EFF6),
    error = IAColors.Red,
)

private val Dark = darkColorScheme(
    primary = IAColors.VioletDark,
    onPrimary = Color(0xFF22135E),
    primaryContainer = Color(0xFF3B2A8F),
    onPrimaryContainer = Color(0xFFE8E1FF),
    secondary = Color(0xFFC8C4DC),
    background = Color(0xFF141318),
    surface = Color(0xFF1B1A21),
    surfaceVariant = Color(0xFF26242E),
    error = Color(0xFFFF8A80),
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)

/** [dynamic] = Material You colors from the wallpaper (Android 12+); falls back to the IA Mode palette. */
@Composable
fun IAModeTheme(dark: Boolean = isSystemInDarkTheme(), dynamic: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}
