package com.kirakos.kinet.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
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
import com.kirakos.kinet.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

/**
 * A realistic, industrial-grade rotating Vinyl Turntable with an animated mechanical Tonearm.
 * - When isPlaying is false: tonearm swings back to its rest cradle off the record.
 * - When isPlaying is true: tonearm swings smoothly onto the spinning record and tracks the groove.
 */
@Composable
fun VinylTurntable(
    modifier: Modifier = Modifier,
    isPlaying: Boolean,
    progress: Float // 0f to 1f
) {
    // 1. Continuous smooth rotation of the vinyl disc when playing (33 1/3 RPM feel)
    val infiniteTransition = rememberInfiniteTransition(label = "vinylSpin")
    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "vinylRotation"
    )

    val currentDiscRotation = if (isPlaying) spinAngle else (progress * 720f) % 360f

    // 2. Tonearm angle:
    // Rest position: -18f (parked in cradle to the right of the platter)
    // Active playback: sweeps smoothly from 12f (outer rim) to 34f (inner label) based on song progress
    val targetTonearmAngle = if (!isPlaying) {
        -18f
    } else {
        12f + (progress.coerceIn(0f, 1f) * 22f)
    }

    val animatedTonearmAngle by animateFloatAsState(
        targetValue = targetTonearmAngle,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "tonearmSwing"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(205.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // Platter center is placed slightly left to give comfortable room for the tonearm assembly on the right
            val platterCenter = Offset(width * 0.44f, height * 0.52f)
            val platterRadius = (height * 0.46f).coerceAtMost(width * 0.38f)

            // Tonearm pivot is anchored at top-right
            val tonearmPivot = Offset(platterCenter.x + platterRadius * 1.05f, platterCenter.y - platterRadius * 0.78f)

            // 1. Turntable Recessed Platter Well / Bed
            drawCircle(
                color = HardwareCarbonLight.copy(alpha = 0.5f),
                radius = platterRadius + 4.dp.toPx(),
                center = platterCenter
            )
            drawCircle(
                color = StoneBorder,
                radius = platterRadius + 4.dp.toPx(),
                center = platterCenter,
                style = Stroke(width = 1.dp.toPx())
            )

            // 2. Rotating Vinyl Record Platter
            rotate(degrees = currentDiscRotation, pivot = platterCenter) {
                drawVinylRecord(
                    center = platterCenter,
                    radius = platterRadius,
                    accentColor = WarmTerracotta,
                    strobeTickColor = CreamOffWhite
                )
            }

            // 3. Physical Mechanical Tonearm
            drawTonearm(
                pivot = tonearmPivot,
                platterCenter = platterCenter,
                angleDeg = animatedTonearmAngle,
                armLength = platterRadius * 1.35f,
                isResting = !isPlaying
            )
        }
    }
}

/**
 * Draws the vinyl record disc with micro-grooves, run-out lead-in, and tachometer strobe center label.
 */
private fun DrawScope.drawVinylRecord(
    center: Offset,
    radius: Float,
    accentColor: Color,
    strobeTickColor: Color
) {
    // A. Vinyl record body (deep matte carbon black)
    drawCircle(
        color = HardwareCarbon,
        radius = radius,
        center = center
    )

    // B. Outer lead-in groove rim
    drawCircle(
        color = Color(0xFF282523),
        radius = radius - 2.dp.toPx(),
        center = center,
        style = Stroke(width = 2.dp.toPx())
    )

    // C. Micro-grooves (concentric sheen rings of vinyl grooves)
    val grooveSteps = 8
    val innerGrooveR = radius * 0.42f
    val outerGrooveR = radius * 0.94f
    val stepSize = (outerGrooveR - innerGrooveR) / grooveSteps

    for (i in 0 until grooveSteps) {
        val r = innerGrooveR + i * stepSize
        val grooveAlpha = if (i % 2 == 0) 0.12f else 0.06f
        drawCircle(
            color = Color.White.copy(alpha = grooveAlpha),
            radius = r,
            center = center,
            style = Stroke(width = 1.dp.toPx())
        )
    }

    // Inner run-out groove ring
    drawCircle(
        color = Color.Black.copy(alpha = 0.4f),
        radius = innerGrooveR + 2.dp.toPx(),
        center = center,
        style = Stroke(width = 2.5.dp.toPx())
    )

    // D. Center Vinyl Label Disc (Warm Terracotta from screenshot2.png)
    val labelRadius = radius * 0.36f
    drawCircle(
        color = accentColor,
        radius = labelRadius,
        center = center
    )

    // Center Label border seam
    drawCircle(
        color = Color.Black.copy(alpha = 0.3f),
        radius = labelRadius,
        center = center,
        style = Stroke(width = 1.2.dp.toPx())
    )

    // E. Radial Tachometer Strobe Ticks (48 crisp ticks around center label)
    val tickCount = 48
    val innerTickR = labelRadius * 0.62f
    val outerTickR = labelRadius * 0.94f
    for (i in 0 until tickCount) {
        val deg = i * (360f / tickCount)
        val rad = Math.toRadians(deg.toDouble())
        val start = Offset(
            center.x + (innerTickR * cos(rad)).toFloat(),
            center.y + (innerTickR * sin(rad)).toFloat()
        )
        val end = Offset(
            center.x + (outerTickR * cos(rad)).toFloat(),
            center.y + (outerTickR * sin(rad)).toFloat()
        )
        val isMajor = i % 4 == 0
        drawLine(
            color = if (isMajor) strobeTickColor else strobeTickColor.copy(alpha = 0.5f),
            start = start,
            end = end,
            strokeWidth = if (isMajor) 1.5.dp.toPx() else 1.dp.toPx()
        )
    }

    // F. Inner Label Center Ring
    drawCircle(
        color = Color(0xFF2A1C16),
        radius = labelRadius * 0.35f,
        center = center
    )

    // G. Center Spindle Hole (metallic center pin)
    drawCircle(
        color = Color(0xFFD6C8BA),
        radius = 4.5.dp.toPx(),
        center = center
    )
    drawCircle(
        color = Color.Black,
        radius = 2.5.dp.toPx(),
        center = center
    )
}

