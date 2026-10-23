package com.kirakos.kinet.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirakos.kinet.ui.theme.HardwareCarbon
import com.kirakos.kinet.ui.theme.RecessedPlateColor
import com.kirakos.kinet.ui.theme.StoneBorder
import com.kirakos.kinet.ui.theme.StoneTextMuted
import com.kirakos.kinet.ui.theme.WarmTerracotta
import kotlin.math.cos
import kotlin.math.sin

/**
 * 5x5 Dot grid matrix from screenshot2.png
 */
@Composable
fun DotGridMatrix(
    modifier: Modifier = Modifier,
    rows: Int = 4,
    cols: Int = 4,
    dotRadius: Dp = 1.2.dp,
    spacing: Dp = 6.dp,
    color: Color = MaterialTheme.colorScheme.outline
) {
    Canvas(modifier = modifier) {
        val spPx = spacing.toPx()
        val rPx = dotRadius.toPx()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                drawCircle(
                    color = color,
                    radius = rPx,
                    center = Offset(c * spPx + rPx, r * spPx + rPx)
                )
            }
        }
    }
}

/**
 * Angled technical connector arrow with 45° or 90° bend from screenshot2.png
 */
@Composable
fun TechnicalBentArrow(
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = MaterialTheme.colorScheme.primary
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val path = Path().apply {
            moveTo(2.dp.toPx(), h - 4.dp.toPx())
            lineTo(w * 0.6f, h - 4.dp.toPx())
            lineTo(w - 4.dp.toPx(), 4.dp.toPx())
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 1.5.dp.toPx())
        )

        // Arrow head
        val headPath = Path().apply {
            moveTo(w - 10.dp.toPx(), 4.dp.toPx())
            lineTo(w - 4.dp.toPx(), 4.dp.toPx())
            lineTo(w - 4.dp.toPx(), 10.dp.toPx())
        }
        drawPath(
            path = headPath,
            color = color,
            style = Stroke(width = 1.5.dp.toPx())
        )
    }
}

/**
 * Diagonal hatched tactile grip texture (//////)
 */
@Composable
fun HatchedGrip(
    modifier: Modifier,
    barCount: Int = 10,
    color: Color = MaterialTheme.colorScheme.outline
) {
    Canvas(modifier = modifier) {
        val step = size.width / barCount
        for (i in 0 until barCount) {
            val x = i * step
            drawLine(
                color = color,
                start = Offset(x, size.height),
                end = Offset(x + step * 0.8f, 0f),
                strokeWidth = 1.2.dp.toPx()
            )
        }
    }
}

/**
 * Tactical rackmount screw head with single slot or cross slot
 */
@Composable
fun ScrewHead(
    modifier: Modifier = Modifier.size(10.dp),
    slotAngleDegrees: Float = 35f,
    headColor: Color = RecessedPlateColor,
    slotColor: Color = HardwareCarbon.copy(alpha = 0.75f)
) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f - 0.5.dp.toPx()

        // Outer beveled ring
        drawCircle(
            color = StoneBorder,
            radius = r,
            center = center,
            style = Stroke(width = 0.8.dp.toPx())
        )
        // Center screw dome
        drawCircle(
            color = headColor,
            radius = r - 0.8.dp.toPx(),
            center = center
        )

        // Screw driver slot
        val rad = Math.toRadians(slotAngleDegrees.toDouble())
        val slotLen = r * 0.72f
        val dx = (cos(rad) * slotLen).toFloat()
        val dy = (sin(rad) * slotLen).toFloat()

        drawLine(
            color = slotColor,
            start = Offset(center.x - dx, center.y - dy),
            end = Offset(center.x + dx, center.y + dy),
            strokeWidth = 1.2.dp.toPx()
        )
    }
}

/**
 * Industrial status LED lamp indicator (green, amber, terracotta, or muted off)
 */
@Composable
fun IndustrialLed(
    isActive: Boolean,
    modifier: Modifier = Modifier.size(8.dp),
    activeColor: Color = WarmTerracotta,
    inactiveColor: Color = StoneTextMuted.copy(alpha = 0.35f)
) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f

        // Outer bezel ring
        drawCircle(
            color = HardwareCarbon.copy(alpha = 0.5f),
            radius = r,
            center = center,
            style = Stroke(width = 1.dp.toPx())
        )

        // Core lamp
        drawCircle(
            color = if (isActive) activeColor else inactiveColor,
            radius = r - 1.dp.toPx(),
            center = center
        )

        // Glowing center pip when active
        if (isActive) {
            drawCircle(
                color = Color.White.copy(alpha = 0.75f),
                radius = (r - 1.dp.toPx()) * 0.4f,
                center = center
            )
        }
    }
}

/**
 * Compact industrial monospace tag / badge
 */
@Composable
fun IndustrialTag(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = WarmTerracotta,
    backgroundColor: Color = WarmTerracotta.copy(alpha = 0.12f)
) {
    Box(
        modifier = modifier
            .background(backgroundColor, RoundedCornerShape(3.dp))
            .border(0.5.dp, color.copy(alpha = 0.35f), RoundedCornerShape(3.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text.uppercase(),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            color = color
        )
    }
}
