package com.calvinatman.xfiles.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val XBlack = Color(0xFF000000)
val XCard = Color(0xFF141414)
val XBorder = Color(0xFF2A2A2A)
val XWhite = Color(0xFFFFFFFF)
val XGrey = Color(0xFF9A9A9A)

private val XColors = darkColorScheme(
    primary = XWhite,
    onPrimary = XBlack,
    background = XBlack,
    onBackground = XWhite,
    surface = XCard,
    onSurface = XWhite,
    onSurfaceVariant = XGrey,
    outline = XBorder,
    secondaryContainer = Color(0xFF262626),
    onSecondaryContainer = XWhite
)

@Composable
fun XFilesTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = XColors, content = content)
}
