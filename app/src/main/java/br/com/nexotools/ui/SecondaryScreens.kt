package br.com.nexotools.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.flowWithLifecycle
import br.com.nexotools.MainViewModel
import br.com.nexotools.R
import br.com.nexotools.data.PreferenceState
import br.com.nexotools.domain.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.pow

private val BrazilianLocale = Locale("pt", "BR")
private fun measured(value: Double?, decimals: Int = 1, unit: String = ""): String =
    if (value == null || !value.isFinite()) "Não disponível" else "% .${decimals}f".format(BrazilianLocale, value).trim() + if (unit.isEmpty()) "" else " $unit"

@Composable private fun StopOnBackground(onStop: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onStop)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) latest() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

@Composable fun AudioScreen(vm: MainViewModel, prefs: PreferenceState) {
    val context = LocalContext.current
    var permissionRevision by remember { mutableIntStateOf(0) }
    val allowed = permissionRevision.let { context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
    var running by remember { mutableStateOf(false) }
    var latest by remember { mutableStateOf<AudioReading?>(null) }
    val samples = remember { mutableStateListOf<Double>() }
    val referenceWindow = remember { ArrayDeque<AudioReading>() }
    var count by remember { mutableIntStateOf(0) }
    var energy by remember { mutableDoubleStateOf(0.0) }
    var minimum by remember { mutableStateOf<Double?>(null) }
    var maximum by remember { mutableStateOf<Double?>(null) }
    var reference by rememberSaveable { mutableStateOf("") }
    var activeSampleCount by remember { mutableIntStateOf(0) }
    StopOnBackground { running = false }
    LaunchedEffect(running, prefs.audioOffsetDb, allowed) {
        if (!running || !allowed) return@LaunchedEffect
        activeSampleCount = 0
        referenceWindow.clear()
        vm.audio.readings(prefs.audioOffsetDb).catch {
            running = false
            vm.setMessage(it.message ?: "Não foi possível acessar o microfone")
        }.collect {
            latest = it; count++; activeSampleCount++; energy += 10.0.pow(it.dbFs / 10.0)
            referenceWindow.addLast(it)
            if (referenceWindow.size > 20) referenceWindow.removeFirst()
            minimum = minOf(minimum ?: it.dbFs, it.dbFs); maximum = maxOf(maximum ?: it.dbFs, it.dbFs)
            samples.add(it.dbFs); if (samples.size > 600) samples.removeAt(0)
        }
    }
    PermissionPanel(arrayOf(Manifest.permission.RECORD_AUDIO), "O microfone calcula o RMS localmente. Áudio não é salvo nem enviado.") { permissionRevision++ }
    val offset = prefs.audioOffsetDb ?: 0.0
    val unit = if (prefs.audioOffsetDb == null) "dBFS" else "dB SPL estimado"
    val average = if (count > 0) 10.0 * log10(energy / count) else null
    Column(verticalArrangement = Arrangement.spacedBy(Space.lg)) {
        Panel {
            SectionLabel(if (running) "Microfone ativo" else "Medição pausada")
            InstrumentReading(measured(latest?.dbFs?.plus(offset), prefs.decimals))
            Text(unit)
            LinearProgressIndicator(progress = { (((latest?.dbFs ?: -120.0) + 120.0) / 120.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            if (latest?.clipped == true) Hint("Saturação detectada. Esta leitura não serve para calibrar.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Button(onClick = { running = !running }, enabled = allowed, modifier = Modifier.heightIn(min = Space.touch)) {
                    Glyph(if (running) R.drawable.ic_pause else R.drawable.ic_play); Spacer(Modifier.width(Space.sm)); Text(if (running) "Pausar" else "Iniciar")
                }
                OutlinedButton(onClick = { running = false; latest = null; samples.clear(); count = 0; energy = 0.0; minimum = null; maximum = null }, modifier = Modifier.heightIn(min = Space.touch)) { Text("Reiniciar") }
            }
            Hint(if (prefs.audioOffsetDb == null) "dBFS é nível digital. Para estimar dB SPL, calibre com uma referência conhecida." else "dB SPL estimado, sem ponderação A. O aparelho não é certificado.")
        }
        Panel {
            SectionLabel("Estatísticas da sessão")
            Metric("Mínima", measured(minimum?.plus(offset), prefs.decimals, unit))
            Metric("Máxima", measured(maximum?.plus(offset), prefs.decimals, unit))
            Metric("Média energética", measured(average?.plus(offset), prefs.decimals, unit))
            Trace(samples.map { it + offset })
            Hint("Gráfico: últimas ${samples.size} leituras. Estatísticas incluem toda a sessão.")
            OutlinedButton(onClick = { vm.save("Ruído", "Média RMS ${measured(average, prefs.decimals, "dBFS")} · ${count} leituras", samples.joinToString(",") { "%.2f".format(Locale.US, it) }) }, enabled = count > 0, modifier = Modifier.heightIn(min = Space.touch)) { Glyph(R.drawable.ic_save); Spacer(Modifier.width(Space.sm)); Text("Salvar medição") }
        }
        Panel {
            SectionLabel("Calibração acústica")
            Hint("Use sonômetro ou calibrador de referência. Mantenha o som estável por 2 segundos; não estime o valor do ambiente.")
            OutlinedTextField(reference, { reference = it }, label = { Text("Referência conhecida em dB SPL") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                runCatching {
                    val recent = referenceWindow.map { it.dbFs }
                    require(referenceWindow.none { it.clipped }) { "Há saturação na janela de referência. Reduza o nível e repita." }
                    require(running && activeSampleCount >= 20 && recent.size >= 20 && recent.max() - recent.min() < 3.0) { "Inicie e mantenha uma referência estável por 2 segundos" }
                    AudioMath.referenceOffset(requireNotNull(reference.replace(',', '.').toDoubleOrNull()) { "Informe a referência em dB SPL" }, requireNotNull(AudioMath.energyMean(recent)))
                }.onSuccess { calibration -> vm.act { vm.preferences.setAudioOffset(calibration); vm.setMessage("Referência acústica salva") } }
                    .onFailure { vm.setMessage(it.message ?: "Referência inválida") }
            }, enabled = allowed && running, modifier = Modifier.heightIn(min = Space.touch)) { Text("Aplicar referência") }
            if (prefs.audioOffsetDb != null) TextButton(onClick = { vm.act { vm.preferences.setAudioOffset(null) } }) { Text("Voltar para dBFS") }
        }
    }
}

@Composable fun PingScreen(vm: MainViewModel, prefs: PreferenceState) {
    var host by rememberSaveable { mutableStateOf("one.one.one.one") }
    var port by rememberSaveable { mutableStateOf("443") }
    var running by remember { mutableStateOf(false) }
    val samples = remember { mutableStateListOf<PingSample>() }
    StopOnBackground { running = false }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        samples.clear()
        try {
            val targetPort = port.toIntOrNull()
            require(targetPort != null && targetPort in 1..65535) { "Informe uma porta entre 1 e 65535" }
            vm.ping.run(host.trim(), targetPort).collect { samples.add(it) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { vm.setMessage(e.message ?: "Não foi possível executar o teste") }
        finally { running = false }
    }
    val stats = PingMath.statistics(samples.toList())
    Column(verticalArrangement = Arrangement.spacedBy(Space.lg)) {
        Panel {
            SectionLabel("Destino do teste")
            OutlinedTextField(host, { host = it }, enabled = !running, label = { Text("Host ou IPv4") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(port, { port = it }, enabled = !running, label = { Text("Porta TCP") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                FilterChip(selected = host == "one.one.one.one", onClick = { host = "one.one.one.one"; port = "443" }, enabled = !running, label = { Text("Cloudflare") })
                FilterChip(selected = host == "www.google.com", onClick = { host = "www.google.com"; port = "443" }, enabled = !running, label = { Text("Google") })
            }
            Button(onClick = { running = !running }, modifier = Modifier.fillMaxWidth().heightIn(min = Space.touch)) { Glyph(if (running) R.drawable.ic_pause else R.drawable.ic_play); Spacer(Modifier.width(Space.sm)); Text(if (running) "Interromper" else "Testar 10 conexões") }
            Hint("Conexão TCP, com timeout de 3 segundos. DNS não entra na latência; nenhum conteúdo é enviado.")
        }
        Panel {
            SectionLabel(if (running) "Testando · ${samples.size}/10" else "Resultado TCP")
            InstrumentReading(measured(samples.lastOrNull()?.latencyMs, prefs.decimals, "ms"))
            Metric("Mínima", measured(stats.minimumMs, prefs.decimals, "ms"))
            Metric("Máxima", measured(stats.maximumMs, prefs.decimals, "ms"))
            Metric("Média", measured(stats.meanMs, prefs.decimals, "ms"))
            Metric("Jitter", measured(stats.jitterMs, prefs.decimals, "ms"))
            Metric("Falhas de conexão", if (stats.sent > 0) measured(stats.lossPercent, 0, "%") else "Não disponível")
            Trace(samples.mapNotNull { it.latencyMs })
            Hint("Falhas TCP não são perda ICMP. Jitter é a variação entre sucessos consecutivos; o gráfico mostra esses sucessos.")
            samples.forEach { sample -> Metric("Conexão ${sample.sequence}", sample.latencyMs?.let { measured(it, prefs.decimals, "ms") } ?: "Falha ou timeout") }
            OutlinedButton(onClick = { vm.save("Latência TCP", "${stats.received}/${stats.sent} conexões · média ${measured(stats.meanMs, prefs.decimals, "ms")} · falhas ${measured(stats.lossPercent, 0, "%")}", samples.joinToString(",") { it.latencyMs?.toString() ?: "falha" }) }, enabled = !running && samples.isNotEmpty(), modifier = Modifier.heightIn(min = Space.touch)) { Glyph(R.drawable.ic_save); Spacer(Modifier.width(Space.sm)); Text("Salvar resultado") }
        }
    }
}

@Composable fun MobileScreen(vm: MainViewModel, prefs: PreferenceState) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    val permissions = arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    var reading by remember { mutableStateOf(MobileReading()) }
    val allowed = revision.let { context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED }
    var sims by remember { mutableStateOf(emptyList<SimOption>()) }
    PermissionPanel(permissions, "Telefone permite consultar o sinal e selecionar SIM. Localização precisa permite métricas de célula; nenhum número de telefone é acessado.") { revision++ }
    LaunchedEffect(allowed, revision) { sims = if (allowed) withContext(Dispatchers.IO) { vm.mobile.subscriptions() } else emptyList() }
    LaunchedEffect(selected, allowed, revision) {
        if (!allowed) { reading = MobileReading(status = "Permissão de telefone necessária"); return@LaunchedEffect }
        vm.mobile.readings(selected).flowWithLifecycle(owner.lifecycle, Lifecycle.State.STARTED).catch { reading = MobileReading(status = "Métricas não disponíveis neste aparelho") }.collect { reading = it }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.lg)) {
        Panel {
            SectionLabel("SIM e tecnologia")
            if (sims.isEmpty()) Hint("SIM não disponível; será usada a assinatura de dados padrão quando possível.")
            sims.forEach { sim ->
                Row(Modifier.fillMaxWidth().heightIn(min = Space.touch).clickable { selected = sim.id }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected == sim.id || selected == null && reading.simLabel == sim.label, { selected = sim.id }); Text(sim.label, Modifier.weight(1f))
                }
            }
            Text(reading.technology ?: "Não disponível", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Hint(reading.status)
            Metric("Operadora", reading.operator ?: "Não disponível")
            Metric("SIM ativo", reading.simLabel ?: "Padrão de dados")
            Metric("Intensidade", reading.level?.let { "$it de 4" } ?: "Não disponível")
        }
        RadioPanel("LTE · referência da célula", reading.lte, prefs.decimals)
        RadioPanel("NR · referência SS", reading.nr, prefs.decimals)
        Hint("Dados do modem podem estar em cache. 5G disponível não confirma NR ativo; LTE e NR são separados, sem presumir SA.")
    }
}

@Composable private fun RadioPanel(title: String, radio: RadioMetrics?, decimals: Int) {
    Panel {
        SectionLabel(title)
        Metric("Sinal", radio?.dbm?.let { "$it dBm" } ?: "Não disponível")
        Metric("RSRP", radio?.rsrp?.let { "$it dBm" } ?: "Não disponível")
        Metric("RSRQ", radio?.rsrq?.let { "$it dB" } ?: "Não disponível")
        Metric("SINR", measured(radio?.sinr, decimals, "dB"))
        Metric("PCI", radio?.pci?.toString() ?: "Não disponível")
        Metric("Banda", radio?.band?.toString() ?: "Não disponível")
    }
}

@Composable fun CalibrationScreen(vm: MainViewModel, prefs: PreferenceState) {
    val calibrator = remember { SixFaceCalibrator() }
    var poses by remember { mutableStateOf(emptySet<CalibrationPose>()) }
    var selected by remember { mutableStateOf(CalibrationPose.POS_X) }
    var operation by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf("") }
    var restoreDialog by remember { mutableStateOf(false) }
    StopOnBackground { operation = null; progress = "Captura interrompida" }
    LaunchedEffect(operation) {
        val kind = operation ?: return@LaunchedEffect
        try {
            for (second in 3 downTo 1) { progress = "Posicione o aparelho em $second s"; delay(1000) }
            progress = "Mantenha imóvel · coletando amostras"
            val window = mutableListOf<CalibrationSample>()
            val captured = withTimeout(15000) {
                vm.orientation.calibrationSamples().transform { sample ->
                    if (sample.stable) window.add(sample) else window.clear()
                    if (window.size >= 40) emit(window.toList())
                }.first()
            }
            if (kind == "face") {
                calibrator.add(selected, captured).getOrThrow(); poses = calibrator.capturedPoses
                selected = CalibrationPose.entries.firstOrNull { it !in poses } ?: selected
                progress = "Posição salva · ${poses.size} de 6"
            } else {
                val gyro = captured.mapNotNull { it.gyro }
                require(gyro.size >= 40 && gyro.all { it.isFinite() && it.norm() < 0.15 }) { "Giroscópio indisponível ou aparelho em movimento" }
                val mean = gyro.fold(Vec3.ZERO, Vec3::plus) * (1.0 / gyro.size)
                vm.preferences.setCalibration(prefs.calibration.copy(gyroBias = mean)); progress = "Viés do giroscópio salvo"
            }
        } catch (_: TimeoutCancellationException) { progress = "Sem amostras estáveis no prazo. Repita em uma superfície firme."; vm.setMessage(progress) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { progress = e.message ?: "A captura falhou. Repita com o aparelho imóvel."; vm.setMessage(progress) }
        finally { operation = null }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.lg)) {
        Panel {
            SectionLabel("Calibração dos sensores")
            Hint("Use a orientação natural do aparelho, superfície firme e sem vibração. Retire capas irregulares; em tablets paisagem, confira X/Y. A precisão depende do hardware.")
            Metric("Acelerômetro", if (prefs.calibration.offset != Vec3.ZERO || prefs.calibration.scale != Vec3.ONES) "Parâmetros pessoais" else "Padrão do aparelho")
            Metric("Viés do giroscópio", measured(prefs.calibration.gyroBias.norm(), 4, "rad/s"))
            Metric("Referência relativa", if (prefs.calibration.reference != null) "Ativa" else "Horizontal por gravidade")
        }
        Panel {
            SectionLabel("Acelerômetro · seis posições")
            CalibrationPose.entries.forEach { pose ->
                Row(Modifier.fillMaxWidth().heightIn(min = Space.touch).clickable(enabled = operation == null) { selected = pose }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected == pose, { selected = pose }, enabled = operation == null)
                    Text(pose.instruction, Modifier.weight(1f))
                    if (pose in poses) Glyph(R.drawable.ic_check, "Posição capturada")
                }
            }
            Hint("Toque em capturar; há 3 segundos para posicionar ou virar o aparelho. A captura exige 40 amostras estáveis.")
            if (operation != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (progress.isNotBlank()) Hint(progress)
            Button(onClick = { operation = "face" }, enabled = operation == null, modifier = Modifier.fillMaxWidth().heightIn(min = Space.touch)) { Text("Capturar posição") }
            if (operation != null) TextButton(onClick = { operation = null; progress = "Captura cancelada" }) { Text("Cancelar captura") }
            OutlinedButton(onClick = { calibrator.finish().onSuccess { result -> vm.act { vm.preferences.setCalibration(result); vm.setMessage("Calibração de seis posições aplicada") } }.onFailure { vm.setMessage(it.message ?: "Capture todas as posições") } }, enabled = poses.size == 6 && operation == null, modifier = Modifier.fillMaxWidth().heightIn(min = Space.touch)) { Text("Aplicar calibração") }
        }
        Panel {
            SectionLabel("Giroscópio · viés em repouso")
            Hint("Deixe o aparelho imóvel. Sem giroscópio, o nível continua usando gravidade ou acelerômetro.")
            OutlinedButton(onClick = { operation = "gyro" }, enabled = operation == null, modifier = Modifier.fillMaxWidth().heightIn(min = Space.touch)) { Text("Estimar viés em repouso") }
            TextButton(onClick = { restoreDialog = true }, enabled = operation == null) { Text("Restaurar padrões") }
        }
    }
    if (restoreDialog) AlertDialog(onDismissRequest = { restoreDialog = false }, title = { Text("Restaurar calibração?") }, text = { Text("Remove viés, escala, zeragem relativa e referência acústica salvos neste aparelho.", Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = { restoreDialog = false; vm.act { vm.preferences.resetCalibration(); vm.setMessage("Calibração restaurada") } }) { Text("Restaurar") } }, dismissButton = { TextButton(onClick = { restoreDialog = false }) { Text("Cancelar") } })
}

@Composable fun SettingsScreen(vm: MainViewModel, prefs: PreferenceState, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    @Suppress("DEPRECATION") val version = remember(context) { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "Não disponível" }
    Column(verticalArrangement = Arrangement.spacedBy(Space.lg)) {
        Panel {
            SectionLabel("Aparência")
            listOf(Triple("SYSTEM", "Seguir o sistema", R.drawable.ic_monitor), Triple("DARK", "Escuro", R.drawable.ic_moon), Triple("LIGHT", "Claro", R.drawable.ic_sun)).forEach { (key, label, icon) ->
                Row(Modifier.fillMaxWidth().heightIn(min = Space.touch).clickable { vm.act { vm.preferences.setTheme(key) } }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(prefs.theme == key, { vm.act { vm.preferences.setTheme(key) } }); Glyph(icon); Spacer(Modifier.width(Space.md)); Text(label, Modifier.weight(1f))
                }
            }
        }
        Panel {
            SectionLabel("Leituras e privacidade")
            Text("Casas decimais")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) { (0..3).forEach { value -> FilterChip(selected = prefs.decimals == value, onClick = { vm.act { vm.preferences.setDecimals(value) } }, label = { Text(value.toString()) }) } }
            Hint("Não altera o nível ou inclinômetro, que mostram graus inteiros. Mais casas não aumentam a precisão.")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Ocultar redes e endereços", Modifier.weight(1f)); Switch(prefs.hideSensitive, { vm.act { vm.preferences.setHideSensitive(it) } })
            }
            OutlinedButton(onClick = { onNavigate("calibration") }, modifier = Modifier.fillMaxWidth().heightIn(min = Space.touch)) { Text("Calibração dos instrumentos") }
            OutlinedButton(onClick = { onNavigate("privacy") }, modifier = Modifier.fillMaxWidth().heightIn(min = Space.touch)) { Text("Permissões e privacidade") }
        }
        Hint("Versão $version")
    }
}

@Composable fun PrivacyScreen() {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(Space.lg)) {
        Panel {
            Glyph(R.drawable.ic_shield_check)
            SectionLabel("Dados no seu aparelho")
            Hint("Preferências, calibrações e medições salvas ficam no armazenamento privado. Não há conta, publicidade, telemetria ou envio de áudio.")
            Hint("O histórico não guarda SSID, BSSID, IP, identificadores de SIM ou destinos editados. Ocultar dados de rede é o padrão visual.")
            Hint("O Sonar consulta anúncios Wi-Fi e identifica o registro OUI localmente, sem salvar as redes próximas.")
            Hint("O desinstalador consulta os apps deste perfil localmente e abre a confirmação do Android. Não remove apps silenciosamente.")
            Hint("A captura VPN mantém em memória até 3.000 pacotes. Exportar PCAP grava apenas os pacotes selecionados pelos filtros, no arquivo escolhido por você.")
        }
        Panel {
            SectionLabel("Permissões por necessidade")
            Metric("Microfone", "Medidor de ruído")
            Metric("Câmera", "Prévia e captura PNG")
            Metric("Localização precisa", "Informações Wi-Fi e células")
            Metric("Telefone", "Sinal e seleção de SIM")
            Metric("VPN local", "Monitor de tráfego")
            Metric("Apps instalados", "Desinstalador e filtro de captura")
            Metric("Acesso ao uso", "Tamanho dos apps e último uso; opcional")
            Hint("Microfone e câmera param ao deixar a ferramenta ou enviar o app para segundo plano. A captura VPN continua até você parar, revogar a VPN ou perder a conexão usada.")
            Hint("Android pode ocultar dados Wi-Fi quando a localização está desligada. O NEXO Tools não solicita localização em segundo plano.")
            OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) } }, modifier = Modifier.fillMaxWidth().heightIn(min = Space.touch)) { Text("Gerenciar permissões no Android") }
        }
        Panel {
            SectionLabel("Conexões externas")
            Hint("Testes TCP acessam o host escolhido e revelam seu IP ao destino, como qualquer conexão de internet. FAST.com e Speedtest abrem serviços externos e seguem suas políticas.")
            Hint("O teste nativo conecta à Cloudflare, que recebe seu IP e as requisições de medição. Os resultados só ficam no histórico se você salvar.")
            Hint("Resultados externos não são presumidos nem salvos automaticamente.")
        }
    }
}