/**
 * Draws the realistic mechanical Tonearm with:
 * - Gimbal / pivot base with brass/aluminum ring
 * - Counterweight at the back
 * - S-shaped aluminum tonearm wand
 * - Cartridge headshell and stylus needle at the tip
 */
private fun DrawScope.drawTonearm(
    pivot: Offset,
    platterCenter: Offset,
    angleDeg: Float,
    armLength: Float,
    isResting: Boolean
) {
    // 1. Tonearm Resting Cradle (drawn on the deck surface)
    val cradleAngleRad = Math.toRadians(-18.0)
    val cradlePos = Offset(
        pivot.x + (armLength * 0.78f * cos(cradleAngleRad + Math.PI / 2)).toFloat(),
        pivot.y + (armLength * 0.78f * sin(cradleAngleRad + Math.PI / 2)).toFloat()
    )
    // Cradle notch
    drawCircle(
        color = HardwareCarbon,
        radius = 4.dp.toPx(),
        center = cradlePos
    )
    drawCircle(
        color = StoneBorder,
        radius = 4.dp.toPx(),
        center = cradlePos,
        style = Stroke(width = 1.dp.toPx())
    )

    // 2. Rotating Tonearm Assembly
    rotate(degrees = angleDeg, pivot = pivot) {
        val wandColor = CreamOffWhite
        val metalAccent = HardwareCarbonLight
        val headshellColor = HardwareCarbon

        // A. Counterweight (protruding behind pivot at 180° opposite direction)
        val weightLen = 18.dp.toPx()
        val weightWidth = 10.dp.toPx()
        drawRoundRect(
            color = metalAccent,
            topLeft = Offset(pivot.x - weightWidth / 2f, pivot.y - weightLen - 4.dp.toPx()),
            size = androidx.compose.ui.geometry.Size(weightWidth, weightLen),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx())
        )
        // Counterweight stripe
        drawLine(
            color = StoneBorder,
            start = Offset(pivot.x - weightWidth / 2f, pivot.y - weightLen / 2f - 4.dp.toPx()),
            end = Offset(pivot.x + weightWidth / 2f, pivot.y - weightLen / 2f - 4.dp.toPx()),
            strokeWidth = 1.5.dp.toPx()
        )

        // B. Pivot Gimbal Base (concentric metallic pivot rings)
        drawCircle(
            color = HardwareCarbon,
            radius = 12.dp.toPx(),
            center = pivot
        )
        drawCircle(
            color = StoneBorder,
            radius = 12.dp.toPx(),
            center = pivot,
            style = Stroke(width = 1.5.dp.toPx())
        )
        drawCircle(
            color = metalAccent,
            radius = 7.dp.toPx(),
            center = pivot
        )
        drawCircle(
            color = CreamOffWhite,
            radius = 2.5.dp.toPx(),
            center = pivot
        )

        // C. S-shaped Tonearm Wand
        // The wand starts at pivot and extends downwards in the local rotated space
        val wandPath = Path().apply {
            moveTo(pivot.x, pivot.y + 6.dp.toPx())
            // First gentle curve outwards
            cubicTo(
                pivot.x + 8.dp.toPx(), pivot.y + armLength * 0.35f,
                pivot.x - 6.dp.toPx(), pivot.y + armLength * 0.70f,
                pivot.x, pivot.y + armLength * 0.88f
            )
            // Final segment heading to headshell
            lineTo(pivot.x - 4.dp.toPx(), pivot.y + armLength)
        }

        // Draw wand tube with highlight
        drawPath(
            path = wandPath,
            color = wandColor,
            style = Stroke(width = 2.8.dp.toPx())
        )
        drawPath(
            path = wandPath,
            color = Color.Black.copy(alpha = 0.25f),
            style = Stroke(width = 1.dp.toPx())
        )

        // D. Headshell Cartridge (the angled block at the end carrying the needle)
        val tipPos = Offset(pivot.x - 4.dp.toPx(), pivot.y + armLength)
        val shellWidth = 8.dp.toPx()
        val shellLength = 16.dp.toPx()

        val headshellPath = Path().apply {
            moveTo(tipPos.x - shellWidth / 2f, tipPos.y)
            lineTo(tipPos.x + shellWidth / 2f, tipPos.y)
            lineTo(tipPos.x + shellWidth * 0.35f, tipPos.y + shellLength)
            lineTo(tipPos.x - shellWidth * 0.35f, tipPos.y + shellLength)
            close()
        }

        drawPath(
            path = headshellPath,
            color = headshellColor
        )
        drawPath(
            path = headshellPath,
            color = StoneBorder,
            style = Stroke(width = 1.dp.toPx())
        )

        // E. Stylus / Needle tip indicator (Terracotta point on the headshell)
        val needleTip = Offset(tipPos.x, tipPos.y + shellLength)
        drawCircle(
            color = WarmTerracotta,
            radius = 2.dp.toPx(),
            center = needleTip
        )
    }
}
