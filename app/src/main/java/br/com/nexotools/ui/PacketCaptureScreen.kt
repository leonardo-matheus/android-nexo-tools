package br.com.nexotools.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.nexotools.MainViewModel
import br.com.nexotools.domain.CapturedPacket
import br.com.nexotools.domain.PacketDecoder
import br.com.nexotools.domain.PacketProtocol
import br.com.nexotools.domain.PcapCodec
import br.com.nexotools.network.PacketCaptureService
import br.com.nexotools.network.PacketCaptureStore
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class CaptureApp(val name: String, val packageName: String)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun PacketCaptureScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val state by PacketCaptureStore.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf(emptyList<CaptureApp>()) }
    var selectedApp by rememberSaveable { mutableStateOf(PacketCaptureStore.state.value.selectedApp) }
    var pendingApp by rememberSaveable { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var protocol by rememberSaveable { mutableStateOf("Todos") }
    var protocolMenu by remember { mutableStateOf(false) }
    var connections by rememberSaveable { mutableStateOf(false) }
    var connectionFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var inspected by remember { mutableStateOf<CapturedPacket?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<List<CapturedPacket>?>(null) }
    val snapshot = remember(state.revision) { PacketCaptureStore.snapshot() }
    val filtered = remember(snapshot, query, protocol, connectionFilter) {
        snapshot.filter { it.matches(protocol, query) && (connectionFilter == null || it.connectionKey == connectionFilter) }.asReversed()
    }
    val groups = remember(filtered) { filtered.groupBy { it.connectionKey }.values.toList() }
    val appNames = remember(apps) { apps.associate { it.packageName to it.name } }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val manager = context.packageManager
            @Suppress("DEPRECATION") val installed = if (Build.VERSION.SDK_INT >= 33)
                manager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0)) else manager.getInstalledApplications(0)
            val collator = Collator.getInstance()
            installed.filter { it.enabled }.map { app -> CaptureApp(runCatching { manager.getApplicationLabel(app).toString() }.getOrDefault(app.packageName), app.packageName) }
                .sortedWith { a, b -> collator.compare(a.name, b.name).takeIf { it != 0 } ?: a.packageName.compareTo(b.packageName) }
        }
    }
    fun launchCapture() {
        if (PacketCaptureStore.state.value.active) return
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, PacketCaptureService::class.java)
                .setAction(PacketCaptureService.ACTION_START).putExtra(PacketCaptureService.EXTRA_APP, pendingApp))
        }.onFailure { vm.setMessage("O Android não permitiu iniciar a captura.") }
    }
    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) launchCapture() else vm.setMessage("Autorização de VPN não concedida.")
    }
    fun requestVpn() {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.activeNetwork?.let(manager::getNetworkCapabilities)
        if (capabilities == null) { vm.setMessage("Conecte o aparelho a uma rede."); return }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) { vm.setMessage("Desconecte a outra VPN antes de capturar."); return }
        val intent = VpnService.prepare(context)
        if (intent == null) launchCapture() else vpnPermission.launch(intent)
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        requestVpn()
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.tcpdump.pcap")) { uri ->
        val saved = pendingExport
        pendingExport = null
        if (uri != null) scope.launch {
            exporting = true
            try {
                requireNotNull(saved) { "Selecione os pacotes novamente" }
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri)).use { PcapCodec.export(it, saved) }
                }
                vm.setMessage("PCAP exportado: ${saved.size} pacotes.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { vm.setMessage("Não foi possível exportar o PCAP. Selecione os pacotes novamente.") }
            finally { exporting = false }
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { picker = true }, enabled = !state.active, modifier = Modifier.weight(1f)) {
                Text((if (state.active) state.selectedApp else selectedApp)?.let { appNames[it] ?: it } ?: "Todos os apps", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { info = true }) { Text("Info") }
            Button(onClick = {
                if (state.active) context.startService(Intent(context, PacketCaptureService::class.java).setAction(PacketCaptureService.ACTION_STOP))
                else {
                    pendingApp = selectedApp
                    if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else requestVpn()
                }
            }, enabled = !state.stopping) { Text(when { state.stopping -> "Parando"; state.active -> "Parar"; else -> "Capturar" }) }
        }
        Text(when { state.starting -> "Iniciando VPN…"; state.stopping -> "Encerrando VPN…"; state.running -> "VPN local · ${state.totalPackets} pacotes · ${trafficSize(state.totalBytes)}"; else -> "${state.totalPackets} pacotes · ${trafficSize(state.totalBytes)}" },
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (state.dropped > 0) Text("${state.dropped} pacotes descartados", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("IP, porta, app ou DNS") }, singleLine = true,
            trailingIcon = { if (query.isNotEmpty()) TextButton({ query = "" }) { Text("Limpar") } })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Box {
                TextButton(onClick = { protocolMenu = true }) { Text(if (protocol == "Todos") "Protocolos ▾" else "$protocol ▾") }
                DropdownMenu(protocolMenu, { protocolMenu = false }) {
                    listOf("Todos", "TCP", "UDP", "DNS", "ICMP", "ICMPV6", "OTHER").forEach { option ->
                        DropdownMenuItem(text = { Text(if (option == "OTHER") "Outros" else option) }, onClick = { protocol = option; protocolMenu = false })
                    }
                }
            }
            TextButton(onClick = { connections = !connections; connectionFilter = null }) { Text(if (connections) "Conexões" else "Pacotes") }
            TextButton(onClick = { pendingExport = filtered.asReversed().toList(); export.launch("nexo-captura.pcap") }, enabled = filtered.isNotEmpty() && !exporting) { Text("PCAP") }
            TextButton(onClick = { PacketCaptureStore.clear() }, enabled = !state.active && snapshot.isNotEmpty()) { Text("Limpar") }
            if (connectionFilter != null) FilterChip(selected = true, onClick = { connectionFilter = null }, label = { Text("Conexão ×") })
        }
        if (state.retained == PacketCaptureStore.MAX_PACKETS) Hint("Exibindo os últimos ${PacketCaptureStore.MAX_PACKETS} pacotes.")
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs), contentPadding = PaddingValues(bottom = Space.md)) {
            if (filtered.isEmpty()) item {
                Hint(if (state.active) "Aguardando tráfego que corresponda ao filtro." else "Inicie a captura e use o app que deseja analisar.")
            }
            if (connections) items(groups, key = { it.first().connectionKey }) { packets ->
                PacketRow(packets.first(), appNames, "${packets.size} pacotes · ${trafficSize(packets.sumOf { it.originalLength.toLong() })}") {
                    connectionFilter = packets.first().connectionKey; connections = false
                }
            } else items(filtered, key = { it.number }) { packet -> PacketRow(packet, appNames, null) { inspected = packet } }
        }
    }
    if (picker) CaptureAppPicker(apps, selectedApp, onSelect = { selectedApp = it; picker = false }, onDismiss = { picker = false })
    if (info) AlertDialog(onDismissRequest = { info = false }, title = { Text("Captura local") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            Text("O NEXO usa uma VPN local para observar e encaminhar TCP/UDP. Nenhum servidor de VPN recebe a captura.")
            Text("Os cabeçalhos pertencem à interface virtual. Não há modo monitor Wi-Fi nem captura de outros aparelhos. ICMP e protocolos IP não encaminhados pelo motor podem deixar de funcionar durante a sessão.")
            Text("HTTPS, TLS, QUIC e DNS criptografado continuam criptografados. Não são instalados certificados nem bloqueado o DNS privado.")
            Text("Ficam em memória os últimos ${PacketCaptureStore.MAX_PACKETS} pacotes, com até ${PcapCodec.SNAPLEN} bytes cada. A exportação PCAP respeita os filtros atuais e pode incluir dados sensíveis presentes nos pacotes.")
            Text("No Android 8/9, selecione o app antes de capturar; a identificação de apps em uma captura geral depende do Android 10 ou superior. A captura termina se a conexão usada for perdida.")
        }
    }, confirmButton = { TextButton({ info = false }) { Text("Fechar") } })
    inspected?.let { packet -> PacketDetails(packet) { inspected = null } }
}

