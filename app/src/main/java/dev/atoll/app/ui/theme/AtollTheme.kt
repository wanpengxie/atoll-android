package dev.atoll.app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** The web has a light palette; terminal surfaces own their dark palette. */
object AtollColors {
    val Canvas = Color(0xFFD4C5A9)
    val Rail = Color(0xFFF5F0E8)
    val ChannelRail = Color(0xFFFBF8F1)
    val Workspace = Color.White
    val Input = Color(0xFFFFFEFD)
    val MutedSurface = Color(0xFFF3EDE2)
    val Selected = Color(0xFFE9E0CF)
    val Code = Color(0xFFF5F1E8)
    val Line = Color(0xFFECE7DD)
    val ControlLine = Color(0xFFDCD3C4)
    val Text = Color(0xFF2E2419)
    val Muted = Color(0xFF6F6252)
    val Accent = Color(0xFFE4002B)
    val Agent = Color(0xFF0072CE)
    val Online = Color(0xFF08783D)
    val Warning = Color(0xFF8D5B0A)
    val WarningSurface = Color(0xFFFBF0D8)
}
object AtollSpacing {
    val Page = 16.dp
    val Timeline = 10.dp
    val Gap = 8.dp
    val TouchTarget = 44.dp
}
private val palette = lightColorScheme(
    primary = AtollColors.Accent, onPrimary = Color.White,
    secondary = AtollColors.Agent, secondaryContainer = AtollColors.Selected,
    onSecondaryContainer = AtollColors.Text, primaryContainer = AtollColors.Accent.copy(alpha = .09f),
    onPrimaryContainer = AtollColors.Accent, surfaceContainer = AtollColors.Rail, background = AtollColors.Workspace,
    surface = AtollColors.Workspace, onSurface = AtollColors.Text,
    onBackground = AtollColors.Text, surfaceVariant = AtollColors.MutedSurface,
    onSurfaceVariant = AtollColors.Muted, outline = AtollColors.ControlLine,
    error = AtollColors.Accent,
)
private val type = Typography(
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 23.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp),
)
@Composable
fun AtollTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = palette, typography = type,
        shapes = Shapes(small = RoundedCornerShape(6.dp), medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(12.dp)),
        content = { CompositionLocalProvider(LocalContentColor provides AtollColors.Text, content = content) })
}
