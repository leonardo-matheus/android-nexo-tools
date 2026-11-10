package br.com.nexotools.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import br.com.nexotools.MainViewModel
import br.com.nexotools.R
import br.com.nexotools.domain.IrHardwareStatus
import br.com.nexotools.domain.IrProfile
import br.com.nexotools.domain.IrProfiles
import br.com.nexotools.domain.NecEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object RemoteDimensions {
    val keyHeight = 52.dp
    val compactHeight = 48.dp
    val radius = 12.dp
    val padding = 12.dp
    val gap = 8.dp
    val tightGap = 4.dp
    val sideWidth = 52.dp
}

@Composable private fun rememberIrHardware(vm: MainViewModel): State<IrHardwareStatus?> {
    val hardware = remember { mutableStateOf<IrHardwareStatus?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(vm, revision) { hardware.value = withContext(Dispatchers.IO) { vm.ir.status() } }
    return hardware
}

private data class RemoteSender(val busy: Boolean, val last: String?, val send: (String, String) -> Unit, val custom: (Int, Int, Boolean) -> Unit)
@Composable private fun rememberRemoteSender(vm: MainViewModel, hardware: IrHardwareStatus?): RemoteSender {
    var busy by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current
    fun submit(label: String, action: suspend () -> Unit) {
        if (busy || hardware?.supports(NecEncoder.CARRIER_HZ) != true) return
        busy = true
        vm.act {
            try {
                action()
                last = "$label · enviado ao emissor"
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { last = "Envio não concluído"; vm.setMessage(e.message ?: "O Android não concluiu a transmissão IR") }
            finally { busy = false }
        }
    }
    return RemoteSender(busy, last,
        { profileId, commandId -> IrProfiles.byId[profileId]?.commandsById?.get(commandId)?.let { command -> submit(command.label) { vm.ir.send(profileId, commandId) } } },
        { address, command, extended -> submit("NEC manual") { vm.ir.sendNec(address, command, extended) } },
    )
}

@Composable private fun RemoteFrame(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(RemoteDimensions.radius), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(RemoteDimensions.padding), verticalArrangement = Arrangement.spacedBy(RemoteDimensions.gap), content = content)
    }
}

