package com.kirakos.kinet.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
