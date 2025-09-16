package com.example.capitalexpressapp.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CapitalExpressColorScheme = lightColorScheme(
    primary = Color(0xFF0061A7), // Azul institucional
    onPrimary = Color.White,
    background = Color.White,
    surface = Color(0xFFF8F8F8),
    onSurface = Color.Black,
    secondary = Color(0xFF0277BD),
    onSecondary = Color.White,
    error = Color(0xFFD32F2F)
)

@Composable
fun CapitalExpressTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CapitalExpressColorScheme,
        typography = Typography(),
        content = content
    )
}

