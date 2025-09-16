package com.example.capitalexpressapp.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Paleta de colores clara
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0061A7),       // Azul institucional
    onPrimary = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    secondary = Color(0xFFFF9800),     // Naranja
    onSecondary = Color.White,
    error = Color.Red,
    onError = Color.White
)

// Paleta oscura si la necesitas (opcional)
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF0061A7),
    onPrimary = Color.White,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.DarkGray,
    onSurface = Color.White,
    secondary = Color(0xFFFF9800),
    onSecondary = Color.Black,
    error = Color.Red,
    onError = Color.Black
)

// Tipografía por defecto de Material 3
private val AppTypography = Typography()

// Esquinas por defecto
private val AppShapes = Shapes()

@Composable
fun CapitalExpressAppTheme(
    useDarkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colors = if (useDarkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
