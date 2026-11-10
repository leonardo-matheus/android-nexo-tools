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
import androidx.compose.ui.text.style.TextOverflow
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
        Hint("O ganho vale só para este player; o volume do Android é independente.")
        Button(onClick = { openAudio.launch(arrayOf("audio/*")) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth().heightIn(min = Space.touch)) { Text("Selecionar áudio") }
        state.fileName?.let { Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        if (state.loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Hint("Preparando áudio") }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Button(onClick = player::togglePlayback, enabled = state.ready, modifier = Modifier.heightIn(min = Space.touch)) {
                Glyph(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play)
                Spacer(Modifier.width(Space.sm)); Text(if (state.playing) "Pausar" else "Reproduzir")
            }
            OutlinedButton(onClick = { player.reset(); seeking = null }, enabled = state.ready, modifier = Modifier.heightIn(min = Space.touch)) { Text("Restaurar") }
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
        InstrumentReading("${mediaNumber(AudioGainMath.amplitudePercent(state.gainDb), 0)}%")
        Metric("Ganho alvo", "+${mediaNumber(state.gainDb.toDouble())} dB")
        Slider(value = state.gainDb, onValueChange = player::setGain, valueRange = 0f..12f, steps = 23,
            enabled = state.ready && state.effectAvailable)
        Hint("Amplitude nominal: 100% a 398% (0 a 12 dB), em passos de 0,5 dB.")
        Metric("Efeito", when { !state.ready -> "Selecione um áudio"; state.effectAvailable -> "Disponível"; else -> "Não disponível" })
        state.effectError?.let { Hint(it) }
        Hint("Aumente aos poucos: pode distorcer ou ficar muito alto. Sem proteção garantida da audição ou do alto-falante.")
    }
    Hint("Sem microfone ou acesso amplo ao armazenamento. Pausa ao sair, perder o foco de áudio ou remover fones, sem retomar automaticamente.")
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
    PermissionPanel(arrayOf(Manifest.permission.CAMERA), "A prévia é processada no aparelho. Só salva imagens quando você captura e escolhe um destino.") { revision++ }
    Panel {
        Glyph(R.drawable.ic_moon)
        SectionLabel("Visão em pouca luz")
        Hint("Clareia a luz captada. Não é infravermelho e não revela detalhes na escuridão total.")
        Box(Modifier.fillMaxWidth().height(cameraPreviewHeight).background(Color.Black), contentAlignment = Alignment.Center) {
            if (allowed) AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            frame?.let { bitmap -> Image(bitmap.asImageBitmap(), "Prévia processada da câmera", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            if (cameraState.loading) CircularProgressIndicator()
            if (!allowed) Text("Permita a câmera para iniciar", color = Color.White)
        }
        cameraState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            OutlinedButton(onClick = { lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK },
                enabled = allowed && !cameraState.loading && cameraState.frontAvailable && cameraState.backAvailable && !saving,
                modifier = Modifier.heightIn(min = Space.touch)) { Text("Trocar câmera") }
            Button(onClick = { pendingExport = frame; export.launch("NEXO-visao-noturna.png") },
                enabled = allowed && cameraState.ready && frame != null && !saving && pendingExport == null,
                modifier = Modifier.heightIn(min = Space.touch)) { Text(if (saving) "Salvando" else "Capturar") }
        }
        if (cameraState.error != null && allowed) TextButton(onClick = { revision++ }) { Text("Tentar novamente") }
        Hint("PNG da prévia: até 640 × 480 px, conforme orientação. Sem áudio.")
    }
    Panel {
        SectionLabel("Luminosidade e tonalidade")
        Metric("Clareamento", "${mediaNumber(brightness.toDouble())}×")
        Slider(value = brightness, onValueChange = { brightness = it }, valueRange = 1f..4f, enabled = allowed)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            FilterChip(selected = tint == NightTint.GREEN, onClick = { tintName = NightTint.GREEN.name }, label = { Text("Verde") })
            FilterChip(selected = tint == NightTint.AMBER, onClick = { tintName = NightTint.AMBER.name }, label = { Text("Amarelo") })
        }
        Hint("Pode ampliar ruído. As tonalidades não representam as cores reais.")
    }
    Panel {
        SectionLabel("Câmera")
        cameraState.controlError?.let { Hint(it) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Lanterna", Modifier.weight(1f))
            Switch(cameraState.torchOn, controller::setTorch, enabled = allowed && cameraState.ready && cameraState.torchAvailable && !cameraState.controlsBusy)
        }
        if (!cameraState.torchAvailable) Hint("A câmera selecionada não oferece lanterna.")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Baixa luz (hardware)", Modifier.weight(1f))
            Switch(cameraState.lowLightEnabled, controller::setLowLight, enabled = allowed && cameraState.ready && cameraState.lowLightAvailable && !cameraState.controlsBusy)
        }
        Hint(cameraState.lowLightStatus)
        Hint("Baixa luz e lanterna são alternativas. O hardware decide quando o boost atua.")
        if (cameraState.exposureMin < cameraState.exposureMax) {
            val current = exposureSlider ?: cameraState.exposureIndex.toFloat()
            Metric("Exposição", "${mediaNumber(current * cameraState.exposureStepEv, 2)} EV")
            Slider(value = current.coerceIn(cameraState.exposureMin.toFloat(), cameraState.exposureMax.toFloat()), onValueChange = { exposureSlider = it },
                onValueChangeFinished = { exposureSlider?.let { controller.setExposure(it.roundToInt()) }; exposureSlider = null },
                valueRange = cameraState.exposureMin.toFloat()..cameraState.exposureMax.toFloat(),
                steps = (cameraState.exposureMax - cameraState.exposureMin - 1).coerceAtLeast(0),
                enabled = allowed && cameraState.ready && !cameraState.controlsBusy)
            Hint("Limites da câmera. Mais exposição pode borrar ou reduzir a atualização.")
        } else Hint("Compensação de exposição não disponível nesta câmera.")
    }
    Hint("Análise local em até 10 quadros por segundo. A câmera e a lanterna param em segundo plano e são liberadas ao sair da ferramenta.")
}
