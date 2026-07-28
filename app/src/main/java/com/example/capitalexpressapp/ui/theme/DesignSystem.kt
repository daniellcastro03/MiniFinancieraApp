package com.example.capitalexpressapp.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tokens del "Capital Express Design System" (Modern Fintech Minimalism):
 * mismos valores en todas las pantallas para que el reskin sea consistente.
 */
object CEColors {
    val Primary = Color(0xFF0A192F)
    val PrimaryLight = Color(0xFF112240)
    val Secondary = Color(0xFF64FFDA)
    val ActionBlue = Color(0xFF007AFF)
    val Surface = Color(0xFFF8FAFC)
    val SurfaceContainerLow = Color(0xFFF2F4F6)
    val SurfaceContainer = Color(0xFFECEEF0)
    val OnSurface = Color(0xFF191C1E)
    val OnSurfaceVariant = Color(0xFF44474D)
    val Outline = Color(0xFF75777E)
    val OutlineVariant = Color(0xFFE2E8F0)
    val Error = Color(0xFFF43F5E)
    val ErrorContainer = Color(0xFFFFDAD6)
}

/** Tarjeta blanca estándar del sistema: borde 1dp, radio 16dp, sombra difusa nivel 1. */
@Composable
fun PremiumCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Box(
        modifier = (if (onClick != null) modifier.clickable { onClick() } else modifier)
            .shadow(4.dp, RoundedCornerShape(16.dp), ambientColor = CEColors.Primary.copy(alpha = 0.08f))
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(1.dp, CEColors.OutlineVariant, RoundedCornerShape(16.dp))
    ) {
        content()
    }
}

/** Título de sección en mayúsculas, como "CREAR" / "VISUALIZAR" en los mockups. */
@Composable
fun SeccionTitulo(texto: String, modifier: Modifier = Modifier) {
    Text(
        text = texto.uppercase(),
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = CEColors.Primary.copy(alpha = 0.6f),
        modifier = modifier
    )
}

/** Caja de icono redondeada (12dp) usada dentro de las tarjetas premium. */
@Composable
fun IconoCaja(
    modifier: Modifier = Modifier,
    contenedorColor: Color,
    size: androidx.compose.ui.unit.Dp = 48.dp,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .size(size)
            .background(contenedorColor, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** Borde punteado (el "Buscar Actualizaciones" del mockup usa border-dashed). */
fun Modifier.dashedBorder(
    color: Color,
    cornerRadius: androidx.compose.ui.unit.Dp = 16.dp,
    strokeWidth: androidx.compose.ui.unit.Dp = 1.5.dp
): Modifier = this.drawWithContent {
    drawContent()
    drawRoundRect(
        color = color,
        style = Stroke(
            width = strokeWidth.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
        ),
        cornerRadius = CornerRadius(cornerRadius.toPx())
    )
}
