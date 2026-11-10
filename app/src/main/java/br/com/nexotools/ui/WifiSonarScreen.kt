package br.com.nexotools.ui

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.flowWithLifecycle
import br.com.nexotools.MainViewModel
import br.com.nexotools.R
import br.com.nexotools.data.PreferenceState
import br.com.nexotools.domain.*
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@OptIn(ExperimentalLayoutApi::class)
@Composable fun SonarScreen(vm: MainViewModel) {
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val preference by vm.prefs.collectAsStateWithLifecycle()
    val prefs = preference ?: PreferenceState()
    var state by remember { mutableStateOf(NearbyScanState()) }
    var band by rememberSaveable { mutableStateOf("Todas") }
    var selected by remember { mutableStateOf<NearbyAccessPoint?>(null) }
    LaunchedEffect(vm, owner) { vm.nearby.watch().flowWithLifecycle(owner.lifecycle, Lifecycle.State.STARTED).collect { state = it } }
    PermissionPanel(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), "O Android exige localização precisa para informar redes Wi-Fi próximas. Os identificadores são processados localmente e não geram histórico.")
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Redes próximas")
                Text("${state.accessPoints.size}", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            }
            Hint(state.status)
            if (state.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(onClick = { vm.act { vm.nearby.requestScan() } }, enabled = state.permissionGranted && state.locationEnabled && state.wifiEnabled && !state.scanning && state.nextScanSeconds == 0, modifier = Modifier.fillMaxWidth().heightIn(min = Space.touch)) {
                Glyph(R.drawable.ic_wifi); Spacer(Modifier.width(Space.sm))
                Text(when { state.scanning -> "Aguardando Android"; state.nextScanSeconds > 0 -> "Consultar em ${state.nextScanSeconds}s"; else -> "Consultar redes" })
            }
            if (state.permissionGranted && !state.locationEnabled) TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } }) { Text("Abrir localização do Android") }
            if (!state.wifiEnabled) Hint("Ative o Wi-Fi para disponibilizar a consulta.")
            Hint(if (state.isCached) "Exibindo cache: cada leitura pode ter uma idade diferente." else "Protocolos anunciados pelo roteador; não é um teste de vulnerabilidade.")
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            listOf("Todas", "2,4 GHz", "5 GHz", "6 GHz").forEach { option -> FilterChip(selected = band == option, onClick = { band = option }, label = { Text(option, style = MaterialTheme.typography.labelSmall) }) }
        }
        val points = state.accessPoints.filter { band == "Todas" || it.frequencyMhz?.let(WifiMath::band) == band }
        Panel {
            SonarRadar(points)
            Hint("Anéis usam RSSI. Os ângulos apenas organizam redes; não mostram direção ou distância.")
            if (points.size > 20) Hint("Radar: 20 redes com sinal mais forte. A lista inclui todas as redes informadas.")
        }
        TextButton(onClick = { vm.act { vm.preferences.setHideSensitive(!prefs.hideSensitive) } }, modifier = Modifier.fillMaxWidth().heightIn(min = Space.touch)) {
            Glyph(R.drawable.ic_shield_check); Spacer(Modifier.width(Space.sm)); Text(if (prefs.hideSensitive) "Mostrar identificadores" else "Ocultar identificadores")
        }
        if (points.isEmpty()) Panel { Hint("Nenhuma rede informada nesta seleção. Consulte novamente quando o Android permitir.") }
        points.forEachIndexed { index, point ->
            val security = NearbyWifiMath.security(point.capabilities)
            Card(onClick = { selected = point }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md), verticalAlignment = Alignment.CenterVertically) {
                        Text("%02d".format(index + 1), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Column(Modifier.weight(1f)) {
                            Text(if (prefs.hideSensitive) "Rede ${index + 1}" else point.ssid?.filterNot(Char::isISOControl)?.take(64) ?: "SSID não divulgado", style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            Hint(point.manufacturer?.let { "Registro OUI: $it" } ?: if (MacVendorMath.isLocal(point.bssid)) "MAC local · fabricante não identificado" else "Fabricante não identificado")
                        }
                        Text(point.rssi?.let { "$it dBm" } ?: "RSSI N/D", style = MaterialTheme.typography.labelMedium)
                    }
                    Hint("${point.frequencyMhz?.let(WifiMath::band) ?: "Banda N/D"} · ${security.label}")
                    val age = NearbyWifiMath.ageSeconds(state.elapsedMillis, point.timestampMicros)
                    val wps = if (point.capabilities == null) "Anúncio WPS N/D" else if (security.wpsAdvertised) "WPS anunciado" else "WPS não anunciado"
                    Hint("$wps · ${age?.let { "observado há ${it}s" } ?: "idade não disponível"}")
                }
            }
        }
        Hint("Sem conexão aos roteadores, testes de PIN ou sondagem de falhas. As consultas não são salvas no histórico.")
    }
    selected?.let { point ->
        val security = NearbyWifiMath.security(point.capabilities)
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(if (prefs.hideSensitive) "Detalhes da rede" else point.ssid?.filterNot(Char::isISOControl)?.take(64) ?: "SSID não divulgado") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Metric("BSSID", if (prefs.hideSensitive) "Oculto" else point.bssid ?: "Não disponível")
                Metric("Registro OUI", point.manufacturer ?: if (MacVendorMath.isLocal(point.bssid)) "MAC local" else "Não identificado")
                Metric("RSSI", point.rssi?.let { "$it dBm" } ?: "Não disponível")
                Metric("Frequência", point.frequencyMhz?.let { "$it MHz" } ?: "Não disponível")
                Metric("Canal", point.frequencyMhz?.let(WifiMath::channel)?.toString() ?: "Não disponível")
                Metric("Segurança", security.label)
                Metric("WPS", if (point.capabilities == null) "Não disponível" else if (security.wpsAdvertised) "Anunciado" else "Não anunciado")
                Hint("WPS não anunciado não comprova que esteja desativado. OUI registra uma organização e não certifica marca ou modelo.")
                security.concern?.let { Hint(it) }
                Hint(security.recommendation)
                Hint("Avaliação baseada somente nos anúncios Wi-Fi; nenhuma vulnerabilidade foi comprovada.")
            }
        }, confirmButton = { TextButton(onClick = { selected = null }) { Text("Fechar") } })
    }
}