@Composable private fun HardwareHeader(hardware: IrHardwareStatus?, sender: RemoteSender) {
    Row(Modifier.fillMaxWidth().heightIn(min = RemoteDimensions.compactHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RemoteDimensions.gap)) {
        if (hardware == null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Glyph(R.drawable.ic_smartphone)
        Column(Modifier.weight(1f)) {
            Text(hardware?.message ?: "Verificando emissor IR", style = MaterialTheme.typography.labelLarge, maxLines = 2)
            Text(if (sender.busy) "Transmitindo…" else sender.last ?: "Aponte para o receptor · resposta não confirmada", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

/** Functional colour tokens describe the physical remote's keys. */
private object RemoteSwatches {
    val byCommand = mapOf(
        "RED" to Color(0xFFE84545), "GREEN" to Color(0xFF71C978), "BLUE" to Color(0xFF487AE8), "WHITE" to Color(0xFFF4F7F7),
        "RED_LIGHT" to Color(0xFFF17A61), "GREEN_LIGHT" to Color(0xFFA6DE7F), "BLUE_DEEP" to Color(0xFF435AB7),
        "ORANGE" to Color(0xFFF1A14D), "TURQUOISE" to Color(0xFF76D4BC), "PURPLE" to Color(0xFFAF8ADB),
        "ORANGE_LIGHT" to Color(0xFFF4C777), "CYAN" to Color(0xFF83D6E8), "MAGENTA" to Color(0xFFDC89C8),
        "YELLOW" to Color(0xFFF1DF75), "TEAL" to Color(0xFF81CEC9), "PINK" to Color(0xFFECA8C2),
    )
}

@Composable private fun RemoteKey(label: String?, description: String, enabled: Boolean, modifier: Modifier = Modifier, colour: Color? = null, icon: Int? = null, compact: Boolean = false, onClick: () -> Unit) {
    val background = colour ?: MaterialTheme.colorScheme.surfaceVariant
    val foreground = if (colour == null) MaterialTheme.colorScheme.onSurfaceVariant else if (background.luminance() > 0.179f) Color.Black else Color.White
    Button(onClick, enabled = enabled,
        modifier = modifier.heightIn(min = if (compact) RemoteDimensions.compactHeight else RemoteDimensions.keyHeight).semantics { contentDescription = description },
        shape = RoundedCornerShape(RemoteDimensions.radius),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        colors = ButtonDefaults.buttonColors(containerColor = background, contentColor = foreground,
            disabledContainerColor = background.copy(alpha = 0.55f), disabledContentColor = foreground.copy(alpha = 0.74f)),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = RemoteDimensions.tightGap),
    ) {
        if (icon != null) Glyph(icon)
        if (icon != null && label != null) Spacer(Modifier.width(RemoteDimensions.tightGap))
        if (label != null) Text(label, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, maxLines = 2)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun UnifiedRemoteScreen(vm: MainViewModel, initialTab: String = "LED") {
    val hardware by rememberIrHardware(vm)
    val sender = rememberRemoteSender(vm, hardware)
    var tab by rememberSaveable(initialTab) { mutableStateOf(if (initialTab == "LG") "LG" else "LED") }
    var ledProfile by rememberSaveable { mutableStateOf("LED24") }
    val enabled = hardware?.supports(NecEncoder.CARRIER_HZ) == true && !sender.busy
    val profile = if (tab == "LG") IrProfiles.lgTv else requireNotNull(IrProfiles.byId[ledProfile])
    Column(verticalArrangement = Arrangement.spacedBy(RemoteDimensions.gap)) {
        HardwareHeader(hardware, sender)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().heightIn(min = RemoteDimensions.compactHeight)) {
            listOf("LED" to "LED 24", "LG" to "TV LG").forEachIndexed { index, (id, label) ->
                SegmentedButton(selected = tab == id, onClick = { tab = id }, enabled = !sender.busy,
                    shape = SegmentedButtonDefaults.itemShape(index, 2), icon = {}, label = { Text(label) })
            }
        }
        if (tab == "LED") LedBoard(profile, ledProfile, { ledProfile = it }, sender, enabled)
        else LgBoard(profile, sender, enabled)
        ManualNecPanel(profile, sender, enabled)
    }
}

// Route aliases use the same UI and authoritative sender.
@Composable fun RemoteLedScreen(vm: MainViewModel) = UnifiedRemoteScreen(vm, "LED")
@Composable fun RemoteLgScreen(vm: MainViewModel) = UnifiedRemoteScreen(vm, "LG")

@Composable private fun LedBoard(profile: IrProfile, selectedProfile: String, onProfile: (String) -> Unit, sender: RemoteSender, enabled: Boolean) {
    val labels = mapOf("BRIGHT_UP" to "Brilho +", "BRIGHT_DOWN" to "Brilho −", "OFF" to "OFF", "ON" to "ON",
        "RED" to "R", "GREEN" to "G", "BLUE" to "B", "WHITE" to "W", "RED_LIGHT" to "Coral", "GREEN_LIGHT" to "Verde\nclaro",
        "BLUE_DEEP" to "Azul\nintenso", "ORANGE_LIGHT" to "Âmbar", "TEAL" to "Verde\nágua")
    RemoteFrame {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RemoteDimensions.gap)) {
            Text("Perfil", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf("LED24" to "00FF", "LED24_F7" to "00F7").forEach { (id, label) -> FilterChip(selected = selectedProfile == id, onClick = { onProfile(id) }, enabled = !sender.busy, label = { Text(label) }) }
            Spacer(Modifier.weight(1f))
            Text("24 teclas", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        profile.commands.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RemoteDimensions.gap)) {
                row.forEach { command ->
                    val colour = RemoteSwatches.byCommand[command.id] ?: when (command.id) {
                        "ON" -> Palette.Green
                        "OFF" -> MaterialTheme.colorScheme.errorContainer
                        else -> null
                    }
                    RemoteKey(labels[command.id] ?: command.label, command.label, enabled, Modifier.weight(1f), colour) { sender.send(profile.id, command.id) }
                }
            }
        }
        Hint("00FF e 00F7 são variantes; selecione a compatível com seu controlador.")
    }
}

@Composable private fun LgKey(profile: IrProfile, sender: RemoteSender, id: String, enabled: Boolean, modifier: Modifier = Modifier, label: String? = null, icon: Int? = null) {
    val command = requireNotNull(profile.commandsById[id])
    RemoteKey(if (icon == null) label ?: command.label else label, command.label, enabled, modifier,
        colour = when (id) { "POWER" -> MaterialTheme.colorScheme.errorContainer; "OK" -> MaterialTheme.colorScheme.primary; else -> null }, icon = icon, compact = true) { sender.send(profile.id, id) }
}

@Composable private fun DPad(profile: IrProfile, sender: RemoteSender, enabled: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(RemoteDimensions.tightGap)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RemoteDimensions.tightGap)) {
            Spacer(Modifier.weight(1f)); LgKey(profile, sender, "UP", enabled, Modifier.weight(1f), icon = R.drawable.ic_arrow_up); Spacer(Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RemoteDimensions.tightGap)) {
            LgKey(profile, sender, "LEFT", enabled, Modifier.weight(1f), icon = R.drawable.ic_arrow_left)
            LgKey(profile, sender, "OK", enabled, Modifier.weight(1f), label = "OK")
            LgKey(profile, sender, "RIGHT", enabled, Modifier.weight(1f), icon = R.drawable.ic_arrow_right)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RemoteDimensions.tightGap)) {
            Spacer(Modifier.weight(1f)); LgKey(profile, sender, "DOWN", enabled, Modifier.weight(1f), icon = R.drawable.ic_arrow_down); Spacer(Modifier.weight(1f))
        }
    }
}

