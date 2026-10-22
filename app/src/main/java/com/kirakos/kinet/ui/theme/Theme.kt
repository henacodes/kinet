package com.kirakos.kinet.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val KinetColorScheme = lightColorScheme(
    primary = WarmTerracotta,
    onPrimary = CreamOffWhite,
    primaryContainer = TerracottaContainer,
    onPrimaryContainer = StoneTextPrimary,
    secondary = StoneTextMuted,
    onSecondary = CreamOffWhite,
    secondaryContainer = RecessedPlateColor,
    onSecondaryContainer = StoneTextPrimary,
    tertiary = TerracottaSoft,
    onTertiary = HardwareCarbon,
    background = WarmStoneBackground,
    onBackground = StoneTextPrimary,
    surface = ElevatedStoneSheet,
    onSurface = StoneTextPrimary,
    surfaceVariant = RecessedPlateColor,
    onSurfaceVariant = StoneTextMuted,
    surfaceContainer = RecessedPlateColor,
    surfaceContainerHigh = ElevatedStoneSheet,
    outline = StoneBorder,
    outlineVariant = StoneBorder,
    error = SignalRed,
    onError = CreamOffWhite
)

val KinetShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp)
)

@Composable
fun KinetTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = KinetColorScheme,
        shapes = KinetShapes,
        typography = Typography,
        content = content
    )
}
