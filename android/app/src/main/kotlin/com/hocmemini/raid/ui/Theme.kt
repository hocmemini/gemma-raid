package com.hocmemini.raid.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.sin

object Dungeon {
    val gold = Color(0xFFD4AF37)
    val ember = Color(0xFFFF6E40)
    val blood = Color(0xFF8E1B1B)
    val stone = Color(0xFF17130F)
    val panel = Color(0xFF2A2117)
    val parchment = Color(0xFFE8D8B0)
    val moss = Color(0xFF69F0AE)
}

private val scheme = darkColorScheme(
    primary = Dungeon.gold, onPrimary = Dungeon.stone,
    secondary = Dungeon.ember, onSecondary = Dungeon.stone,
    background = Dungeon.stone, onBackground = Dungeon.parchment,
    surface = Dungeon.panel, onSurface = Dungeon.parchment,
    surfaceVariant = Color(0xFF3A2E22), onSurfaceVariant = Dungeon.parchment,
    outline = Dungeon.gold.copy(alpha = 0.6f), error = Dungeon.blood,
)

private val serif = FontFamily.Serif
private val type = Typography(
    displayLarge = TextStyle(fontFamily = serif, fontWeight = FontWeight.Black, fontSize = 44.sp, letterSpacing = 4.sp),
    headlineMedium = TextStyle(fontFamily = serif, fontWeight = FontWeight.Bold, fontSize = 24.sp),
    titleMedium = TextStyle(fontFamily = serif, fontWeight = FontWeight.Bold, fontSize = 17.sp),
    bodyLarge = TextStyle(fontFamily = serif, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = serif, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = serif, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = serif, fontWeight = FontWeight.Bold, fontSize = 15.sp),
)

@Composable
fun RaidTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, typography = type, content = content)

/** Torch-lit vignette over the camera: dark edges that breathe, a warm tint, so the office reads as a dungeon. */
@Composable
fun DungeonVignette(intensity: Float = 1f) {
    val flicker by rememberInfiniteTransition(label = "torch").animateFloat(0f, 1f, infiniteRepeatable(tween(2300, easing = LinearEasing), RepeatMode.Restart), label = "f")
    Canvas(Modifier.fillMaxSize()) {
        val t = flicker * 6.283f
        val wob = 0.06f * sin(t * 3f) + 0.04f * sin(t * 7.3f)
        val r = size.maxDimension * (0.62f + wob)
        drawRect(Brush.radialGradient(
            0f to Color.Transparent, 0.55f to Color(0x22140A00), 1f to Color(0xE60A0603).copy(alpha = (0.9f + wob) * intensity),
            center = Offset(size.width / 2, size.height * 0.45f), radius = r,
        ))
        // warm cast
        drawRect(Color(0xFFFF9A3C).copy(alpha = 0.06f * intensity))
    }
}
