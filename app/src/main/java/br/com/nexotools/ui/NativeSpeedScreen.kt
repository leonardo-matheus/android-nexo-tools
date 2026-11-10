package br.com.nexotools.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import br.com.nexotools.MainViewModel
import br.com.nexotools.data.PreferenceState
import br.com.nexotools.domain.*
import java.util.Locale
import kotlinx.coroutines.CancellationException

private val speedLocale = Locale.forLanguageTag("pt-BR")
private fun speedValue(value: Double?, decimals: Int = 1, suffix: String = ""): String =
    value?.takeIf { it.isFinite() }?.let { String.format(speedLocale, "%.${decimals}f", it) + suffix } ?: "Não disponível"

@Composable fun NativeSpeedScreen(vm: MainViewModel, prefs: PreferenceState) {
    val owner = LocalLifecycleOwner.current
    var selected by rememberSaveable { mutableStateOf(SpeedProfile.BALANCED.name) }
    var loaded by rememberSaveable { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    var showOfficial by rememberSaveable { mutableStateOf(false) }
    var activeConfig by remember { mutableStateOf(SpeedConfig()) }
    var state by remember { mutableStateOf(SpeedState()) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) {
            generation++
            running = false
            if (state.stage !in setOf(SpeedStage.READY, SpeedStage.COMPLETE, SpeedStage.ERROR))
                state = state.copy(stage = SpeedStage.ERROR, message = "Teste interrompido em segundo plano; inicie novamente para obter um resultado final.")
        } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(running,generation) {
        if (!running) return@LaunchedEffect
        val token=generation
        state = SpeedState(stage = SpeedStage.WARMUP)
        try { vm.nativeSpeed.run(activeConfig).collect { if(running && token==generation)state = it } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if(running && token==generation)state = state.copy(stage = SpeedStage.ERROR, message = "Não foi possível concluir o teste de rede") }
        finally { if(token==generation)running = false }
    }
    Panel {
        SectionLabel("Teste NEXO · Cloudflare")
        Hint("Meça download, upload e resposta da conexão sem sair do NEXO. Usa infraestrutura Cloudflare; é independente dos testes FAST e Ookla.")
        SpeedProfile.entries.forEach { profile ->
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                RadioButton(selected == profile.name, onClick = { selected = profile.name }, enabled = !running)
                Text(profile.title, Modifier.weight(1f))
            }
        }
        Hint("O consumo total pode ser um pouco maior que o limite indicado. Se a franquia for limitada, escolha Economia ou use Wi-Fi.")
        Row(Modifier.fillMaxWidth()) {
            Text("Medir latência durante as transferências", Modifier.weight(1f))
            Switch(loaded, { loaded = it }, enabled = !running)
        }
        Button(onClick = {
            generation++
            if (running) {
                running = false
                state = state.copy(stage = SpeedStage.ERROR, currentMbps = null, message = "Teste cancelado; os valores exibidos são parciais")
            } else {
                activeConfig = SpeedConfig(SpeedProfile.valueOf(selected), loaded)
                running = true
            }
        }, modifier = Modifier.fillMaxWidth().heightIn(min = Space.touch)) { Text(if (running) "Cancelar teste" else "Iniciar teste nativo") }
        Hint("Até 12 segundos por direção e limite total de 55 segundos. O teste encerra antes se atingir o perfil de dados.")
    }
    Panel {
        SectionLabel(when (state.stage) {
            SpeedStage.READY -> "Pronto para iniciar"
            SpeedStage.WARMUP -> "Aquecendo a conexão"
            SpeedStage.LATENCY -> "Medindo latência sem carga"
            SpeedStage.DOWNLOAD -> "Medindo download"
            SpeedStage.UPLOAD -> "Medindo upload"
            SpeedStage.COMPLETE -> "Resultado nativo"
            SpeedStage.ERROR -> "Teste não concluído"
        })
        if (state.stage in setOf(SpeedStage.DOWNLOAD, SpeedStage.UPLOAD)) {
            Text(speedValue(state.currentMbps, prefs.decimals, " Mbps"), style = InstrumentStyle, color = MaterialTheme.colorScheme.primary)
            Hint(if (state.stage == SpeedStage.UPLOAD) "Velocidade estimada durante o envio. Aguarde o fim para o resultado." else "Velocidade durante o download")
            LinearProgressIndicator(progress = { state.phaseProgress }, modifier = Modifier.fillMaxWidth())
        } else if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
        Metric("Download médio", speedValue(state.downloadMbps, prefs.decimals, " Mbps"))
        Metric("Upload médio", speedValue(state.uploadMbps, prefs.decimals, " Mbps"))
        Metric("Dados de download", speedValue(state.downloadBytes / 1_000_000.0, 1, " MB"))
        Metric("Dados de upload", speedValue(state.uploadBytes / 1_000_000.0, 1, " MB"))
        Metric("Duração", "${state.elapsedMs / 1000} s")
        state.message?.let { Hint(it) }
        if (state.shortSample) Hint("Amostra curta: limites baixos em conexões rápidas reduzem o período observado e a confiança do resultado.")
        OutlinedButton(onClick = { vm.save("Velocidade nativa", "Cloudflare · download ${speedValue(state.downloadMbps, prefs.decimals, "Mbps")} · upload ${speedValue(state.uploadMbps, prefs.decimals, "Mbps")} · HTTP ${speedValue(state.unloaded.medianMs, prefs.decimals, "ms")}${if (state.shortSample) " · amostra curta" else ""}") },
            enabled = !running && state.stage == SpeedStage.COMPLETE && state.downloadMbps != null && state.uploadMbps != null,
            modifier = Modifier.heightIn(min = Space.touch)) { Text("Salvar resultado real") }
    }
    Panel {
        SectionLabel("Latência HTTP")
        Metric("Mediana sem carga", speedValue(state.unloaded.medianMs, prefs.decimals, " ms"))
        Metric("Mínima / máxima", "${speedValue(state.unloaded.minimumMs, prefs.decimals)} / ${speedValue(state.unloaded.maximumMs, prefs.decimals)} ms")
        Metric("Média sem carga", speedValue(state.unloaded.meanMs, prefs.decimals, " ms"))
        Metric("Jitter sem carga", speedValue(state.unloaded.jitterMs, prefs.decimals, " ms"))
        Metric("Mediana durante download", speedValue(state.downLoaded.medianMs, prefs.decimals, " ms"))
        Metric("Mediana durante upload", speedValue(state.upLoaded.medianMs, prefs.decimals, " ms"))
        Hint("Tempo de resposta do servidor web. Pode diferir do ping de outros serviços. Jitter é a variação entre respostas; este teste não mede perda de pacotes.")
    }
    Panel {
        SectionLabel("Velocidade durante o teste")
        val down = state.trace.filter { it.stage == SpeedStage.DOWNLOAD }.map { it.mbps }
        val up = state.trace.filter { it.stage == SpeedStage.UPLOAD }.map { it.mbps }
        if (down.isNotEmpty()) { Hint("Evolução do download"); Trace(down) }
        if (up.isNotEmpty()) { Hint("Evolução do upload"); Trace(up) }
        if (down.isEmpty() && up.isEmpty()) Hint("O gráfico aparece quando houver transferência observada por tempo suficiente.")
    }
    Hint("A Cloudflare recebe seu IP e o tráfego do teste. O histórico fica no aparelho. O teste para ao sair da tela ou enviar o app ao segundo plano.")
    TextButton(onClick={showOfficial=!showOfficial},enabled=!running){Text(if(showOfficial)"Ocultar serviços oficiais" else "Comparar com FAST e Ookla")}
    if(showOfficial)SpeedScreen()
}
