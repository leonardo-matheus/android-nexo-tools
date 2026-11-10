package br.com.nexotools.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import br.com.nexotools.domain.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable private fun ProbeCancellation(onStop: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onStop)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) latest() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); latest() }
    }
}

@Composable fun PortScanScreen(source: NetworkProbeSource) {
    var host by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf(false) }
    var portText by remember { mutableStateOf("22,80,443") }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Pronto para iniciar") }
    var error by remember { mutableStateOf<String?>(null) }
    var total by remember { mutableIntStateOf(0) }
    var generation by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    val results = remember { mutableStateListOf<PortProbeResult>() }
    val scope = rememberCoroutineScope()
    fun stop() {
        if (running) { generation++; job?.cancel(); running = false; status = "Interrompido · resultado parcial" }
    }
    ProbeCancellation(::stop)
    Panel {
        SectionLabel("Portas TCP")
        OutlinedTextField(host, { host = it.take(300); results.clear(); error = null }, label = { Text("Host, IPv4 ou IPv6") }, singleLine = true, enabled = !running, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth()) {
            listOf(false to "Comuns", true to "Lista/faixa").forEach { (selected, label) ->
                Row(Modifier.weight(1f).heightIn(min = Space.touch).selectable(custom == selected, enabled = !running, role = Role.RadioButton, onClick = { custom = selected }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(custom == selected, onClick = null, enabled = !running)
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (custom) OutlinedTextField(portText, { portText = it.take(2048) }, label = { Text("Portas ou faixas") }, supportingText = { Text("22,80,443,8000-8010 · até 256 portas") }, singleLine = true, enabled = !running, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth())
        else Hint(ProbeInputs.commonPorts.joinToString(", "))
        Button(onClick = {
            if (running) stop() else {
                val target = runCatching { ProbeInputs.host(host) }.getOrElse { error = it.message; return@Button }
                val ports = runCatching { if (custom) ProbeInputs.ports(portText) else ProbeInputs.commonPorts }.getOrElse { error = it.message; return@Button }
                val token = ++generation
                results.clear(); error = null; total = ports.size; running = true; status = "Resolvendo host"
                job = scope.launch {
                    try {
                        source.scan(target.value, ports).collect { result ->
                            if (token == generation) { results += result; status = "Testando · ${results.size}/$total" }
                        }
                        if (token == generation) status = "Concluído · ${results.size}/$total"
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { if (token == generation) { error = failure.message ?: "Teste TCP não concluído"; status = "Não concluído" } }
                    finally { if (token == generation) running = false }
                }
            }
        }, modifier = Modifier.fillMaxWidth().heightIn(min = Space.touch)) { Text(if (running) "Cancelar teste" else "Testar portas") }
        Text(status, style = MaterialTheme.typography.bodyMedium)
        if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Hint("Conexões TCP a um endereço do host informado. Até 8 simultâneas, timeout de 1 segundo. Não envia comandos nem identifica vulnerabilidades.")
    }
    if (results.isNotEmpty()) Panel {
        SectionLabel("Resultado TCP")
        Metric("Endereço testado", results.first().address)
        PortProbeStatus.entries.forEach { state -> Metric(state.label, results.count { it.status == state }.toString()) }
        results.sortedBy { it.port }.forEach { result ->
            Metric("${result.port}/TCP", "${result.status.label} · ${result.durationMs} ms")
            result.detail?.let { Hint(it) }
        }
        Hint("Sem resposta pode indicar filtragem, rota indisponível ou limite de tempo; não significa porta fechada.")
    }
}

@Composable fun DnsTlsScreen(source: NetworkProbeSource) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("443") }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Pronto para consultar") }
    var error by remember { mutableStateOf<String?>(null) }
    var dns by remember { mutableStateOf<DnsProbeResult?>(null) }
    var tls by remember { mutableStateOf<TlsProbeResult?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    fun stop() {
        if (running) { generation++; job?.cancel(); running = false; status = "Consulta interrompida" }
    }
    ProbeCancellation(::stop)
    fun run(checkTls: Boolean) {
        val target = runCatching { ProbeInputs.host(host) }.getOrElse { error = it.message; return }
        val targetPort = if (checkTls) port.toIntOrNull()?.takeIf { it in 1..65535 } else 443
        if (targetPort == null) { error = "Informe uma porta TLS entre 1 e 65535"; return }
        val token = ++generation
        dns = null; tls = null; error = null; running = true; status = if (checkTls) "Validando TLS" else "Resolvendo DNS"
        job = scope.launch {
            try {
                if (checkTls) { val result = source.tls(target.value, targetPort); if (token == generation) tls = result }
                else { val result = source.dns(target.value); if (token == generation) dns = result }
                if (token == generation) status = "Consulta concluída"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (token == generation) { error = failure.message ?: "Consulta não concluída"; status = "Não concluído" } }
            finally { if (token == generation) running = false }
        }
    }
    Panel {
        SectionLabel("DNS e TLS")
        OutlinedTextField(host, { host = it.take(300); dns = null; tls = null; error = null }, label = { Text("Host, IPv4 ou IPv6") }, singleLine = true, enabled = !running, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(port, { port = it.take(5); tls = null }, label = { Text("Porta TLS") }, singleLine = true, enabled = !running, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Button(onClick = { run(false) }, enabled = !running, modifier = Modifier.heightIn(min = Space.touch)) { Text("Resolver DNS") }
            OutlinedButton(onClick = { run(true) }, enabled = !running, modifier = Modifier.heightIn(min = Space.touch)) { Text("Validar TLS") }
            if (running) TextButton(onClick = ::stop, modifier = Modifier.heightIn(min = Space.touch)) { Text("Cancelar") }
        }
        Text(status, style = MaterialTheme.typography.bodyMedium)
        if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Hint("DNS usa o resolvedor do Android e pode aproveitar cache. TLS valida confiança e nome do host; certificados inválidos são rejeitados. Nenhum resultado é salvo.")
    }
    dns?.let { result -> Panel {
        SectionLabel("DNS do sistema")
        Metric("Duração da resolução", "${result.durationMs} ms")
        result.addresses.forEach { Metric(it.type, it.address) }
        if (result.literal) Hint("Endereço literal: não foi necessária uma consulta DNS.")
    } }
    tls?.let { result -> Panel {
        SectionLabel("TLS validado")
        Metric("Endereço", result.address)
        Metric("Protocolo", result.protocol)
        Metric("Cifra", result.cipher)
        Metric("Conexão e handshake", "${result.durationMs} ms")
        result.certificates.forEachIndexed { index, certificate ->
            SectionLabel("Certificado ${index + 1}")
            Metric("Titular", certificate.subject)
            Metric("Emissor", certificate.issuer)
            Metric("Válido de · UTC", probeDate(certificate.notBeforeMs))
            Metric("Válido até · UTC", probeDate(certificate.notAfterMs))
        }
    } }
}

private fun probeDate(epochMs: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.forLanguageTag("pt-BR"))
    .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(epochMs))