@Composable fun HistoryScreen(vm: MainViewModel) {
    val entries by vm.history.collectAsStateWithLifecycle()
    var clearDialog by remember { mutableStateOf(false) }
    val date = remember { SimpleDateFormat("dd/MM/yyyy · HH:mm", BrazilianLocale) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.lg)) {
        if (entries.isEmpty()) Panel { Glyph(R.drawable.ic_rotate_ccw); SectionLabel("Seu histórico começa aqui"); Hint("Salve uma medição em um instrumento. Somente resultados reais são registrados.") }
        else {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                SectionLabel("Últimas ${entries.size} medições")
                TextButton(onClick = { clearDialog = true }) { Text("Apagar histórico") }
            }
            entries.forEach { entry -> Panel { SectionLabel(entry.tool); Hint(date.format(Date(entry.timestamp))); Text(entry.summary) } }
        }
    }
    if (clearDialog) AlertDialog(onDismissRequest = { clearDialog = false }, title = { Text("Apagar o histórico?") }, text = { Text("As medições salvas serão removidas deste aparelho. Esta ação não pode ser desfeita.", Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = { clearDialog = false; vm.clearHistory() }) { Text("Apagar") } }, dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("Cancelar") } })
}

@Composable fun SpeedScreen() {
    val context = LocalContext.current
    var failure by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.lg)) {
        Hint("O serviço abre no navegador. Use Voltar para retornar ao NEXO.")
        listOf(Triple("FAST.com · Netflix", "https://fast.com/pt/", "Teste externo de download, upload e latência com a infraestrutura da Netflix."), Triple("Speedtest · Ookla", "https://www.speedtest.net/", "Teste externo com seleção de servidor e métricas fornecidas pela Ookla.")).forEach { (name, url, explanation) ->
            Panel {
                Glyph(R.drawable.ic_gauge); SectionLabel(name); Hint(explanation)
                Button(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { failure = "Nenhum navegador disponível para abrir o serviço" } }, modifier = Modifier.fillMaxWidth().heightIn(min = Space.touch)) { Text("Abrir serviço"); Spacer(Modifier.width(Space.sm)); Glyph(R.drawable.ic_arrow_up_right) }
            }
        }
        failure?.let { Hint(it) }
        Hint("Pode consumir muitos dados. Abrir o serviço não salva resultados no histórico.")
    }
}
