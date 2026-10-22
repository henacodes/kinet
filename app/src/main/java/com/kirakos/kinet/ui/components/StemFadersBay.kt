package com.kirakos.kinet.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirakos.kinet.ui.theme.*

/**
 * Ultra-minimalist, high-precision horizontal stem faders.
 * Stripped of all visual clutter, cards, and bulky buttons.
 * Pure minimal hardware lines with generous touch targets.
 */
@Composable
fun StemFadersBay(
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
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Vocals Row
        MinimalStemFader(
            label = "Vocals",
            volume = vocalsVolume,
            isMuted = isVocalsMuted,
            onVolumeChange = onVocalsVolumeChange,
            onMuteToggle = onVocalsMuteToggle
        )

        // Music Row
        MinimalStemFader(
            label = "Music",
            volume = accompVolume,
            isMuted = isAccompMuted,
            onVolumeChange = onAccompVolumeChange,
            onMuteToggle = onAccompMuteToggle
        )
    }
}

@Composable
fun MinimalStemFader(
    label: String,
    volume: Float,
    isMuted: Boolean,
    onVolumeChange: (Float) -> Unit,
    onMuteToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Label (Clickable to toggle mute)
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            color = if (isMuted) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .width(52.dp)
                .clickable { onMuteToggle() }
        )

        // Ultra-minimal hairline slider with drag + tap support
        MinimalHairlineTrack(
            volume = if (isMuted) 0f else volume,
            isMuted = isMuted,
            onVolumeChange = onVolumeChange,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Level readout / Mute toggle pill
        Text(
            text = if (isMuted) "Muted" else "${(volume * 100).toInt()}%",
            fontSize = 11.sp,
            textAlign = TextAlign.End,
            color = if (isMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .width(42.dp)
                .clickable { onMuteToggle() }
        )
    }
}

@Composable
fun MinimalHairlineTrack(
    volume: Float,
    isMuted: Boolean,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val inactiveTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    // Dark prevailing color for the active rail line
    val activeTrackColor = if (isMuted) MaterialTheme.colorScheme.outline else HardwareCarbon
    // Terracotta accent color for the three /// knurled grip slashes
    val slashAccentColor = if (isMuted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier.pointerInput(isMuted) {
            if (!isMuted) {
                awaitEachGesture {
                    val thumbRadius = 6.dp.toPx()
                    val trackW = size.width - thumbRadius * 2
                    fun updateAt(px: Float) {
                        if (trackW > 0) {
                            val target = ((px - thumbRadius) / trackW).coerceIn(0f, 1f)
                            onVolumeChange(target)
                        }
                    }

                    // 1. Instant response on initial touch down (Tap)
                    val down = awaitFirstDown(requireUnconsumed = false)
                    updateAt(down.position.x)

                    // 2. Smooth continuous response as finger moves (Drag)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.pressed) {
                            change.consume()
                            updateAt(change.position.x)
                        } else {
                            break
                        }
                    }
                }
            }
        }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val centerY = h / 2f

            val trackHeight = 2.5.dp.toPx()
            val thumbRadius = 5.5.dp.toPx()
            val usableTravel = (w - thumbRadius * 2).coerceAtLeast(0f)
            val thumbCenterX = thumbRadius + (volume.coerceIn(0f, 1f) * usableTravel)

            // 1. Inactive Track (Subtle hairline rail)
            drawRoundRect(
                color = inactiveTrackColor,
                topLeft = Offset(thumbRadius, centerY - trackHeight / 2f),
                size = Size(usableTravel, trackHeight),
                cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
            )

            // 2. Active Track (Prevailing dark carbon line)
            if (thumbCenterX > thumbRadius && !isMuted) {
                drawRoundRect(
                    color = activeTrackColor,
                    topLeft = Offset(thumbRadius, centerY - trackHeight / 2f),
                    size = Size(thumbCenterX - thumbRadius, trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
                )
            }

            // 3. Tactile Knurled Grip Handle: dense, tightly clustered diagonal slashes (///) in Terracotta Accent
            val slashCount = 3
            val slashSpacing = 2.5.dp.toPx() // extremely close together
            val slashHeight = 13.dp.toPx()
            val slantOffset = 3.5.dp.toPx()   // steep, crisp diagonal angle
            val slashStroke = 1.8.dp.toPx()

            val totalGripWidth = (slashCount - 1) * slashSpacing + slantOffset
            val gripStartX = thumbCenterX - totalGripWidth / 2f

            for (i in 0 until slashCount) {
                val x = gripStartX + (i * slashSpacing)
                drawLine(
                    color = slashAccentColor,
                    start = Offset(x, centerY + slashHeight / 2f),
                    end = Offset(x + slantOffset, centerY - slashHeight / 2f),
                    strokeWidth = slashStroke,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}
