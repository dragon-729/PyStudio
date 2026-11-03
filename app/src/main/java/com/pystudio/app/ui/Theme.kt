package com.pystudio.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

data class SyntaxColors(
    val bg: Color, val text: Color, val keyword: Color, val builtin: Color, val function: Color,
    val cls: Color, val string: Color, val number: Color, val comment: Color, val decorator: Color,
    val self: Color, val gutter: Color, val cursor: Color, val error: Color, val warning: Color,
    val info: Color, val accent: Color
)

val DarkSyntax = SyntaxColors(
    bg = Color(0xFF1E1E1E), text = Color(0xFFD4D4D4), keyword = Color(0xFF569CD6), builtin = Color(0xFF4EC9B0),
    function = Color(0xFFDCDCAA), cls = Color(0xFF4EC9B0), string = Color(0xFFCE9178), number = Color(0xFFB5CEA8),
    comment = Color(0xFF6A9955), decorator = Color(0xFFDCDCAA), self = Color(0xFF9CDCFE), gutter = Color(0xFF6E7681),
    cursor = Color(0xFFAEAFAD), error = Color(0xFFF14C4C), warning = Color(0xFFCCA700), info = Color(0xFF3794FF),
    accent = Color(0xFF3794FF)
)

val LightSyntax = SyntaxColors(
    bg = Color(0xFFFFFFFF), text = Color(0xFF1F1F1F), keyword = Color(0xFF0000FF), builtin = Color(0xFF267F99),
    function = Color(0xFF795E26), cls = Color(0xFF267F99), string = Color(0xFFA31515), number = Color(0xFF098658),
    comment = Color(0xFF008000), decorator = Color(0xFF795E26), self = Color(0xFF001080), gutter = Color(0xFF8A8A8A),
    cursor = Color(0xFF000000), error = Color(0xFFE51400), warning = Color(0xFFBF8803), info = Color(0xFF1A85FF),
    accent = Color(0xFF0066B8)
)

val LocalSyntax = staticCompositionLocalOf { DarkSyntax }

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF4DA3FF), onPrimary = Color(0xFF00203F),
    primaryContainer = Color(0xFF0E639C), onPrimaryContainer = Color.White,
    secondary = Color(0xFF4EC9B0), onSecondary = Color(0xFF00201A),
    secondaryContainer = Color(0xFF2D3B38), onSecondaryContainer = Color(0xFFCFF2EA),
    tertiary = Color(0xFFDCDCAA), error = Color(0xFFF48771), onError = Color(0xFF3A0A00),
    background = Color(0xFF1E1E1E), onBackground = Color(0xFFD4D4D4),
    surface = Color(0xFF252526), onSurface = Color(0xFFD4D4D4),
    surfaceVariant = Color(0xFF2D2D30), onSurfaceVariant = Color(0xFFA6A6A6),
    outline = Color(0xFF4A4A4F), outlineVariant = Color(0xFF3C3C3C),
    surfaceContainerLowest = Color(0xFF181818), surfaceContainerLow = Color(0xFF202021),
    surfaceContainer = Color(0xFF252526), surfaceContainerHigh = Color(0xFF2D2D30),
    surfaceContainerHighest = Color(0xFF37373D)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0066B8), onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E6F8), onPrimaryContainer = Color(0xFF00305A),
    secondary = Color(0xFF0F7B6C), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5EFEA), onSecondaryContainer = Color(0xFF00201A),
    tertiary = Color(0xFF795E26), error = Color(0xFFD32F2F), onError = Color.White,
    background = Color(0xFFFFFFFF), onBackground = Color(0xFF1F1F1F),
    surface = Color(0xFFF3F3F3), onSurface = Color(0xFF1F1F1F),
    surfaceVariant = Color(0xFFE8E8E8), onSurfaceVariant = Color(0xFF616161),
    outline = Color(0xFFBDBDBD), outlineVariant = Color(0xFFDADADA),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF7F7F7),
    surfaceContainer = Color(0xFFF3F3F3), surfaceContainerHigh = Color(0xFFEBEBEB),
    surfaceContainerHighest = Color(0xFFE3E3E3)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun PyStudioTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) { "light" -> false; "system" -> isSystemInDarkTheme(); else -> true }
    val scheme = if (dark) DarkScheme else LightScheme
    MaterialTheme(colorScheme = scheme, shapes = AppShapes) {
        CompositionLocalProvider(
            LocalSyntax provides (if (dark) DarkSyntax else LightSyntax),
            LocalTextSelectionColors provides TextSelectionColors(scheme.primary, scheme.primary.copy(alpha = 0.35f)),
            content = content
        )
    }
}
