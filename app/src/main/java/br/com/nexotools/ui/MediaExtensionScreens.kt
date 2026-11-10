package br.com.nexotools.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.LocalActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.nexotools.MainViewModel
import br.com.nexotools.R
import br.com.nexotools.domain.AudioGainMath
import br.com.nexotools.domain.NightTint
import br.com.nexotools.media.AudioBoostPlayer
import br.com.nexotools.media.NightCameraController
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val mediaLocale = Locale.forLanguageTag("pt-BR")
private val cameraPreviewHeight = 300.dp
private fun mediaNumber(value: Double, places: Int = 1) = String.format(mediaLocale, "%.${places}f", value)
private fun playbackTime(milliseconds: Int): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "%d:%02d".format(mediaLocale, seconds / 60, seconds % 60)
}

@Composable fun VolumeBoostScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val player = remember(context) { AudioBoostPlayer(context) }
    val state by player.state.collectAsStateWithLifecycle()
    var seeking by remember { mutableStateOf<Float?>(null) }
    val openAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(player::load) }
    DisposableEffect(player, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pauseForBackground() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); player.close() }
    }
    Panel {
        Glyph(R.drawable.ic_audio_lines)
        SectionLabel("Ganho de áudio local")
        Hint("Selecione um arquivo de áudio. O efeito modifica somente o player do NEXO Tools; o volume do Android continua separado.")
        Button(onClick = { openAudio.launch(arrayOf("audio/*")) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth().heightIn(min = Space.touch)) { Text("Selecionar áudio no aparelho") }
        state.fileName?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        if (state.loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Hint("Preparando áudio") }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Button(onClick = player::togglePlayback, enabled = state.ready, modifier = Modifier.weight(1f).heightIn(min = Space.touch)) {
                Glyph(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play)
                Spacer(Modifier.width(Space.sm)); Text(if (state.playing) "Pausar" else "Reproduzir")
            }
            OutlinedButton(onClick = { player.reset(); seeking = null }, enabled = state.ready, modifier = Modifier.weight(1f).heightIn(min = Space.touch)) { Text("Restaurar") }
        }
        if (state.ready) {
            Slider(value = seeking ?: state.positionMs.toFloat(), onValueChange = { seeking = it },
                onValueChangeFinished = { seeking?.let { player.seek(it.roundToInt()) }; seeking = null },
                valueRange = 0f..state.durationMs.coerceAtLeast(1).toFloat(), enabled = state.durationMs > 0)
            Metric("Posição", "${playbackTime((seeking ?: state.positionMs.toFloat()).roundToInt())} / ${playbackTime(state.durationMs)}")
        }
    }
    Panel {
        SectionLabel("Amplificação do arquivo")
        Text("${mediaNumber(AudioGainMath.amplitudePercent(state.gainDb), 0)}%", style = InstrumentStyle, color = MaterialTheme.colorScheme.primary)
        Metric("Ganho alvo da sessão", "+${mediaNumber(state.gainDb.toDouble())} dB")
        Slider(value = state.gainDb, onValueChange = player::setGain, valueRange = 0f..12f, steps = 23,
            enabled = state.ready && state.effectAvailable)
        Hint("0 dB = 100% de amplitude; +12 dB ≈ 398%. Ajustes em 0,5 dB via LoudnessEnhancer da sessão deste player.")
        Metric("Efeito do aparelho", when { !state.ready -> "Selecione um áudio"; state.effectAvailable -> "LoudnessEnhancer disponível"; else -> "Não disponível" })
        state.effectError?.let { Hint(it) }
        Hint("Ganho elevado pode distorcer picos e aumentar o volume ouvido. Comece em 0 dB e ajuste gradualmente. Não há promessa de proteção do alto-falante ou da audição.")
    }
    Hint("Sem permissão de microfone ou acesso amplo ao armazenamento. Reprodução pausa em segundo plano e encerra ao sair desta ferramenta.")
    Hint("Perder o foco de áudio ou desconectar os fones pausa a reprodução. O NEXO Tools não retoma o som automaticamente.")
}

