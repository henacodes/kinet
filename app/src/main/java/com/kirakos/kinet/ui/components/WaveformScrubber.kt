package com.kirakos.kinet.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirakos.kinet.ChordSegment
import com.kirakos.kinet.ChordTransposer
import com.kirakos.kinet.ui.theme.*
import java.util.Locale

@Composable
fun WaveformLoopScrubber(
    modifier: Modifier = Modifier,
    waveform: FloatArray,
    positionMs: Long,
    durationMs: Long,
    loopStartMs: Long?,
    loopEndMs: Long?,
    isLooping: Boolean,
    chordSegments: List<ChordSegment> = emptyList(),
    pitchSemitones: Int = 0,
    showChords: Boolean = true,
    onSeekTo: (Long) -> Unit
) {
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val loopAProgress = if (loopStartMs != null && durationMs > 0) (loopStartMs.toFloat() / durationMs).coerceIn(0f, 1f) else null
    val loopBProgress = if (loopEndMs != null && durationMs > 0) (loopEndMs.toFloat() / durationMs).coerceIn(0f, 1f) else null

    val playedBarColor = HardwareCarbon
    val unplayedBarColor = MaterialTheme.colorScheme.outline
    val loopActiveBarColor = MaterialTheme.colorScheme.primary
    val bracketColor = MaterialTheme.colorScheme.primary
    val playheadColor = MaterialTheme.colorScheme.primary

    Column(modifier = modifier.fillMaxWidth()) {
        // Waveform & Loop Bracket Canvas
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .pointerInput(durationMs) {
                    awaitEachGesture {
                        fun updateSeek(px: Float) {
                            if (durationMs > 0 && size.width > 0) {
                                val targetFrac = (px / size.width).coerceIn(0f, 1f)
                                onSeekTo((targetFrac * durationMs).toLong())
                            }
                        }

                        // Immediate seek on touch down (Tap)
                        val down = awaitFirstDown(requireUnconsumed = false)
                        updateSeek(down.position.x)

                        // Smooth continuous scrub as finger drags across timeline (Drag)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.pressed) {
                                change.consume()
                                updateSeek(change.position.x)
                            } else {
                                break
                            }
                        }
                    }
                }
        ) {
            val width = size.width
            val height = size.height
            val centerY = height / 2f

            // 1. Center subtle baseline
            drawLine(
                color = unplayedBarColor.copy(alpha = 0.5f),
                start = Offset(0f, centerY),
                end = Offset(width, centerY),
                strokeWidth = 1.dp.toPx()
            )

            // 2. Waveform Bars
            val bars = if (waveform.isNotEmpty()) waveform else FloatArray(56) { 0.2f }
            val barCount = bars.size
            val barSpacing = width / barCount
            val barWidth = (barSpacing * 0.55f).coerceAtLeast(1.5.dp.toPx())

            for (i in 0 until barCount) {
                val barProgress = i.toFloat() / barCount
                val x = i * barSpacing + (barSpacing - barWidth) / 2f
                val barHeight = (bars[i] * height * 0.78f).coerceAtLeast(3.dp.toPx())
                val top = centerY - barHeight / 2f

                // Color calculation: is it inside loop? is it before playhead?
                val isInsideLoop = loopAProgress != null && loopBProgress != null &&
                        barProgress >= loopAProgress && barProgress <= loopBProgress
                val isPlayed = barProgress <= progress

                val color = when {
                    isInsideLoop -> loopActiveBarColor
                    isPlayed -> playedBarColor
                    else -> unplayedBarColor
                }

                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, top),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx())
                )
            }

            // 3. Loop Brackets [   ]
            // Left Bracket [ (Point A)
            if (loopAProgress != null) {
                val xA = width * loopAProgress
                val bracketH = height * 0.9f
                val bracketTop = centerY - bracketH / 2f
                val capLen = 7.dp.toPx()

                // Vertical line
                drawLine(
                    color = bracketColor,
                    start = Offset(xA, bracketTop),
                    end = Offset(xA, bracketTop + bracketH),
                    strokeWidth = 2.dp.toPx()
                )
                // Top cap ->
                drawLine(
                    color = bracketColor,
                    start = Offset(xA, bracketTop),
                    end = Offset(xA + capLen, bracketTop),
                    strokeWidth = 2.dp.toPx()
                )
                // Bottom cap ->
                drawLine(
                    color = bracketColor,
                    start = Offset(xA, bracketTop + bracketH),
                    end = Offset(xA + capLen, bracketTop + bracketH),
                    strokeWidth = 2.dp.toPx()
                )
            }

            // Right Bracket ] (Point B)
            if (loopBProgress != null) {
                val xB = width * loopBProgress
                val bracketH = height * 0.9f
                val bracketTop = centerY - bracketH / 2f
                val capLen = 7.dp.toPx()

                // Vertical line
                drawLine(
                    color = bracketColor,
                    start = Offset(xB, bracketTop),
                    end = Offset(xB, bracketTop + bracketH),
                    strokeWidth = 2.dp.toPx()
                )
                // Top cap <-
                drawLine(
                    color = bracketColor,
                    start = Offset(xB, bracketTop),
                    end = Offset(xB - capLen, bracketTop),
                    strokeWidth = 2.dp.toPx()
                )
                // Bottom cap <-
                drawLine(
                    color = bracketColor,
                    start = Offset(xB, bracketTop + bracketH),
                    end = Offset(xB - capLen, bracketTop + bracketH),
                    strokeWidth = 2.dp.toPx()
                )
            }

            // 4. Playhead Cursor (Discrete vertical indicator)
            val playheadX = width * progress
            drawLine(
                color = playheadColor,
                start = Offset(playheadX, 0f),
                end = Offset(playheadX, height),
                strokeWidth = 1.5.dp.toPx()
            )
            drawCircle(
                color = playheadColor,
                radius = 3.dp.toPx(),
                center = Offset(playheadX, centerY)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Time readouts row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatTime(positionMs),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Center status / loop badge
            Text(
                text = when {
                    isLooping -> "Loop active"
                    loopStartMs != null && loopEndMs == null -> "A set • Tap Set B"
                    else -> "Tap timeline to seek"
                },
                fontSize = 11.sp,
                color = if (isLooping) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = formatTime(durationMs),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
