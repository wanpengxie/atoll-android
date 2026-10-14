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

/**
 * Ink on neutral paper with one lagoon accent. Lagoon marks selection, presence and links;
 * the status hues mark state only, and a completed turn is not coloured at all.
 * Terminal surfaces own their dark palette.
 */
object AtollColors {
    // Grounds and surfaces
    val Ground = Color(0xFFF6F6F2)
    val Canvas = Ground
    val ChannelRail = Ground
    val Surface = Color.White
    val Workspace = Surface
    val Rail = Surface
    val Input = Surface
    val Raised = Color(0xFFFBFBF8)
    val Subtle = Color(0xFFF1F1EC)
    val Sunken = Color(0xFFEBEBE5)
    val MutedSurface = Sunken
    val Code = Subtle
    val Line = Color(0xFFE5E5E0)
    val LineSoft = Color(0xFFEFEFEA)
    val ControlLine = Color(0xFFD4D4CD)

    // Text
    val Ink = Color(0xFF15171A)
    val Text = Ink
    val Body = Color(0xFF3B3F46)
    val Muted = Color(0xFF5C6169)
    /** Decoration only (the "#" prefix, chevrons); below 4.5:1 for text. */
    val Faint = Color(0xFF8B9097)

    // Accent: selection, presence, links
    val Lagoon = Color(0xFF0F766E)
    val LagoonText = Color(0xFF0F5F59)
    val LagoonSurface = Color(0xFFE2F1EE)
    val Online = Lagoon
    val Selected = LagoonSurface

    // States
    val Running = Color(0xFF1D5FD1)
    val RunningText = Color(0xFF1D4FB0)
    val RunningInk = Color(0xFF1D3F80)
    val RunningSurface = Color(0xFFE9F0FC)
    val RunningTrack = Color(0xFFC9D8F5)
    /** Historical name: links and running states. */
    val Agent = RunningText
    val Warning = Color(0xFF8A4B00)
    val WarningInk = Color(0xFF5A3A05)
    val WarningActionInk = Color(0xFF6B3A00)
    val Star = Color(0xFFC98A1A)
    val WarningSurface = Color(0xFFFBF1DF)
    val WarningLine = Color(0xFFE7C98F)
    val Danger = Color(0xFFB42318)
    val DangerText = Color(0xFF9F1F14)
    val DangerSurface = Color(0xFFFCECEA)
    val DangerLine = Color(0xFFF0C7C2)
    /** Historical name: every remaining use is an error or failure. */
    val Accent = Danger
    val Scrim = Color(0x995E6064)
}

/** Agents get a rounded square, people a circle; the tint comes from the actor id. */
object AtollAvatarTints {
    data class Tint(val background: Color, val content: Color)
    private val tints = listOf(
        Tint(Color(0xFF4F46E5), Color.White),
        Tint(Color(0xFFFBF1DF), Color(0xFF8A4B00)),
        Tint(Color(0xFFE2F1EE), Color(0xFF0F5F59)),
        Tint(Color(0xFFE7E3F8), Color(0xFF3F3591)),
        Tint(Color(0xFFFCECEA), Color(0xFF9F1F14)),
        Tint(Color(0xFFE9F0FC), Color(0xFF1D4FB0)),
    )
    val Self = Tint(AtollColors.Ink, Color.White)
    fun of(id: String): Tint = tints[Math.floorMod(id.hashCode(), tints.size)]
}

object AtollSpacing {
    val Page = 16.dp
    val Timeline = 10.dp
    val Gap = 8.dp
    val TouchTarget = 44.dp
}

object AtollShapes {
    val Button = RoundedCornerShape(12.dp)
    val Row = RoundedCornerShape(14.dp)
    val Card = RoundedCornerShape(16.dp)
    val TurnCard = RoundedCornerShape(18.dp)
    val Field = RoundedCornerShape(22.dp)
    val Sheet = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val Pill = RoundedCornerShape(50)
}

private val palette = lightColorScheme(
    primary = AtollColors.Ink, onPrimary = Color.White,
    primaryContainer = AtollColors.LagoonSurface, onPrimaryContainer = AtollColors.LagoonText,
    secondary = AtollColors.Lagoon, onSecondary = Color.White,
    secondaryContainer = AtollColors.LagoonSurface, onSecondaryContainer = AtollColors.LagoonText,
    tertiary = AtollColors.RunningText, tertiaryContainer = AtollColors.RunningSurface,
    background = AtollColors.Ground, onBackground = AtollColors.Ink,
    surface = AtollColors.Surface, onSurface = AtollColors.Ink,
    surfaceVariant = AtollColors.Sunken, onSurfaceVariant = AtollColors.Muted,
    surfaceContainerLowest = AtollColors.Surface, surfaceContainerLow = AtollColors.Raised,
    surfaceContainer = AtollColors.Surface, surfaceContainerHigh = AtollColors.Surface,
    surfaceContainerHighest = AtollColors.Subtle,
    outline = AtollColors.ControlLine, outlineVariant = AtollColors.Line,
    error = AtollColors.Danger, onError = Color.White,
    errorContainer = AtollColors.DangerSurface, onErrorContainer = AtollColors.DangerText,
    scrim = AtollColors.Scrim,
)

/** System sans covers both Latin and CJK (Noto Sans CJK on Android). Body text is 15sp minimum. */
private val sans = FontFamily.SansSerif
val AtollMono = FontFamily.Monospace
private val type = Typography(
    headlineSmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = sans, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = sans, fontSize = 15.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontFamily = sans, fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)

@Composable
fun AtollTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = palette, typography = type,
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(18.dp), extraLarge = RoundedCornerShape(24.dp)),
        content = { CompositionLocalProvider(LocalContentColor provides AtollColors.Ink, content = content) })
}