@Composable private fun SideRocker(profile: IrProfile, sender: RemoteSender, enabled: Boolean, volume: Boolean) {
    Column(Modifier.width(RemoteDimensions.sideWidth), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(RemoteDimensions.tightGap)) {
        Text(if (volume) "VOL" else "CANAL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LgKey(profile, sender, if (volume) "VOL_UP" else "CH_UP", enabled, Modifier.fillMaxWidth(), label = "+")
        LgKey(profile, sender, if (volume) "VOL_DOWN" else "CH_DOWN", enabled, Modifier.fillMaxWidth(), label = "−")
    }
}

@Composable private fun LgBoard(profile: IrProfile, sender: RemoteSender, enabled: Boolean) {
    RemoteFrame {
        Text("LG · NEC clássico", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RemoteDimensions.gap)) {
            LgKey(profile, sender, "POWER", enabled, Modifier.weight(1f), label = "Ligar", icon = R.drawable.ic_power)
            LgKey(profile, sender, "INPUT", enabled, Modifier.weight(1f), label = "Entrada")
            LgKey(profile, sender, "MUTE", enabled, Modifier.weight(1f), label = "Mudo")
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 280.dp) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RemoteDimensions.gap)) {
                SideRocker(profile, sender, enabled, true)
                DPad(profile, sender, enabled, Modifier.weight(1f))
                SideRocker(profile, sender, enabled, false)
            } else Column(verticalArrangement = Arrangement.spacedBy(RemoteDimensions.gap)) {
                DPad(profile, sender, enabled, Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { SideRocker(profile, sender, enabled, true); SideRocker(profile, sender, enabled, false) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(RemoteDimensions.tightGap)) {
            (1..9).toList().chunked(3).forEach { numbers -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RemoteDimensions.gap)) { numbers.forEach { number -> LgKey(profile, sender, "DIGIT_$number", enabled, Modifier.weight(1f)) } } }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RemoteDimensions.gap)) { Spacer(Modifier.weight(1f)); LgKey(profile, sender, "DIGIT_0", enabled, Modifier.weight(1f)); Spacer(Modifier.weight(1f)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RemoteDimensions.gap)) {
            LgKey(profile, sender, "MENU", enabled, Modifier.weight(1f))
            LgKey(profile, sender, "BACK", enabled, Modifier.weight(1f))
            LgKey(profile, sender, "EXIT", enabled, Modifier.weight(1f))
        }
    }
}

@Composable private fun ManualNecPanel(profile: IrProfile, sender: RemoteSender, enabled: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable(profile.id) { mutableStateOf(profile.address.toString(16).uppercase()) }
    var command by rememberSaveable { mutableStateOf("") }
    var extended by rememberSaveable(profile.id) { mutableStateOf(profile.extendedAddress) }
    var error by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = RemoteDimensions.compactHeight)) {
        Text("Avançado"); Spacer(Modifier.weight(1f)); Glyph(R.drawable.ic_chevron_right, modifier = Modifier.rotate(if (expanded) 90f else 0f))
    }
    if (expanded) RemoteFrame {
        Hint("Endereço e comando NEC documentados em hexadecimal, na notação LSB.")
        OutlinedTextField(address, { address = it.take(6); error = null }, label = { Text("Endereço NEC · hex LSB") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(command, { command = it.take(4); error = null }, label = { Text("Comando · hex de 8 bits") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Endereço estendido · 16 bits", Modifier.weight(1f)); Switch(extended, { extended = it }) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = {
            runCatching {
                val a = requireNotNull(address.trim().removePrefix("0x").removePrefix("0X").toIntOrNull(16)) { "Informe o endereço hexadecimal" }
                val c = requireNotNull(command.trim().removePrefix("0x").removePrefix("0X").toIntOrNull(16)) { "Informe o comando hexadecimal" }
                NecEncoder.frame(a, c, extended); a to c
            }.onSuccess { (a, c) -> error = null; sender.custom(a, c, extended) }.onFailure { error = it.message ?: "Código NEC inválido" }
        }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = RemoteDimensions.compactHeight)) { Text("Transmitir NEC manual") }
        Hint("Configuração desta tela; agendamentos mantêm os perfis nomeados.")
    }
}
