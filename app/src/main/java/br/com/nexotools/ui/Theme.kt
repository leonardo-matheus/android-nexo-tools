package br.com.nexotools.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.activity.compose.LocalActivity
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.nexotools.R

object Palette {
    val Lime = Color(0xFFD9ED92); val Leaf = Color(0xFFB5E48C)
    val Green = Color(0xFF99D98C); val Mint = Color(0xFF76C893)
    val Teal = Color(0xFF52B69A); val Aqua = Color(0xFF34A0A4)
    val Cyan = Color(0xFF168AAD); val Ocean = Color(0xFF1A759F)
    val Blue = Color(0xFF1E6091); val Navy = Color(0xFF184E77)
}
object Space { val xs=4.dp; val sm=8.dp; val md=12.dp; val lg=16.dp; val xl=24.dp; val xxl=32.dp; val touch=48.dp }
object Dimensions {
    val contentMax=720.dp; val onboardingMax=520.dp; val glyph=24.dp
    val panelRadius=20.dp; val tileMinHeight=148.dp
    val vialThickness=52.dp; val vialLength=160.dp; val planeMax=160.dp
    val horizonHeight=200.dp; val gaugeHeight=170.dp; val traceHeight=100.dp
    val stroke=2.dp; val needleStroke=4.dp; val gaugeStroke=12.dp
}
private val Dark = darkColorScheme(
    primary=Palette.Lime, onPrimary=Color(0xFF183326), secondary=Palette.Teal,
    primaryContainer=Palette.Navy, onPrimaryContainer=Palette.Lime,
    secondaryContainer=Color(0xFF234D50), onSecondaryContainer=Palette.Lime,
    tertiary=Palette.Aqua, onTertiary=Color(0xFF0B2030),
    background=Color(0xFF0B2030), onBackground=Color(0xFFF4F8F6),
    surface=Color(0xFF122D40), onSurface=Color(0xFFF4F8F6),
    surfaceVariant=Color(0xFF1B3C50), onSurfaceVariant=Color(0xFFA7BCC7),
    surfaceContainerLowest=Color(0xFF0B2030), surfaceContainerLow=Color(0xFF122D40),
    surfaceContainer=Color(0xFF122D40), surfaceContainerHigh=Color(0xFF1B3C50),
    surfaceContainerHighest=Color(0xFF25445A), surfaceTint=Palette.Lime,
    outline=Color(0xFF355264), outlineVariant=Color(0xFF355264), error=Color(0xFFFFB4AB)
)
private val Light = lightColorScheme(
    primary=Palette.Navy, onPrimary=Color.White, secondary=Color(0xFF26785F),
    primaryContainer=Palette.Lime, onPrimaryContainer=Palette.Navy,
    secondaryContainer=Color(0xFFE7F0EC), onSecondaryContainer=Palette.Navy,
    tertiary=Palette.Ocean, onTertiary=Color.White,
    background=Color(0xFFF4F8F6), onBackground=Color(0xFF16384B),
    surface=Color.White, onSurface=Color(0xFF16384B),
    surfaceVariant=Color(0xFFE7F0EC), onSurfaceVariant=Color(0xFF506B7A),
    surfaceContainerLowest=Color.White, surfaceContainerLow=Color(0xFFF4F8F6),
    surfaceContainer=Color(0xFFE7F0EC), surfaceContainerHigh=Color(0xFFE7F0EC),
    surfaceContainerHighest=Color(0xFFDCEAE4), surfaceTint=Palette.Navy,
    outline=Color(0xFFC7D9D5), outlineVariant=Color(0xFFC7D9D5), error=Color(0xFFB3261E)
)
val InstrumentStyle=TextStyle(fontFamily=FontFamily.Monospace,fontSize=44.sp,fontWeight=FontWeight.Medium,letterSpacing=(-2).sp)
private val Inter=FontFamily(Font(R.font.inter))
private val NexoTypography=Typography().let { base->base.copy(
    headlineLarge=base.headlineLarge.copy(fontFamily=Inter),
    headlineMedium=base.headlineMedium.copy(fontFamily=Inter),
    headlineSmall=base.headlineSmall.copy(fontFamily=Inter),
    titleLarge=base.titleLarge.copy(fontFamily=Inter),
    titleMedium=base.titleMedium.copy(fontFamily=Inter),
    titleSmall=base.titleSmall.copy(fontFamily=Inter),
    bodyLarge=base.bodyLarge.copy(fontFamily=Inter),
    bodyMedium=base.bodyMedium.copy(fontFamily=Inter),
    bodySmall=base.bodySmall.copy(fontFamily=Inter),
    labelLarge=base.labelLarge.copy(fontFamily=Inter),
    labelMedium=base.labelMedium.copy(fontFamily=Inter),
    labelSmall=base.labelSmall.copy(fontFamily=Inter)
) }
@Composable
fun NexoTheme(theme:String, content:@Composable ()->Unit) {
    val dark=when(theme){ "DARK"->true; "LIGHT"->false; else->isSystemInDarkTheme() }
    val activity=LocalActivity.current
    SideEffect {
        activity?.let {
            WindowCompat.getInsetsController(it.window,it.window.decorView).apply {
                isAppearanceLightStatusBars=!dark
                isAppearanceLightNavigationBars=!dark
            }
        }
    }
    val target=if(dark) Dark else Light
    val colors=target.copy(
        primary=animateColorAsState(target.primary,label="tema").value,
        background=animateColorAsState(target.background,label="fundo").value,
        surface=animateColorAsState(target.surface,label="superfície").value,
        onSurface=animateColorAsState(target.onSurface,label="texto").value
    )
    MaterialTheme(colorScheme=colors, typography=NexoTypography, content=content)
}
