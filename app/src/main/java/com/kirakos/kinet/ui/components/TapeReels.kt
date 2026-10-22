package com.kirakos.kinet.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirakos.kinet.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun DualTapeReels(
    modifier: Modifier = Modifier,
    isPlaying: Boolean,
    progress: Float // 0f to 1f
) {
    val infiniteTransition = rememberInfiniteTransition(label = "tapeReels")
    val animatedRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "reelSpin"
    )

    val currentRotation = if (isPlaying) animatedRotation else (progress * 720f) % 360f

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Spool (Vocals / Feed Reel)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            TapeReel(
                rotationAngle = currentRotation,
                tapePackRatio = 1f - (progress * 0.75f), // shrinks from 1.0 to 0.25
                reelLabel = "A",
                modifier = Modifier.size(136.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Reel a • Vocals",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.5.sp
            )
        }

        // Right Spool (Accompaniment / Take-up Reel)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            TapeReel(
                rotationAngle = currentRotation,
                tapePackRatio = 0.25f + (progress * 0.75f), // grows from 0.25 to 1.0
                reelLabel = "B",
                modifier = Modifier.size(136.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Reel b • Music",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.5.sp
            )
        }
    }
}

@Composable
fun TapeReel(
    rotationAngle: Float,
    tapePackRatio: Float,
    reelLabel: String,
    modifier: Modifier = Modifier
) {
    val spoolChassis = MaterialTheme.colorScheme.surfaceVariant
    val spoolRim = MaterialTheme.colorScheme.outline
    val tapeColor = TapeBrown
    val hubColor = HardwareCarbon
    val centerAccent = MaterialTheme.colorScheme.primary
    val tickColor = CreamOffWhite

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val outerRadius = size.minDimension / 2f - 2.dp.toPx()
        val hubRadius = outerRadius * 0.38f

        // 1. Spool Background Disc (Recessed bed)
        drawCircle(
            color = spoolChassis.copy(alpha = 0.7f),
            radius = outerRadius,
            center = center
        )

        // 2. Dynamic Tape Pack (Dark magnetic tape wound around hub)
        val tapeOuterRadius = hubRadius + (outerRadius - hubRadius - 8.dp.toPx()) * tapePackRatio.coerceIn(0.15f, 1f)
        drawCircle(
            color = tapeColor,
            radius = tapeOuterRadius,
            center = center
        )

        // Tape pack outer texture rings
        drawCircle(
            color = Color.Black.copy(alpha = 0.25f),
            radius = tapeOuterRadius,
            center = center,
            style = Stroke(width = 1.dp.toPx())
        )

        // 3. Rotating Spool Assembly (Flange cutouts & NAB hub)
        rotate(degrees = rotationAngle, pivot = center) {
            // Three NAB reel cutout windows (120 degrees apart)
            drawReelFlangeCutouts(center, hubRadius, outerRadius - 4.dp.toPx(), hubColor)

            // Center NAB Hub
            drawCircle(
                color = hubColor,
                radius = hubRadius,
                center = center
            )

            // Inner Terracotta / Orange Accent Hub
            val innerAccentRadius = hubRadius * 0.52f
            drawCircle(
                color = centerAccent,
                radius = innerAccentRadius,
                center = center
            )

            // Radial Strobe / Tachometer Ticks (from screenshot2.png)
            val tickCount = 24
            val innerTickR = innerAccentRadius * 0.7f
            val outerTickR = innerAccentRadius * 0.98f
            for (i in 0 until tickCount) {
                val angleDeg = (i * 360f / tickCount)
                val rad = Math.toRadians(angleDeg.toDouble())
                val start = Offset(
                    center.x + (innerTickR * cos(rad)).toFloat(),
                    center.y + (innerTickR * sin(rad)).toFloat()
                )
                val end = Offset(
                    center.x + (outerTickR * cos(rad)).toFloat(),
                    center.y + (outerTickR * sin(rad)).toFloat()
                )
                drawLine(
                    color = tickColor,
                    start = start,
                    end = end,
                    strokeWidth = 1.2.dp.toPx()
                )
            }

            // Three NAB Hub Drive Holes
            val pinDistance = hubRadius * 0.76f
            val pinRadius = 2.5.dp.toPx()
            for (i in 0 until 3) {
                val angleDeg = i * 120f + 30f
                val rad = Math.toRadians(angleDeg.toDouble())
                val pinCenter = Offset(
                    center.x + (pinDistance * cos(rad)).toFloat(),
                    center.y + (pinDistance * sin(rad)).toFloat()
                )
                drawCircle(
                    color = tickColor,
                    radius = pinRadius,
                    center = pinCenter
                )
            }

            // Center Spindle Pin
            drawCircle(
                color = tickColor,
                radius = 3.dp.toPx(),
                center = center
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.5f),
                radius = 1.5.dp.toPx(),
                center = center
            )
        }

        // 4. Stationary Outer Rim Seam
        drawCircle(
            color = spoolRim,
            radius = outerRadius,
            center = center,
            style = Stroke(width = 1.dp.toPx())
        )
    }
}

/**
 * Draws the 3 characteristic Revox / NAB cutout windows.
 */
private fun DrawScope.drawReelFlangeCutouts(
    center: Offset,
    innerR: Float,
    outerR: Float,
    color: Color
) {
    val windowInnerR = innerR * 1.15f
    val windowOuterR = outerR * 0.92f

    for (spoke in 0 until 3) {
        val baseAngle = spoke * 120f
        val angularWidth = 46f // degrees of cutout
        val startAngle = baseAngle + (120f - angularWidth) / 2f

        val path = Path().apply {
            val r1 = windowInnerR
            val r2 = windowOuterR

            val radStart = Math.toRadians(startAngle.toDouble())
            val radEnd = Math.toRadians((startAngle + angularWidth).toDouble())

            val p1 = Offset(center.x + (r1 * cos(radStart)).toFloat(), center.y + (r1 * sin(radStart)).toFloat())
            val p2 = Offset(center.x + (r2 * cos(radStart)).toFloat(), center.y + (r2 * sin(radStart)).toFloat())
            val p3 = Offset(center.x + (r2 * cos(radEnd)).toFloat(), center.y + (r2 * sin(radEnd)).toFloat())
            val p4 = Offset(center.x + (r1 * cos(radEnd)).toFloat(), center.y + (r1 * sin(radEnd)).toFloat())

            moveTo(p1.x, p1.y)
            lineTo(p2.x, p2.y)
            lineTo(p3.x, p3.y)
            lineTo(p4.x, p4.y)
            close()
        }

        drawPath(
            path = path,
            color = color.copy(alpha = 0.85f)
        )
    }
}
