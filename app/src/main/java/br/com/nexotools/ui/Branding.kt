package br.com.nexotools.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.nexotools.R

object Brand {
    val Turquoise = Color(0xFF45D0B1)
    val Teal = Color(0xFF126D62)
    val Sky = Color(0xFF55B6DE)
    val Ocean = Color(0xFF1F5B7A)
    val Ink = Color(0xFF0B2030)
}

@Composable internal fun BrandHeader() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        Image(painterResource(R.drawable.nexo_mark), contentDescription = null, modifier = Modifier.size(32.dp, 25.dp))
        Text("NEXO TOOLS", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable internal fun BrandLoadingScreen() {
    val logo = if (MaterialTheme.colorScheme.background.luminance() < .5f) R.drawable.nexo_logo else R.drawable.nexo_logo_light
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Image(painterResource(logo), "NEXO Tools", Modifier.size(240.dp))
            LinearProgressIndicator(Modifier.width(96.dp))
        }
    }
}

/** Only the new destination is composed, so outgoing hardware is released immediately. */
@Composable internal fun ColumnScope.RouteEntrance(content: @Composable ColumnScope.() -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(220, easing = LinearOutSlowInEasing)) }
    Column(Modifier.weight(1f).fillMaxWidth().graphicsLayer {
        alpha = .7f + .3f * progress.value
        translationY = 12.dp.toPx() * (1f - progress.value)
    }, content = content)
}
