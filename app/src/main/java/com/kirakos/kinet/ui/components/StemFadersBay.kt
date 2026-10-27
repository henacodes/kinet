package com.kirakos.kinet.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirakos.kinet.R
import com.kirakos.kinet.ui.theme.*

/**
 * Ultra-minimalist, high-precision horizontal stem faders.
 * Supports both 4-Stem Studio Console and 2-Stem Classic Console.
 * Featuring dedicated Solo (S) & Mute (M) toggles with analog LED styling.
 */
@Composable
fun StemFadersBay(
    modifier: Modifier = Modifier,
    isFourStem: Boolean = false,
    // Vocals
    vocalsVolume: Float,
    isVocalsMuted: Boolean,
    isVocalsSolo: Boolean = false,
    onVocalsVolumeChange: (Float) -> Unit,
    onVocalsMuteToggle: () -> Unit,
    onVocalsSoloToggle: () -> Unit = {},
    // Drums (4-stem)
    drumsVolume: Float = 1.0f,
    isDrumsMuted: Boolean = false,
    isDrumsSolo: Boolean = false,
    onDrumsVolumeChange: (Float) -> Unit = {},
    onDrumsMuteToggle: () -> Unit = {},
    onDrumsSoloToggle: () -> Unit = {},
    // Bass (4-stem)
    bassVolume: Float = 1.0f,
    isBassMuted: Boolean = false,
    isBassSolo: Boolean = false,
    onBassVolumeChange: (Float) -> Unit = {},
    onBassMuteToggle: () -> Unit = {},
    onBassSoloToggle: () -> Unit = {},
    // Other (4-stem)
    otherVolume: Float = 1.0f,
    isOtherMuted: Boolean = false,
    isOtherSolo: Boolean = false,
    onOtherVolumeChange: (Float) -> Unit = {},
    onOtherMuteToggle: () -> Unit = {},
    onOtherSoloToggle: () -> Unit = {},
    // Music (2-stem fallback)
    accompVolume: Float = 1.0f,
    isAccompMuted: Boolean = false,
    onAccompVolumeChange: (Float) -> Unit = {},
    onAccompMuteToggle: () -> Unit = {}
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (isFourStem) {
            // Track 1: Vocals
            MinimalStemFader(
                label = "Vocals",
                iconResId = R.drawable.ic_stem_vocals,
                volume = vocalsVolume,
                isMuted = isVocalsMuted,
                isSolo = isVocalsSolo,
                accentColor = StemVocalsCoral,
                onVolumeChange = onVocalsVolumeChange,
                onMuteToggle = onVocalsMuteToggle,
                onSoloToggle = onVocalsSoloToggle
            )

            // Track 2: Drums
            MinimalStemFader(
                label = "Drums",
                iconResId = R.drawable.ic_stem_drums,
                volume = drumsVolume,
                isMuted = isDrumsMuted,
                isSolo = isDrumsSolo,
                accentColor = StemDrumsAmber,
                onVolumeChange = onDrumsVolumeChange,
                onMuteToggle = onDrumsMuteToggle,
                onSoloToggle = onDrumsSoloToggle
            )

            // Track 3: Bass
            MinimalStemFader(
                label = "Bass",
                iconResId = R.drawable.ic_stem_bass,
                volume = bassVolume,
                isMuted = isBassMuted,
                isSolo = isBassSolo,
                accentColor = StemBassTeal,
                onVolumeChange = onBassVolumeChange,
                onMuteToggle = onBassMuteToggle,
                onSoloToggle = onBassSoloToggle
            )

            // Track 4: Other / Melody
            MinimalStemFader(
                label = "Other",
                iconResId = R.drawable.ic_stem_other,
                volume = otherVolume,
                isMuted = isOtherMuted,
                isSolo = isOtherSolo,
                accentColor = StemOtherLavender,
                onVolumeChange = onOtherVolumeChange,
                onMuteToggle = onOtherMuteToggle,
                onSoloToggle = onOtherSoloToggle
            )
        } else {
            // 2-Stem Classic Mode: Vocals
            MinimalStemFader(
                label = "Vocals",
                iconResId = R.drawable.ic_stem_vocals,
                volume = vocalsVolume,
                isMuted = isVocalsMuted,
                isSolo = false,
                accentColor = StemVocalsCoral,
                onVolumeChange = onVocalsVolumeChange,
                onMuteToggle = onVocalsMuteToggle
            )

            // 2-Stem Classic Mode: Music
            MinimalStemFader(
                label = "Music",
                iconResId = R.drawable.ic_stem_music,
                volume = accompVolume,
                isMuted = isAccompMuted,
                isSolo = false,
                accentColor = MaterialTheme.colorScheme.primary,
                onVolumeChange = onAccompVolumeChange,
                onMuteToggle = onAccompMuteToggle
            )
        }
    }
}

@Composable
fun MinimalStemFader(
    label: String,
    iconResId: Int? = null,
    volume: Float,
    isMuted: Boolean,
    isSolo: Boolean = false,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    onVolumeChange: (Float) -> Unit,
    onMuteToggle: () -> Unit,
    onSoloToggle: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon + Label
        Row(
            modifier = Modifier
                .width(68.dp)
                .clickable { onMuteToggle() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (iconResId != null) {
                Icon(
                    painter = painterResource(id = iconResId),
                    contentDescription = label,
                    tint = if (isMuted) accentColor.copy(alpha = 0.35f) else accentColor,
                    modifier = Modifier.size(13.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(RoundedCornerShape(2.5.dp))
                        .background(if (isMuted) Color.Transparent else accentColor)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                fontSize = 11.5.sp,
                fontWeight = if (isSolo) FontWeight.Bold else FontWeight.Normal,
                color = if (isMuted) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.onSurface
            )
        }

        // Ultra-minimal hairline slider
        MinimalHairlineTrack(
            volume = if (isMuted) 0f else volume,
            isMuted = isMuted,
            accentColor = accentColor,
            onVolumeChange = onVolumeChange,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Solo Button [S]
        if (onSoloToggle != null) {
            Box(
                modifier = Modifier
                    .size(width = 20.dp, height = 20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (isSolo) StemSoloActive
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                    .clickable { onSoloToggle() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "S",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSolo) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
        }

        // Mute Button [M]
        Box(
            modifier = Modifier
                .size(width = 20.dp, height = 20.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (isMuted) StemMuteActive
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
                .clickable { onMuteToggle() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "M",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (isMuted) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Level readout
        Text(
            text = if (isMuted) "0%" else "${(volume * 100).toInt()}%",
            fontSize = 10.5.sp,
            textAlign = TextAlign.End,
            color = if (isMuted) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(32.dp)
        )
    }
}

@Composable
fun MinimalHairlineTrack(
    volume: Float,
    isMuted: Boolean,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val inactiveTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    // Dark prevailing color for the active rail line
    val activeTrackColor = if (isMuted) MaterialTheme.colorScheme.outline else HardwareCarbon
    // Accent color for the three /// knurled grip slashes
    val slashAccentColor = if (isMuted) MaterialTheme.colorScheme.outline else accentColor

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

            // 3. Tactile Knurled Grip Handle: dense, tightly clustered diagonal slashes (///) in Stem Accent
            val slashCount = 3
            val slashSpacing = 2.5.dp.toPx()
            val slashHeight = 13.dp.toPx()
            val slantOffset = 3.5.dp.toPx()
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
