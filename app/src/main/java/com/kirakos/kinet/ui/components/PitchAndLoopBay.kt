package com.kirakos.kinet.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirakos.kinet.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Clean, card-less Pitch & A/B Loop Bay:
 * - Left: 3D Cylindrical Pitch Roller with magnetic line snapping, open surface, longer lines, closer line spacing.
 * - Right: Precision Segmented Loop Capsule [ A | Off | B ] in single seamless hardware housing.
 */
@Composable
fun PitchAndLoopBay(
    modifier: Modifier = Modifier,
    pitchSemitones: Int,
    onPitchChange: (Int) -> Unit,
    loopStartMs: Long?,
    loopEndMs: Long?,
    isLooping: Boolean,
    onSetLoopA: () -> Unit,
    onSetLoopB: () -> Unit,
    onClearLoop: () -> Unit,
    onJumpToA: () -> Unit = {}
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Left Column: Magnetic 3D Pitch Roller
        PitchRollerControl(
            pitchSemitones = pitchSemitones,
            onPitchChange = onPitchChange,
            modifier = Modifier.weight(1f)
        )

        // Right Column: Seamless Segmented Loop Capsule
        LoopControlSection(
            loopStartMs = loopStartMs,
            loopEndMs = loopEndMs,
            isLooping = isLooping,
            onSetLoopA = onSetLoopA,
            onSetLoopB = onSetLoopB,
            onClearLoop = onClearLoop,
            onJumpToA = onJumpToA,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Composed 3D Cylindrical Pitch Roller:
 * - Plain open stone background (zero top/bottom border lines).
 * - Longer, elegant vertical lines in theme's dark carbon color.
 * - Closer spacing between lines for a fine-vernier vernier drum feel.
 * - Magnetic line snapping: center pointer always firmly locks onto a line.
 * - Positive on the right, negative on the left.
 */
@Composable
fun PitchRollerControl(
    pitchSemitones: Int,
    onPitchChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val animatedSemitone = remember { Animatable(pitchSemitones.toFloat()) }
    var dragSemitone by remember { mutableFloatStateOf(pitchSemitones.toFloat()) }
    var isDragging by remember { mutableStateOf(false) }

    val currentPitchSemitones by rememberUpdatedState(pitchSemitones)
    val currentOnPitchChange by rememberUpdatedState(onPitchChange)

    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val vibrator = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
                ?: context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    // Physical haptic tick / buzz on each new semitone level
    val triggerTickBuzz = remember(vibrator, haptic) {
        {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(18L)
                }
            } catch (_: Exception) {}
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    LaunchedEffect(pitchSemitones) {
        if (!isDragging) {
            dragSemitone = pitchSemitones.toFloat()
            if ((animatedSemitone.targetValue - pitchSemitones).let { kotlin.math.abs(it) > 0.05f }) {
                animatedSemitone.animateTo(
                    targetValue = pitchSemitones.toFloat(),
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                )
            }
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Label row: "pitch" on left, semitone readout on right (no "st" suffix, tap to reset to 0)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "pitch",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = if (pitchSemitones == 0) "0" else if (pitchSemitones > 0) "+$pitchSemitones" else "$pitchSemitones",
                fontSize = 12.sp,
                fontWeight = if (pitchSemitones != 0) FontWeight.Normal else FontWeight.Light,
                color = if (pitchSemitones != 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clickable {
                    triggerTickBuzz()
                    isDragging = false
                    dragSemitone = 0f
                    coroutineScope.launch {
                        animatedSemitone.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                        currentOnPitchChange(0)
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Narrow, composed roller on the open stone plain (no top/bottom borders)
        Box(
            modifier = Modifier
                .width(130.dp)
                .height(38.dp),
            contentAlignment = Alignment.Center
        ) {
            val displayPosition = if (isDragging) dragSemitone else animatedSemitone.value

            MagneticRollerCanvas(
                currentSemitone = displayPosition,
                onDragStart = {
                    isDragging = true
                },
                onDragAbsolute = { totalDx, initialSemitone ->
                    isDragging = true
                    val pxPerSemitone = 38f
                    val newTarget = (initialSemitone + (totalDx / pxPerSemitone)).coerceIn(-6f, 6f)
                    val oldInt = dragSemitone.roundToInt()
                    val newInt = newTarget.roundToInt().coerceIn(-6, 6)
                    dragSemitone = newTarget

                    if (newInt != oldInt) {
                        triggerTickBuzz()
                    }

                    if (newInt != currentPitchSemitones) {
                        currentOnPitchChange(newInt)
                    }
                },
                onDragEnd = {
                    isDragging = false
                    val nearestInt = dragSemitone.roundToInt().coerceIn(-6, 6)
                    dragSemitone = nearestInt.toFloat()
                    if (nearestInt != currentPitchSemitones) {
                        triggerTickBuzz()
                        currentOnPitchChange(nearestInt)
                    }
                    coroutineScope.launch {
                        animatedSemitone.snapTo(dragSemitone)
                        animatedSemitone.animateTo(
                            targetValue = nearestInt.toFloat(),
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMedium
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun MagneticRollerCanvas(
    currentSemitone: Float,
    onDragStart: () -> Unit,
    onDragAbsolute: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val darkLineColor = HardwareCarbon
    val centerAccentColor = MaterialTheme.colorScheme.primary

    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragAbsolute by rememberUpdatedState(onDragAbsolute)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val latestCurrentSemitone by rememberUpdatedState(currentSemitone)

    Box(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val startX = down.position.x
                val initialSemitone = latestCurrentSemitone

                currentOnDragStart()

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.pressed) {
                        change.consume()
                        val totalDx = change.position.x - startX
                        currentOnDragAbsolute(totalDx, initialSemitone)
                    } else {
                        currentOnDragEnd()
                        break
                    }
                }
            }
        }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val centerX = w / 2f
            val centerY = h / 2f
            val radius = w / 2f

            // Zero top and bottom borders (clean, open floating surface)

            // Angular spacing per semitone: closer distance between lines
            val angleStep = (Math.PI / 10.5).toFloat()

            // Draw semitone lines from -6 to +6 (positive to right, negative to left)
            for (st in -6..6) {
                val relAngle = (st - currentSemitone) * angleStep

                // Visible on front hemisphere (-PI/2 to +PI/2)
                if (relAngle in -1.55f..1.55f) {
                    // Cosine foreshortening: x = center + R * sin(angle)
                    val projX = centerX + (radius * 0.94f * sin(relAngle))
                    val shade = (cos(relAngle)).coerceIn(0f, 1f)
                    val alpha = (shade * shade).coerceIn(0.08f, 1f)

                    // Longer, elegant line height (~82% - 98% of height)
                    val lineH = (h * (0.80f + 0.18f * shade)).coerceAtLeast(8.dp.toPx())
                    val topY = centerY - lineH / 2f

                    val isZero = st == 0
                    val isNearestSelected = kotlin.math.abs(st - currentSemitone) < 0.15f

                    val color = when {
                        isNearestSelected -> centerAccentColor.copy(alpha = alpha)
                        isZero -> darkLineColor.copy(alpha = (alpha * 0.95f).coerceIn(0f, 1f))
                        else -> darkLineColor.copy(alpha = (alpha * 0.65f).coerceIn(0f, 1f))
                    }
                    val strokeW = if (isNearestSelected || isZero) 2.dp.toPx() else 1.2.dp.toPx()

                    drawLine(
                        color = color,
                        start = Offset(projX, topY),
                        end = Offset(projX, topY + lineH),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )
                }
            }

            // Center Precision Pointer: clean terracotta pips at top & bottom pointing to the locked line
            val notchH = 4.dp.toPx()
            drawLine(
                color = centerAccentColor,
                start = Offset(centerX, 0f),
                end = Offset(centerX, notchH),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                color = centerAccentColor,
                start = Offset(centerX, h - notchH),
                end = Offset(centerX, h),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * Unified Segmented Hardware Loop Capsule:
 * - Single seamless housing [ A | B | To A | Off ] with flush tactile segments.
 * - Replaces clunky separate buttons with an integrated hardware module.
 */
@Composable
fun LoopControlSection(
    loopStartMs: Long?,
    loopEndMs: Long?,
    isLooping: Boolean,
    onSetLoopA: () -> Unit,
    onSetLoopB: () -> Unit,
    onClearLoop: () -> Unit,
    onJumpToA: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isAnyLoopSet = loopStartMs != null || loopEndMs != null
    val accentColor = MaterialTheme.colorScheme.primary
    val housingBg = MaterialTheme.colorScheme.surfaceVariant
    val borderColor = if (isLooping) accentColor else MaterialTheme.colorScheme.outline
    val dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)

    Column(modifier = modifier) {
        // Label row: "loop" on left, status on right
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "loop",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = when {
                    isLooping -> "active"
                    loopStartMs != null && loopEndMs == null -> "a set"
                    else -> "off"
                },
                fontSize = 12.sp,
                fontWeight = if (isLooping) FontWeight.Normal else FontWeight.Light,
                color = if (isLooping) accentColor else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Unified Segmented Capsule: [ A | B | To A | Off ]
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp),
            shape = RoundedCornerShape(10.dp),
            color = housingBg,
            border = BorderStroke(1.dp, borderColor)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Segment [ A ]
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(if (loopStartMs != null) accentColor else Color.Transparent)
                        .clickable { onSetLoopA() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "A",
                        fontSize = 12.sp,
                        fontWeight = if (loopStartMs != null) FontWeight.Medium else FontWeight.Normal,
                        color = if (loopStartMs != null) CreamOffWhite else MaterialTheme.colorScheme.onSurface
                    )
                }

                // Vertical Hairline Seam
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight(0.55f)
                        .background(dividerColor)
                )

                // Segment [ B ]
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(if (loopEndMs != null) accentColor else Color.Transparent)
                        .clickable { onSetLoopB() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "B",
                        fontSize = 12.sp,
                        fontWeight = if (loopEndMs != null) FontWeight.Medium else FontWeight.Normal,
                        color = if (loopEndMs != null) CreamOffWhite else MaterialTheme.colorScheme.onSurface
                    )
                }

                // Vertical Hairline Seam
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight(0.55f)
                        .background(dividerColor)
                )

                // Segment [ To A ]
                val canJumpToA = loopStartMs != null
                Box(
                    modifier = Modifier
                        .weight(1.2f)
                        .fillMaxHeight()
                        .clickable(enabled = canJumpToA) { onJumpToA() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "To A",
                        fontSize = 11.sp,
                        fontWeight = if (canJumpToA) FontWeight.Medium else FontWeight.Normal,
                        color = if (canJumpToA) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    )
                }

                // Vertical Hairline Seam
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight(0.55f)
                        .background(dividerColor)
                )

                // Segment [ Off ]
                Box(
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight()
                        .clickable(enabled = isAnyLoopSet) { onClearLoop() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Off",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                        color = if (isAnyLoopSet) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    )
                }
            }
        }
    }
}
