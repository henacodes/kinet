package com.kirakos.kinet.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirakos.kinet.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun DualStemRotaryBay(
    modifier: Modifier = Modifier,
    vocalsVolume: Float,
    isVocalsMuted: Boolean,
    onVocalsVolumeChange: (Float) -> Unit,
    onVocalsMuteToggle: () -> Unit,
    accompVolume: Float,
    isAccompMuted: Boolean,
    onAccompVolumeChange: (Float) -> Unit,
    onAccompMuteToggle: () -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Vocals Rotary Plate
        RotaryKnobCard(
            modifier = Modifier.weight(1f),
            label = "Vocals",
            volume = vocalsVolume,
            isMuted = isVocalsMuted,
            onVolumeChange = onVocalsVolumeChange,
            onMuteToggle = onVocalsMuteToggle
        )

        // Accompaniment Rotary Plate
        RotaryKnobCard(
            modifier = Modifier.weight(1f),
            label = "Music",
            volume = accompVolume,
            isMuted = isAccompMuted,
            onVolumeChange = onAccompVolumeChange,
            onMuteToggle = onAccompMuteToggle
        )
    }
}

@Composable
fun RotaryKnobCard(
    modifier: Modifier = Modifier,
    label: String,
    volume: Float,
    isMuted: Boolean,
    onVolumeChange: (Float) -> Unit,
    onMuteToggle: () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 14.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Label Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (isMuted) "Muted" else "${(volume * 100).toInt()}%",
                    fontSize = 11.sp,
                    color = if (isMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // The Physical Rotary Dial
            RotaryDial(
                volume = if (isMuted) 0f else volume,
                isMuted = isMuted,
                onVolumeChange = onVolumeChange,
                onMuteToggle = onMuteToggle,
                modifier = Modifier.size(96.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Subtitle / Mute Tap Indicator
            Text(
                text = if (isMuted) "Tap knob to unmute" else "Drag to adjust",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun RotaryDial(
    modifier: Modifier = Modifier,
    volume: Float,
    isMuted: Boolean,
    onVolumeChange: (Float) -> Unit,
    onMuteToggle: () -> Unit
) {
    val dialColor = HardwareCarbon
    val centerHubColor = HardwareCarbonLight
    val tickColor = CreamOffWhite
    val accentColor = MaterialTheme.colorScheme.primary
    val rimColor = MaterialTheme.colorScheme.outline
    val errorColor = MaterialTheme.colorScheme.error

    // Knob rotation maps 0.0f..1.0f -> -135deg..+135deg (270 degrees total sweep)
    val angleDeg = -135f + (volume.coerceIn(0f, 1f) * 270f)

    Box(
        modifier = modifier
            .pointerInput(isMuted) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    // Vertical drag: dragging up increases volume, dragging down decreases
                    val delta = -dragAmount.y / 200f
                    onVolumeChange((volume + delta).coerceIn(0f, 1f))
                }
            }
            .clickable { onMuteToggle() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val outerRadius = size.minDimension / 2f - 2.dp.toPx()
            val innerHubRadius = outerRadius * 0.42f

            // 1. Arc graduation tick marks (around knob circumference)
            val startSweep = 135f
            val totalSweep = 270f
            val steps = 18
            for (i in 0..steps) {
                val tickAngle = startSweep + (i.toFloat() / steps) * totalSweep
                val rad = Math.toRadians(tickAngle.toDouble())
                val innerR = outerRadius + 1.dp.toPx()
                val outerR = outerRadius + 4.dp.toPx()
                val start = Offset(center.x + (innerR * cos(rad)).toFloat(), center.y + (innerR * sin(rad)).toFloat())
                val end = Offset(center.x + (outerR * cos(rad)).toFloat(), center.y + (outerR * sin(rad)).toFloat())
                drawLine(
                    color = rimColor,
                    start = start,
                    end = end,
                    strokeWidth = 1.dp.toPx()
                )
            }

            // 2. Main Dial Body (Matte carbon cylinder)
            drawCircle(
                color = dialColor,
                radius = outerRadius,
                center = center
            )

            // Outer dial bevel ring
            drawCircle(
                color = rimColor,
                radius = outerRadius,
                center = center,
                style = Stroke(width = 1.2.dp.toPx())
            )

            // 3. Center Inset Disc
            drawCircle(
                color = centerHubColor,
                radius = innerHubRadius,
                center = center
            )
            drawCircle(
                color = rimColor.copy(alpha = 0.5f),
                radius = innerHubRadius,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )

            // 4. Rotating Notch / Indicator Line (Rotates with volume)
            val indicatorRad = Math.toRadians((angleDeg - 90f).toDouble())
            val notchStartR = innerHubRadius * 0.9f
            val notchEndR = outerRadius * 0.88f
            val notchStart = Offset(
                center.x + (notchStartR * cos(indicatorRad)).toFloat(),
                center.y + (notchStartR * sin(indicatorRad)).toFloat()
            )
            val notchEnd = Offset(
                center.x + (notchEndR * cos(indicatorRad)).toFloat(),
                center.y + (notchEndR * sin(indicatorRad)).toFloat()
            )

            val indicatorColor = if (isMuted) errorColor else accentColor
            drawLine(
                color = indicatorColor,
                start = notchStart,
                end = notchEnd,
                strokeWidth = 3.dp.toPx()
            )

            // Notch tip cap circle
            drawCircle(
                color = indicatorColor,
                radius = 2.dp.toPx(),
                center = notchEnd
            )

            // Center LED dot (Mute status)
            if (isMuted) {
                drawCircle(
                    color = errorColor,
                    radius = 3.dp.toPx(),
                    center = center
                )
            }
        }
    }
}
