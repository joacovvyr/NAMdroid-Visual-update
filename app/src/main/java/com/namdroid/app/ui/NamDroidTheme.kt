package com.namdroid.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Carbon = Color(0xFF111418)
val Panel = Color(0xFF1A1F25)
val PanelRaised = Color(0xFF252B33)
val ElectricBlue = Color(0xFF19B5FE)
val SignalGreen = Color(0xFF55E6A5)
val WarmOrange = Color(0xFFFFA62B)
val MutedText = Color(0xFF98A3AE)

private val NamColors = darkColorScheme(
    primary = Color(0xFF8BE34F),
    secondary = SignalGreen,
    tertiary = WarmOrange,
    background = Carbon,
    surface = Panel,
    surfaceVariant = PanelRaised,
    onPrimary = Color.Black,
    onBackground = Color(0xFFF4F7FA),
    onSurface = Color(0xFFF4F7FA),
    onSurfaceVariant = Color(0xFFD2D8DE),
)

@Composable
fun NamDroidTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NamColors, shapes = Shapes(small = RoundedCornerShape(6.dp), medium = RoundedCornerShape(10.dp), large = RoundedCornerShape(12.dp)), content = content)
}
