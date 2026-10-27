package com.kirakos.kinet.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

private data class LogoBar(
    val x: Float,
    val y1: Float,
    val y2: Float,
    val isPrimary: Boolean
)

private val KINET_LOGO_BARS = listOf(
    // Left Pillar (Vocals - Warm Terracotta)
    LogoBar(38f, 38.62f, 64.62f, true),
    LogoBar(42f, 43.12f, 64.62f, true),

    // Roof Bridge (Master Mix - Dark Carbon / Primary Text)
    LogoBar(46f, 43.12f, 48.38f, false),
    LogoBar(50f, 43.12f, 48.38f, false),
    LogoBar(54f, 43.12f, 48.38f, false),

    // Right Pillar (Accompaniment - Dark Carbon / Primary Text)
    LogoBar(58f, 43.12f, 64.62f, false),
    LogoBar(62f, 43.12f, 64.62f, false),

    // Vowel Branch of ኪ (Pulse Node - Warm Terracotta)
    LogoBar(66f, 58.88f, 64.62f, true),
    LogoBar(70f, 54.12f, 69.38f, true)
)

/**
 * The official Kinet (ኪነት) brandmark: An equalizer soundwave outlining the Ethiopic letter ኪ.
 * The left pillar and pulse node represent Vocals (terracotta), while the roof bridge and
 * right pillar represent the instrumental accompaniment.
 */
@Composable
fun KinetLogo(
    modifier: Modifier = Modifier,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = MaterialTheme.colorScheme.onBackground
) {
    Canvas(modifier = modifier.size(34.dp, 32.dp)) {
        val minX = 38f
        val maxX = 70f
        val minY = 38.62f
        val maxY = 69.38f

        val spanX = maxX - minX
        val spanY = maxY - minY

        val strokePx = (size.width / spanX) * 2.2f
        val padX = strokePx / 2f
        val padY = strokePx / 2f

        val drawW = size.width - padX * 2f
        val drawH = size.height - padY * 2f

        for (bar in KINET_LOGO_BARS) {
            val normX = (bar.x - minX) / spanX
            val normY1 = (bar.y1 - minY) / spanY
            val normY2 = (bar.y2 - minY) / spanY

            val px = padX + normX * drawW
            val py1 = padY + normY1 * drawH
            val py2 = padY + normY2 * drawH

            drawLine(
                color = if (bar.isPrimary) primaryColor else secondaryColor,
                start = Offset(px, py1),
                end = Offset(px, py2),
                strokeWidth = strokePx,
                cap = StrokeCap.Round
            )
        }
    }
}
