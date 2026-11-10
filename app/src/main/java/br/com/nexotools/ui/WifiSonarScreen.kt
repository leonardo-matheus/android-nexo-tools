package br.com.nexotools.ui

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

private data class SonarSelection(val point: NearbyAccessPoint, val ordinal: Int)
private object SonarDimensions {
    val radarHeight = 168.dp
    val cardRadius = 12.dp
    val padding = 12.dp
    val glyph = 20.dp
}
private enum class ProtectionTone { OPEN, CAUTION, MODERN, UNKNOWN }
private data class SonarColours(val modern: Color, val amber: Color, val open: Color, val unknown: Color)
@Composable private fun sonarColours(): SonarColours {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.3f
    return SonarColours(if (dark) Palette.Green else MaterialTheme.colorScheme.secondary,
        if (dark) Color(0xFFE8B654) else Color(0xFF8D6300), MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onSurfaceVariant)
}
private fun protectionTone(point: NearbyAccessPoint): ProtectionTone {
    val security = NearbyWifiMath.security(point.capabilities)
    return when {
        security.label == "Rede aberta anunciada" -> ProtectionTone.OPEN
        security.concern != null -> ProtectionTone.CAUTION
        security.label.startsWith("WPA2") || security.label.startsWith("WPA3") || security.label.startsWith("OWE") -> ProtectionTone.MODERN
        else -> ProtectionTone.UNKNOWN
    }
}
private fun protectionColour(tone: ProtectionTone, colours: SonarColours): Color = when (tone) {
    ProtectionTone.OPEN -> colours.open
    ProtectionTone.CAUTION -> colours.amber
    ProtectionTone.MODERN -> colours.modern
    ProtectionTone.UNKNOWN -> colours.unknown
}
private fun networkName(point: NearbyAccessPoint, ordinal: Int, hidden: Boolean): String =
    if (hidden) "Rede $ordinal" else point.ssid?.filterNot(Char::isISOControl)?.take(64)?.takeIf { it.isNotBlank() } ?: "SSID não divulgado"