@Composable private fun SonarRadar(points: List<NearbyAccessPoint>) {
    val textMeasurer = rememberTextMeasurer(cacheSize = 24)
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    val accent = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(240.dp).semantics { contentDescription = "Radar ilustrativo de intensidade RSSI. Ângulo e posição não são direção nem distância." }) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = min(size.width, size.height) / 2 - 8.dp.toPx()
        drawCircle(accent.copy(alpha = 0.04f), radius, center)
        (1..4).forEach { ring -> drawCircle(grid, radius * ring / 4, center, style = Stroke(1.dp.toPx())) }
        drawLine(grid, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 1.dp.toPx())
        drawLine(grid, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 1.dp.toPx())
        points.take(20).forEachIndexed { index, point ->
            val signal = point.rssi ?: return@forEachIndexed
            val angle = -Math.PI / 2 + index * 2 * Math.PI / min(points.size, 20).coerceAtLeast(1)
            val radial = radius * (0.14 + 0.78 * (1 - WifiMath.quality(signal) / 100.0))
            val position = Offset(center.x + (cos(angle) * radial).toFloat(), center.y + (sin(angle) * radial).toFloat())
            drawCircle(accent, 11.dp.toPx(), position)
            val number = textMeasurer.measure((index + 1).toString(), style = labelStyle)
            drawText(number, topLeft = Offset(position.x - number.size.width / 2f, position.y - number.size.height / 2f))
        }
    }
}
