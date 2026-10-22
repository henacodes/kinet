package com.kirakos.kinet.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val KinetColorScheme = darkColorScheme(
    primary = WarmTerracotta,
    onPrimary = CarbonBlack,
    primaryContainer = TerracottaContainer,
    onPrimaryContainer = TerracottaSoft,
    secondary = GraphiteMuted,
    onSecondary = LuminescentWhite,
    secondaryContainer = DarkElevatedGray,
    onSecondaryContainer = LuminescentWhite,
    tertiary = TerracottaSoft,
    onTertiary = CarbonBlack,
    background = DeepObsidian,
    onBackground = LuminescentWhite,
    surface = DarkCharcoalSheet,
    onSurface = LuminescentWhite,
    surfaceVariant = DarkElevatedGray,
    onSurfaceVariant = GraphiteMuted,
    surfaceContainer = DarkCharcoalSheet,
    surfaceContainerHigh = DarkElevatedGray,
    outline = DarkOutlineTint,
    outlineVariant = DarkOutlineTint
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