@Composable private fun PacketRow(packet: CapturedPacket, apps: Map<String, String>, summary: String?, onClick: () -> Unit) {
    val colour = when (packet.protocol) { PacketProtocol.TCP -> MaterialTheme.colorScheme.primary; PacketProtocol.UDP -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.onSurfaceVariant }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(packet.protocol.name, color = colour, style = MaterialTheme.typography.labelMedium)
                Text(summary ?: "#${packet.number} · ${packet.originalLength} B", style = MaterialTheme.typography.labelSmall)
            }
            Text("${packet.sourceEndpoint} → ${packet.destinationEndpoint}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            (packet.dnsQuestion ?: packet.app?.let { apps[it] ?: it })?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable private fun CaptureAppPicker(apps: List<CaptureApp>, selected: String?, onSelect: (String?) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("App da captura") }, text = {
        Column {
            OutlinedTextField(query, { query = it }, label = { Text("Buscar app") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item { TextButton(onClick = { onSelect(null) }, modifier = Modifier.fillMaxWidth()) { Text(if (selected == null) "✓ Todos os apps" else "Todos os apps") } }
                items(apps.filter { it.name.contains(query, true) || it.packageName.contains(query, true) }, key = { it.packageName }) { app ->
                    Column(Modifier.fillMaxWidth().clickable { onSelect(app.packageName) }.padding(vertical = Space.sm)) {
                        Text((if (selected == app.packageName) "✓ " else "") + app.name, style = MaterialTheme.typography.bodyMedium)
                        Text(app.packageName, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("Fechar") } })
}

@Composable private fun PacketDetails(packet: CapturedPacket, onDismiss: () -> Unit) {
    val timestamp = remember(packet.timestampMicros) { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(packet.timestampMicros / 1000)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Pacote #${packet.number}") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Metric("Horário", timestamp)
            Metric("Tamanho", "${packet.bytes.size}/${packet.originalLength} B")
            Metric("Protocolo", "IPv${packet.ipVersion} · ${packet.protocol}")
            Metric("TTL / Hop limit", packet.ttl.toString())
            SelectionContainer { Text("${packet.sourceEndpoint}\n→ ${packet.destinationEndpoint}", style = MaterialTheme.typography.bodySmall) }
            packet.app?.let { Metric("App", it) }
            packet.tcpFlags?.let { Metric("Flags", PacketDecoder.flags(it)) }
            packet.sequence?.let { Metric("SEQ", it.toString()) }
            packet.acknowledgement?.let { Metric("ACK", it.toString()) }
            packet.dnsQuestion?.let { Metric("DNS", it) }
            SelectionContainer { Text(PacketDecoder.hex(packet.bytes), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall,
                softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState())) }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("Fechar") } })
}

private fun trafficSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MiB", bytes / 1048576.0)
    bytes >= 1024 -> String.format(Locale.getDefault(), "%.1f KiB", bytes / 1024.0)
    else -> "$bytes B"
}