@Composable fun NightVisionScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val activity = LocalActivity.current
    val configuration = LocalConfiguration.current
    @Suppress("DEPRECATION") val rotation = remember(activity, configuration) { activity?.windowManager?.defaultDisplay?.rotation ?: 0 }
    val scope = rememberCoroutineScope()
    val controller = remember(context) { NightCameraController(context) }
    val cameraState by controller.state.collectAsStateWithLifecycle()
    val frame by controller.frame.collectAsStateWithLifecycle()
    var revision by remember { mutableIntStateOf(0) }
    var lens by rememberSaveable { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var brightness by rememberSaveable { mutableFloatStateOf(1.8f) }
    var tintName by rememberSaveable { mutableStateOf(NightTint.GREEN.name) }
    var exposureSlider by remember { mutableStateOf<Float?>(null) }
    var pendingExport by remember { mutableStateOf<Bitmap?>(null) }
    var saving by remember { mutableStateOf(false) }
    val tint = NightTint.valueOf(tintName)
    val allowed = revision.let { context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED }
    val previewView = remember(context) { PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FIT_CENTER
    } }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val snapshot = pendingExport
        pendingExport = null
        if (uri != null && snapshot != null) scope.launch {
            saving = true
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                        check(snapshot.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Falha ao codificar PNG" }
                    } ?: error("Destino de imagem não disponível")
                }
                vm.setMessage("Prévia processada salva em PNG")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { vm.setMessage("Não foi possível salvar a imagem no destino escolhido") }
            finally { saving = false }
        }
    }
    DisposableEffect(controller, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controller.pauseForBackground()
            if (event == Lifecycle.Event.ON_START) revision++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); controller.close() }
    }
    LaunchedEffect(brightness, tint) { controller.setProcessing(brightness, tint) }
    LaunchedEffect(allowed, revision, lens, owner, rotation) {
        exposureSlider = null
        if (allowed) controller.bind(owner, previewView, lens, rotation) else controller.unbind()
    }
    PermissionPanel(arrayOf(Manifest.permission.CAMERA), "A câmera mostra e processa a luz disponível localmente. Imagens são salvas somente ao tocar em capturar e escolher um destino.") { revision++ }
    Panel {
        Glyph(R.drawable.ic_moon)
        SectionLabel("Visão em pouca luz")
        Hint("Filtro de luminosidade e cores sobre a câmera real. Não é visão infravermelha: escuridão total continua sem detalhes.")
        Box(Modifier.fillMaxWidth().height(cameraPreviewHeight).background(Color.Black), contentAlignment = Alignment.Center) {
            if (allowed) AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            frame?.let { bitmap -> Image(bitmap.asImageBitmap(), "Prévia processada da câmera", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            if (cameraState.loading) CircularProgressIndicator()
            if (!allowed) Text("Permita a câmera para iniciar", color = Color.White)
        }
        cameraState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            OutlinedButton(onClick = { lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK },
                enabled = allowed && !cameraState.loading && cameraState.frontAvailable && cameraState.backAvailable && !saving,
                modifier = Modifier.weight(1f).heightIn(min = Space.touch)) { Text("Trocar câmera") }
            Button(onClick = { pendingExport = frame; export.launch("NEXO-visao-noturna.png") },
                enabled = allowed && cameraState.ready && frame != null && !saving && pendingExport == null,
                modifier = Modifier.weight(1f).heightIn(min = Space.touch)) { Text(if (saving) "Salvando" else "Capturar PNG") }
        }
        if (cameraState.error != null && allowed) TextButton(onClick = { revision++ }) { Text("Tentar câmera novamente") }
        Hint("Captura salva a prévia processada, limitada a 640 × 480 pixels (ou orientação equivalente). Nenhum áudio é capturado.")
    }
    Panel {
        SectionLabel("Luminosidade e tonalidade")
        Metric("Filtro gamma", "${mediaNumber(brightness.toDouble())}× · gamma ${mediaNumber(1.0 / brightness, 2)}")
        Slider(value = brightness, onValueChange = { brightness = it }, valueRange = 1f..4f, enabled = allowed)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            FilterChip(selected = tint == NightTint.GREEN, onClick = { tintName = NightTint.GREEN.name }, label = { Text("Verde") })
            FilterChip(selected = tint == NightTint.AMBER, onClick = { tintName = NightTint.AMBER.name }, label = { Text("Amarelo") })
        }
        Hint("O filtro clareia luminância já captada; pode ampliar ruído. Cores são monocromáticas e não representam a cor original dos objetos.")
    }
    Panel {
        SectionLabel("Recursos reais da câmera")
        cameraState.controlError?.let { Hint(it) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Lanterna", Modifier.weight(1f))
            Switch(cameraState.torchOn, controller::setTorch, enabled = allowed && cameraState.ready && cameraState.torchAvailable && !cameraState.controlsBusy)
        }
        if (!cameraState.torchAvailable) Hint("A câmera selecionada não oferece lanterna.")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Boost de baixa luz do hardware", Modifier.weight(1f))
            Switch(cameraState.lowLightEnabled, controller::setLowLight, enabled = allowed && cameraState.ready && cameraState.lowLightAvailable && !cameraState.controlsBusy)
        }
        Hint(cameraState.lowLightStatus)
        Hint("Boost do hardware e lanterna não ficam ativos juntos. O boost pode ficar armado e só atuar quando a câmera detectar pouca luz.")
        if (cameraState.exposureMin < cameraState.exposureMax) {
            val current = exposureSlider ?: cameraState.exposureIndex.toFloat()
            Metric("Compensação de exposição", "${mediaNumber(current * cameraState.exposureStepEv, 2)} EV")
            Slider(value = current.coerceIn(cameraState.exposureMin.toFloat(), cameraState.exposureMax.toFloat()), onValueChange = { exposureSlider = it },
                onValueChangeFinished = { exposureSlider?.let { controller.setExposure(it.roundToInt()) }; exposureSlider = null },
                valueRange = cameraState.exposureMin.toFloat()..cameraState.exposureMax.toFloat(),
                steps = (cameraState.exposureMax - cameraState.exposureMin - 1).coerceAtLeast(0),
                enabled = allowed && cameraState.ready && !cameraState.controlsBusy)
            Hint("Limites e passo EV fornecidos pela câmera. Mais exposição pode aumentar borrão e reduzir a atualização.")
        } else Hint("Compensação de exposição não disponível nesta câmera.")
    }
    Hint("Análise local em até 10 quadros por segundo. A câmera e a lanterna param em segundo plano e são liberadas ao sair da ferramenta.")
}