@OptIn(ExperimentalLayoutApi::class)
@Composable fun SonarScreen(vm: MainViewModel) {
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val preference by vm.prefs.collectAsStateWithLifecycle()
    val prefs = preference ?: PreferenceState()
    var state by remember { mutableStateOf(NearbyScanState()) }
    var band by rememberSaveable { mutableStateOf("Todas") }
    var selected by remember { mutableStateOf<SonarSelection?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    LaunchedEffect(vm, owner) {
        vm.nearby.watch().flowWithLifecycle(owner.lifecycle, Lifecycle.State.STARTED).collect { state = it }
    }
    PermissionPanel(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        "O Android exige localização precisa para consultar redes Wi-Fi próximas. Os identificadores são processados localmente.")
    val points = state.accessPoints.withIndex().filter { band == "Todas" || it.value.frequencyMhz?.let(WifiMath::band) == band }
    val available = state.permissionGranted && state.locationEnabled && state.wifiEnabled
    val currentSelection = selected?.takeIf { available }?.let { previous ->
        state.accessPoints.withIndex().firstOrNull { (_, point) ->
            if (previous.point.bssid != null) previous.point.bssid.equals(point.bssid, ignoreCase = true)
            else point.bssid == null && point.ssid == previous.point.ssid && point.frequencyMhz == previous.point.frequencyMhz
        }?.let { SonarSelection(it.value, it.index + 1) }
    }
    LaunchedEffect(selected, state.accessPoints, available) { if (selected != currentSelection) selected = currentSelection }
    val latestAge = points.mapNotNull { NearbyWifiMath.ageSeconds(state.elapsedMillis, it.value.timestampMicros) }.minOrNull()
    val canScan = state.permissionGranted && state.locationEnabled && state.wifiEnabled && !state.scanning && state.nextScanSeconds == 0
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            Column(Modifier.weight(1f)) {
                Text("${points.size} redes", style = MaterialTheme.typography.titleMedium)
                Text(when {
                    state.scanning -> "Consultando…"
                    !state.permissionGranted -> "Sem permissão"
                    !state.wifiEnabled -> "Wi-Fi desligado"
                    !state.locationEnabled -> "Localização desligada"
                    latestAge != null -> "Última leitura: ${if (latestAge < 60) "${latestAge}s" else "${latestAge / 60}min"}${if (state.isCached) " · cache" else ""}"
                    state.isCached -> "Cache do Android"
                    else -> "Idade não informada"
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { vm.act { vm.preferences.setHideSensitive(!prefs.hideSensitive) } }) {
                Icon(androidx.compose.ui.res.painterResource(if (prefs.hideSensitive) R.drawable.ic_eye else R.drawable.ic_eye_off),
                    contentDescription = if (prefs.hideSensitive) "Mostrar identificadores das redes" else "Ocultar identificadores das redes",
                    tint = if (prefs.hideSensitive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { vm.act { vm.nearby.requestScan() } }, enabled = canScan) {
                when {
                    state.scanning -> CircularProgressIndicator(Modifier.size(SonarDimensions.glyph).semantics { contentDescription = "Consulta Wi-Fi em andamento" }, strokeWidth = 2.dp)
                    state.nextScanSeconds > 0 -> Text("${state.nextScanSeconds}s", style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.clearAndSetSemantics { contentDescription = "Nova consulta disponível em ${state.nextScanSeconds} segundos" })
                    else -> Glyph(R.drawable.ic_rotate_ccw, "Consultar redes Wi-Fi")
                }
            }
        }
        if (state.permissionGranted && !state.locationEnabled) {
            TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } }, modifier = Modifier.heightIn(min = Space.touch)) { Text("Ativar localização no Android") }
        }
        if (state.permissionGranted && !state.wifiEnabled) {
            TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } }, modifier = Modifier.heightIn(min = Space.touch)) { Text("Abrir Wi-Fi no Android") }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            listOf("Todas", "2,4 GHz", "5 GHz", "6 GHz").forEach { option ->
                FilterChip(selected = band == option, onClick = { band = option }, label = { Text(option) })
            }
        }
        Card(onClick = { showInfo = true }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Radar ilustrativo de RSSI. Toque nos pontos para abrir a rede ou no restante para entender a consulta." },
            shape = RoundedCornerShape(SonarDimensions.cardRadius), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Box(Modifier.fillMaxWidth()) {
                SonarRadar(points, onSelect = { selected = it }, onInfo = { showInfo = true })
                Glyph(R.drawable.ic_chevron_right, modifier = Modifier.align(Alignment.TopEnd).padding(Space.sm))
            }
        }
        if (points.isEmpty()) Text("Nenhuma rede nesta seleção", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        points.forEach { (index, point) ->
            AccessPointCard(point, index + 1, prefs.hideSensitive) { selected = SonarSelection(point, index + 1) }
        }
    }
    currentSelection?.let { selection ->
        AccessPointDialog(selection, prefs.hideSensitive, state.elapsedMillis) { selected = null }
    }
    if (showInfo) AlertDialog(onDismissRequest = { showInfo = false }, title = { Text("Consulta e radar") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            DetailField("Estado da consulta", state.status)
            Text("O radar usa RSSI para posicionar as redes nos anéis. Os ângulos somente organizam os pontos; não medem direção ou distância. São desenhadas até 20 redes com RSSI disponível.")
            Text("As cores representam anúncios: vermelho para rede aberta; âmbar para WPS ou modos antigos/transição; verde para proteção moderna; cinza quando não identificada.")
            Text("O Android limita a frequência das consultas. O NEXO respeita o intervalo e pode exibir cache; a idade de cada leitura aparece nos detalhes.")
            Text("Proteção e WPS são anúncios do roteador. WPS não anunciado não comprova desativação; nenhum anúncio comprova uma brecha ou a segurança completa.")
            Text("Identificadores e registros OUI são processados localmente, sem consultas de fabricante pela internet e sem histórico de scans.")
        }
    }, confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Fechar") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun AccessPointCard(point: NearbyAccessPoint, ordinal: Int, hidden: Boolean, onClick: () -> Unit) {
    val security = NearbyWifiMath.security(point.capabilities)
    val colours = sonarColours()
    val tone = protectionTone(point)
    val protection = security.label.removeSuffix(" anunciado").let { label -> when (label) {
        "Rede aberta anunciada" -> "Aberta"
        "WPA2 / RSN" -> "WPA2"
        "WPA2 / WPA3" -> "WPA2/WPA3"
        "WPA1 / WPA2" -> "WPA1/WPA2"
        "Proteção não identificada", "Segurança não disponível" -> "Proteção N/D"
        else -> label
    } } + if (point.capabilities?.contains("TKIP", ignoreCase = true) == true) " · TKIP" else ""
    val securityColour = protectionColour(tone, colours)
    val protectionIcon = when (tone) {
        ProtectionTone.OPEN -> R.drawable.ic_lock_open
        ProtectionTone.CAUTION, ProtectionTone.UNKNOWN -> R.drawable.ic_shield_alert
        ProtectionTone.MODERN -> R.drawable.ic_lock_keyhole
    }
    Card(onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(SonarDimensions.cardRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(SonarDimensions.padding), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text(networkName(point, ordinal, hidden), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Glyph(R.drawable.ic_chevron_right)
            }
            point.manufacturer?.takeIf { it.isNotBlank() }?.let { company ->
                Text(company, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                TinyMetric(R.drawable.ic_signal, point.rssi?.let { "$it dBm" } ?: "Sinal N/D",
                    point.rssi?.let { "Sinal de $it dBm" } ?: "Sinal não disponível",
                    if (point.rssi?.let(WifiMath::quality)?.let { it >= 60 } == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                val channel = point.frequencyMhz?.let(WifiMath::channel)
                val band = point.frequencyMhz?.let(WifiMath::band)
                val shortBand = when (band) { "2,4 GHz" -> "2,4G"; "5 GHz" -> "5G"; "6 GHz" -> "6G"; else -> "N/D" }
                TinyMetric(R.drawable.ic_sliders_horizontal, "$shortBand · C${channel ?: "?"}",
                    "Banda ${band ?: "não disponível"}, canal ${channel ?: "não disponível"}", MaterialTheme.colorScheme.onSurfaceVariant)
                TinyMetric(protectionIcon, protection, security.label + if (point.capabilities?.contains("TKIP", ignoreCase = true) == true) ", TKIP anunciado" else "", securityColour)
                val spokenWps = when { point.capabilities == null -> "Anúncio WPS não disponível"; security.wpsAdvertised -> "WPS anunciado"; else -> "WPS não anunciado" }
                TinyMetric(R.drawable.ic_key_round, when { point.capabilities == null -> "WPS ?"; security.wpsAdvertised -> "WPS !"; else -> "WPS —" }, spokenWps,
                    if (security.wpsAdvertised) colours.amber else colours.unknown)
            }
        }
    }
}

@Composable private fun TinyMetric(icon: Int, text: String, spoken: String, colour: Color) {
    Row(Modifier.clearAndSetSemantics { contentDescription = spoken }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        Icon(androidx.compose.ui.res.painterResource(icon), null, Modifier.size(SonarDimensions.glyph), tint = colour)
        Text(text, style = MaterialTheme.typography.labelSmall, color = colour)
    }
}

@Composable private fun AccessPointDialog(selection: SonarSelection, hidden: Boolean, nowMillis: Long, onDismiss: () -> Unit) {
    val point = selection.point
    val security = NearbyWifiMath.security(point.capabilities)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(networkName(point, selection.ordinal, hidden)) }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            DetailField("BSSID", if (hidden) "Oculto" else point.bssid ?: "Não disponível")
            point.manufacturer?.takeIf { it.isNotBlank() }?.let { DetailField("Organização registrada no OUI", it) }
            DetailField("Sinal", point.rssi?.let { "$it dBm" } ?: "Não disponível")
            DetailField("Banda / frequência", point.frequencyMhz?.let { "${WifiMath.band(it) ?: "Banda N/D"} · $it MHz" } ?: "Não disponível")
            DetailField("Canal", point.frequencyMhz?.let(WifiMath::channel)?.toString() ?: "Não disponível")
            DetailField("Proteção anunciada", security.label)
            DetailField("WPS", if (point.capabilities == null) "Não disponível" else if (security.wpsAdvertised) "Anunciado" else "Não anunciado")
            DetailField("Idade da leitura", NearbyWifiMath.ageSeconds(nowMillis, point.timestampMicros)?.let { "$it segundos" } ?: "Não disponível")
            Text("WPS não anunciado não comprova desativação. OUI identifica uma organização, sem garantir a marca ou modelo do equipamento.")
            security.concern?.let { Text(it) }
            Text(security.recommendation)
            Text("Esta avaliação usa somente anúncios Wi-Fi. Nenhuma vulnerabilidade foi testada ou comprovada.")
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } })
}

@Composable private fun DetailField(label: String, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private data class RadarPoint(val selection: SonarSelection, val position: Offset)
private fun radarPositions(points: List<IndexedValue<NearbyAccessPoint>>, width: Float, height: Float, padding: Float): List<RadarPoint> {
    val plotted = points.filter { it.value.rssi != null }.take(20)
    val center = Offset(width / 2, height / 2)
    val radius = (min(width, height) / 2 - padding).coerceAtLeast(0f)
    return plotted.mapIndexed { index, item ->
        val angle = -Math.PI / 2 + index * 2 * Math.PI / plotted.size.coerceAtLeast(1)
        val radial = radius * (0.14 + 0.78 * (1 - WifiMath.quality(requireNotNull(item.value.rssi)) / 100.0))
        RadarPoint(SonarSelection(item.value, item.index + 1), Offset(center.x + (cos(angle) * radial).toFloat(), center.y + (sin(angle) * radial).toFloat()))
    }
}

@Composable private fun SonarRadar(points: List<IndexedValue<NearbyAccessPoint>>, onSelect: (SonarSelection) -> Unit, onInfo: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val colours = sonarColours()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val select by rememberUpdatedState(onSelect)
    val info by rememberUpdatedState(onInfo)
    Canvas(Modifier.fillMaxWidth().height(SonarDimensions.radarHeight).pointerInput(points) {
        detectTapGestures { tap ->
            val plotted = radarPositions(points, size.width.toFloat(), size.height.toFloat(), Space.sm.toPx())
            val nearest = plotted.minByOrNull { (it.position - tap).getDistance() }
            if (nearest != null && (nearest.position - tap).getDistance() <= 24.dp.toPx()) select(nearest.selection) else info()
        }
    }) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = min(size.width, size.height) / 2 - Space.sm.toPx()
        drawCircle(accent.copy(alpha = 0.04f), radius, center)
        repeat(4) { ring -> drawCircle(grid, radius * (ring + 1) / 4, center, style = Stroke(1.dp.toPx())) }
        drawLine(grid, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 1.dp.toPx())
        drawLine(grid, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 1.dp.toPx())
        radarPositions(points, size.width, size.height, Space.sm.toPx()).forEach { plotted ->
            val point = plotted.selection.point
            val tone = protectionTone(point)
            val colour = if (tone != ProtectionTone.OPEN && NearbyWifiMath.security(point.capabilities).wpsAdvertised) colours.amber else protectionColour(tone, colours)
            drawCircle(colour, 5.dp.toPx(), plotted.position)
        }
    }
}
