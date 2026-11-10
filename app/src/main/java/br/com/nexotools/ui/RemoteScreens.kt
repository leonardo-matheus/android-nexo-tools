package br.com.nexotools.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private object RemoteDimensions {
    val key = 48.dp
    val largeKey = 76.dp
    val header = 48.dp
    val status = 24.dp
    val gap = 8.dp
    val tightGap = 4.dp
    val radius = 16.dp
    val pad = 8.dp
    val dpad = 160.dp
    val smallDpad = 144.dp
    val rocker = 52.dp
    val tvPortrait = 328.dp
    val tvLandscapeWidth = 480.dp
    val dialogWidth = 360.dp
    val wideDialogWidth = 600.dp
    val dialogRail = 152.dp
}

private data class CommandGrid(val columns: Int, val capacity: Int)
private fun commandGrid(width: Dp, height: Dp, count: Int, preferredColumns: Int? = null): CommandGrid? {
    if (width < RemoteDimensions.key || height < RemoteDimensions.key) return null
    if (preferredColumns != null) {
        val rows = (count + preferredColumns - 1) / preferredColumns
        if (width >= RemoteDimensions.key * preferredColumns && height >= RemoteDimensions.key * rows)
            return CommandGrid(preferredColumns, count)
    }
    val columns = (width / RemoteDimensions.key).toInt().coerceIn(1, 8)
    val rows = (height / RemoteDimensions.key).toInt().coerceAtLeast(1)
    return CommandGrid(columns, minOf(count, columns * rows))
}
private fun tvFits(width: Dp, height: Dp, landscape: Boolean): Boolean =
    if (landscape) width >= RemoteDimensions.tvLandscapeWidth && height >= RemoteDimensions.dpad
    else width >= 264.dp && height >= RemoteDimensions.tvPortrait

@Composable private fun PageControl(page: Int, count: Int, onNext: () -> Unit) {
    IconButton(onClick = onNext, enabled = count > 1, modifier = Modifier.size(RemoteDimensions.key).semantics {
        contentDescription = "Próxima página de comandos"
        stateDescription = "Página ${page + 1} de $count"
    }) {
        InstrumentReading("${page + 1}/$count", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun RemoteMenu(tab: String, onTab: (String) -> Unit, onSettings: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.size(RemoteDimensions.key)) {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(RemoteDimensions.key)) { Glyph(R.drawable.ic_settings_2, "Selecionar e configurar controle IR") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            listOf("LED" to "LED 24", "LG" to "TV LG").forEach { (id, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; onTab(id) },
                    trailingIcon = { if (tab == id) Glyph(R.drawable.ic_check) })
            }
            DropdownMenuItem(text = { Text("Configurar controle IR") }, onClick = { expanded = false; onSettings() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun RemoteHeader(tab: String, onTab: (String) -> Unit, width: Dp, page: Int, count: Int, onNext: () -> Unit, onSettings: () -> Unit) {
    val compact = width < 360.dp || LocalDensity.current.fontScale > 1.3f
    Row(Modifier.fillMaxWidth().height(RemoteDimensions.header), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RemoteDimensions.tightGap)) {
        if (width < 240.dp) {
            Text(if (tab == "LG") "TV LG" else "LED", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (count > 1) PageControl(page, count, onNext)
            RemoteMenu(tab, onTab, onSettings)
        } else {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f).height(RemoteDimensions.header)) {
                listOf("LED" to "LED 24", "LG" to "TV LG").forEachIndexed { index, (id, label) ->
                    SegmentedButton(selected = tab == id, onClick = { onTab(id) }, modifier = Modifier.semantics { contentDescription = "Selecionar $label" },
                        shape = SegmentedButtonDefaults.itemShape(index, 2), icon = {}) { Text(if (compact) id else label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            if (count > 1) PageControl(page, count, onNext)
            IconButton(onClick = onSettings, modifier = Modifier.size(RemoteDimensions.key)) { Glyph(R.drawable.ic_settings_2, "Configurar controle IR") }
        }
    }
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
    LaunchedEffect(vm, revision) {
        hardware.value = withContext(Dispatchers.IO) { vm.ir.status() }
    }
    return hardware
}

/** Serializes taps without exposing transmission state to the UI. */
private class RemoteSender(
    private val vm: MainViewModel,
    private val scope: CoroutineScope,
    private val canTransmit: () -> Boolean,
) {
    private val inFlight = AtomicBoolean(false)
    private var transmission: Job? = null

    private fun submit(action: suspend () -> Unit) {
        if (!canTransmit() || !inFlight.compareAndSet(false, true)) return
        val job = scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { vm.setMessage(e.message ?: "O Android não concluiu o envio IR") }
        }
        transmission = job
        job.invokeOnCompletion { inFlight.set(false) }
    }
    fun send(profile: String, command: String) = submit { vm.ir.send(profile, command) }
    fun custom(address: Int, command: Int, extended: Boolean) = submit { vm.ir.sendNec(address, command, extended) }
    fun cancel() { transmission?.cancel() }
}

@Composable private fun rememberRemoteSender(vm: MainViewModel, hardware: IrHardwareStatus?): RemoteSender {
    val currentHardware by rememberUpdatedState(hardware)
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val sender = remember(vm, owner, scope) {
        RemoteSender(vm, scope) {
            owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                currentHardware?.supports(NecEncoder.CARRIER_HZ) == true
        }
    }
    DisposableEffect(owner, sender) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) sender.cancel() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); sender.cancel() }
    }
    return sender
}

/** Functional colours of the physical LED remote, independent of theme. */
private object RemoteSwatches {
    val byCommand = mapOf(
        "RED" to Color(0xFFE84545), "GREEN" to Color(0xFF71C978), "BLUE" to Color(0xFF487AE8), "WHITE" to Color(0xFFF4F7F7),
        "RED_LIGHT" to Color(0xFFF17A61), "GREEN_LIGHT" to Color(0xFFA6DE7F), "BLUE_DEEP" to Color(0xFF435AB7),
        "ORANGE" to Color(0xFFF1A14D), "TURQUOISE" to Color(0xFF76D4BC), "PURPLE" to Color(0xFFAF8ADB),
        "ORANGE_LIGHT" to Color(0xFFF4C777), "CYAN" to Color(0xFF83D6E8), "MAGENTA" to Color(0xFFDC89C8),
        "YELLOW" to Color(0xFFF1DF75), "TEAL" to Color(0xFF81CEC9), "PINK" to Color(0xFFECA8C2),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun UnifiedRemoteScreen(vm: MainViewModel, initialTab: String = "LED", modifier: Modifier = Modifier) {
    val hardware by rememberIrHardware(vm)
    val sender = rememberRemoteSender(vm, hardware)
    var tab by rememberSaveable(initialTab) { mutableStateOf(if (initialTab == "LG") "LG" else "LED") }
    var ledProfile by rememberSaveable { mutableStateOf("LED24") }
    var page by rememberSaveable(tab) { mutableIntStateOf(0) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var digits by rememberSaveable { mutableStateOf(false) }
    val enabled = hardware?.supports(NecEncoder.CARRIER_HZ) == true
    val profile = if (tab == "LG") IrProfiles.lgTv else IrProfiles.byId[ledProfile] ?: IrProfiles.led24
    BoxWithConstraints(modifier.fillMaxSize().padding(RemoteDimensions.pad)) {
        val boardWidth = maxWidth
        val availableHeight = maxHeight
        val landscape = maxWidth > maxHeight
        val normalLedColumns = if (landscape && maxWidth >= RemoteDimensions.key * 8 + RemoteDimensions.gap * 7) 8 else 4
        val shortViewport = availableHeight < RemoteDimensions.header + RemoteDimensions.gap + RemoteDimensions.key
        val statusRowHeight = with(LocalDensity.current) { MaterialTheme.typography.labelSmall.lineHeight.toDp() }.coerceAtLeast(RemoteDimensions.status)
        val showStatus = !enabled && !shortViewport && availableHeight >= RemoteDimensions.header + statusRowHeight + RemoteDimensions.gap * 2 + RemoteDimensions.key
        val chromeHeight = RemoteDimensions.header + RemoteDimensions.gap + if (showStatus) statusRowHeight + RemoteDimensions.gap else 0.dp
        val boardHeight = if (shortViewport) availableHeight else (availableHeight - chromeHeight).coerceAtLeast(0.dp)
        val railWidth = RemoteDimensions.key * 2 + RemoteDimensions.tightGap
        val actualWidth = if (shortViewport) (boardWidth - railWidth - RemoteDimensions.tightGap).coerceAtLeast(0.dp) else boardWidth
        val fullTv = tab == "LG" && !shortViewport && tvFits(actualWidth, boardHeight, landscape)
        val grid = commandGrid(actualWidth, boardHeight, if (tab == "LED") profile.commands.size else 16, if (tab == "LED") normalLedColumns else null)
        val pageCount = if (fullTv || grid == null) 1 else ((if (tab == "LED") profile.commands.size else 16) + grid.capacity - 1) / grid.capacity
        val visiblePage = page.mod(pageCount)
        val onNext = { page = (visiblePage + 1) % pageCount }
        val content: @Composable () -> Unit = {
            if (fullTv) TvBoard(profile, sender, enabled, actualWidth, boardHeight, landscape, false, 0) { digits = true }
            else if (grid != null && tab == "LED") LedBoard(profile, sender, enabled, actualWidth, boardHeight, grid, visiblePage)
            else if (grid != null) CompactTvPage(profile, sender, enabled, actualWidth, boardHeight, visiblePage) { digits = true }
        }
        when {
            grid == null && !fullTv -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Amplie a janela", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            shortViewport -> Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RemoteDimensions.tightGap)) {
                Row(Modifier.width(railWidth), horizontalArrangement = Arrangement.spacedBy(RemoteDimensions.tightGap)) {
                    RemoteMenu(tab, { tab = it }, { settings = true })
                    PageControl(visiblePage, pageCount, onNext)
                }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { content() }
            }
            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(RemoteDimensions.gap)) {
                RemoteHeader(tab, { tab = it }, boardWidth, visiblePage, pageCount, onNext, { settings = true })
                if (showStatus) Row(Modifier.fillMaxWidth().height(statusRowHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RemoteDimensions.gap)) {
                    if (hardware == null) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Glyph(R.drawable.ic_smartphone, modifier = Modifier.size(16.dp))
                    Text(hardware?.message ?: "Consultando emissor IR", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { content() }
            }
        }
    }
    if (digits) NumberDialog(profile = IrProfiles.lgTv, sender = sender, enabled = enabled) { digits = false }
    if (settings) RemoteSettingsDialog(vm, profile, tab == "LED", ledProfile, { ledProfile = it }, sender, enabled) { settings = false }
}

// Macro routes retain their existing device selection.
@Composable fun RemoteLedScreen(vm: MainViewModel) = UnifiedRemoteScreen(vm, "LED")
@Composable fun RemoteLgScreen(vm: MainViewModel) = UnifiedRemoteScreen(vm, "LG")

@Composable private fun LedBoard(profile: IrProfile, sender: RemoteSender, enabled: Boolean, width: Dp, height: Dp, grid: CommandGrid, page: Int) {
    val commands = profile.commands.chunked(grid.capacity)[page]
    val count = grid.columns
    val rows = (commands.size + count - 1) / count
    val horizontalGap = minOf(RemoteDimensions.gap, ((width - RemoteDimensions.key * count) / (count - 1).coerceAtLeast(1)).coerceAtLeast(0.dp))
    val verticalGap = minOf(RemoteDimensions.gap, ((height - RemoteDimensions.key * rows) / (rows - 1).coerceAtLeast(1)).coerceAtLeast(0.dp))
    val keyHeight = ((height - verticalGap * (rows - 1)) / rows).coerceIn(RemoteDimensions.key, RemoteDimensions.largeKey)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(verticalGap)) {
        commands.chunked(count).forEach { keys ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(horizontalGap)) {
                keys.forEach { command ->
                    val colour = RemoteSwatches.byCommand[command.id] ?: when (command.id) {
                        "ON" -> Palette.Green
                        "OFF" -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    val foreground = if (colour.luminance() > 0.179f) Color.Black else Color.White
                    Button(onClick = { sender.send(profile.id, command.id) }, enabled = enabled,
                        modifier = Modifier.weight(1f).height(keyHeight).semantics { contentDescription = command.label },
                        shape = RoundedCornerShape(RemoteDimensions.radius), contentPadding = PaddingValues(2.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colour, contentColor = foreground,
                            disabledContainerColor = colour.copy(alpha = 0.55f), disabledContentColor = foreground.copy(alpha = 0.74f)),
                    ) {
                        when (command.id) {
                            "BRIGHT_UP" -> Glyph(R.drawable.ic_arrow_up)
                            "BRIGHT_DOWN" -> Glyph(R.drawable.ic_arrow_down)
                            "ON", "OFF" -> Glyph(R.drawable.ic_power)
                            "RED", "GREEN", "BLUE", "WHITE" -> Text(command.id.first().toString(), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            else -> if (command.id !in RemoteSwatches.byCommand) Text(command.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                repeat(count - keys.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable private fun TvKey(profile: IrProfile, sender: RemoteSender, id: String?, enabled: Boolean, icon: Int? = null, label: String? = null, size: Dp = RemoteDimensions.key, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val command = id?.let { profile.commandsById[it] }
    val background = when (id) { "POWER" -> MaterialTheme.colorScheme.errorContainer; "OK" -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.surfaceVariant }
    val foreground = when (id) { "POWER" -> MaterialTheme.colorScheme.onErrorContainer; "OK" -> MaterialTheme.colorScheme.onPrimary; else -> MaterialTheme.colorScheme.onSurfaceVariant }
    Button(onClick = { if (onClick != null) onClick() else if (id != null) sender.send(profile.id, id) }, enabled = if (id == null) true else enabled,
        modifier = modifier.size(size).semantics { contentDescription = command?.let { "TV: " + it.label } ?: "Teclado numérico da TV" }, shape = CircleShape, contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = background, contentColor = foreground,
            disabledContainerColor = background.copy(alpha = 0.55f), disabledContentColor = foreground.copy(alpha = 0.74f)),
    ) {
        if (icon != null) Glyph(icon)
        else Text(label ?: command?.label.orEmpty(), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun TvActions(profile: IrProfile, sender: RemoteSender, enabled: Boolean, showDigits: () -> Unit, compact: Boolean = false) {
    if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(RemoteDimensions.gap), horizontalAlignment = Alignment.CenterHorizontally) {
            TvActionRow(profile, sender, enabled, showDigits, true)
            TvActionRow(profile, sender, enabled, showDigits, false)
            TvKey(profile, sender, "MUTE", enabled, icon = R.drawable.ic_volume_x)
        }
    } else TvActionRow(profile, sender, enabled, showDigits, true)
}
@Composable private fun TvActionRow(profile: IrProfile, sender: RemoteSender, enabled: Boolean, showDigits: () -> Unit, top: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        if (top) {
            TvKey(profile, sender, "POWER", enabled, icon = R.drawable.ic_power)
            TvKey(profile, sender, "INPUT", enabled, icon = R.drawable.ic_monitor)
            TvKey(profile, sender, "MENU", enabled, icon = R.drawable.ic_menu)
        } else {
            TvKey(profile, sender, "BACK", enabled, icon = R.drawable.ic_arrow_left)
            TvKey(profile, sender, null, enabled, icon = R.drawable.ic_keyboard, onClick = showDigits)
            TvKey(profile, sender, "EXIT", enabled, icon = R.drawable.ic_log_out)
        }
    }
}

@Composable private fun DPad(profile: IrProfile, sender: RemoteSender, enabled: Boolean, diameter: Dp = RemoteDimensions.dpad) {
    val target = (diameter / 3).coerceIn(RemoteDimensions.key, 80.dp)
    Box(Modifier.size(diameter).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
        TvKey(profile, sender, "UP", enabled, icon = R.drawable.ic_arrow_up, size = target, modifier = Modifier.align(Alignment.TopCenter))
        TvKey(profile, sender, "LEFT", enabled, icon = R.drawable.ic_arrow_left, size = target, modifier = Modifier.align(Alignment.CenterStart))
        TvKey(profile, sender, "RIGHT", enabled, icon = R.drawable.ic_arrow_right, size = target, modifier = Modifier.align(Alignment.CenterEnd))
        TvKey(profile, sender, "DOWN", enabled, icon = R.drawable.ic_arrow_down, size = target, modifier = Modifier.align(Alignment.BottomCenter))
        TvKey(profile, sender, "OK", enabled, label = "OK", size = target, modifier = Modifier.align(Alignment.Center))
    }
}
@Composable private fun Rocker(profile: IrProfile, sender: RemoteSender, enabled: Boolean, volume: Boolean) {
    Column(Modifier.width(RemoteDimensions.rocker), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(RemoteDimensions.tightGap)) {
        Text(if (volume) "VOL" else "CH", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        TvKey(profile, sender, if (volume) "VOL_UP" else "CH_UP", enabled, label = "+")
        TvKey(profile, sender, if (volume) "VOL_DOWN" else "CH_DOWN", enabled, label = "−")
    }
}
@Composable private fun TvCenter(profile: IrProfile, sender: RemoteSender, enabled: Boolean, diameter: Dp) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
        Rocker(profile, sender, enabled, true)
        DPad(profile, sender, enabled, diameter)
        Rocker(profile, sender, enabled, false)
    }
}

@Composable private fun CompactTvPage(profile: IrProfile, sender: RemoteSender, enabled: Boolean, width: Dp, height: Dp, page: Int, showDigits: () -> Unit) {
    val keys = listOf("LEFT", "UP", "OK", "DOWN", "RIGHT", "VOL_UP", "VOL_DOWN", "MUTE", "CH_UP", "CH_DOWN", "POWER", "INPUT", "MENU", "BACK", "DIGITS", "EXIT")
    val grid = commandGrid(width, height, keys.size) ?: return
    val pages = keys.chunked(grid.capacity)
    val shown = pages[page.mod(pages.size)]
    val rows = (shown.size + grid.columns - 1) / grid.columns
    val horizontalGap = minOf(RemoteDimensions.gap, ((width - RemoteDimensions.key * grid.columns) / (grid.columns - 1).coerceAtLeast(1)).coerceAtLeast(0.dp))
    val verticalGap = minOf(RemoteDimensions.gap, ((height - RemoteDimensions.key * rows) / (rows - 1).coerceAtLeast(1)).coerceAtLeast(0.dp))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(verticalGap)) {
        shown.chunked(grid.columns).forEach { group ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(horizontalGap)) {
                group.forEach { id -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    when (id) {
                        "LEFT" -> TvKey(profile, sender, id, enabled, icon = R.drawable.ic_arrow_left)
                        "UP" -> TvKey(profile, sender, id, enabled, icon = R.drawable.ic_arrow_up)
                        "OK" -> TvKey(profile, sender, id, enabled, label = "OK")
                        "DOWN" -> TvKey(profile, sender, id, enabled, icon = R.drawable.ic_arrow_down)
                        "RIGHT" -> TvKey(profile, sender, id, enabled, icon = R.drawable.ic_arrow_right)
                        "VOL_UP" -> TvKey(profile, sender, id, enabled, label = "V+")
                        "VOL_DOWN" -> TvKey(profile, sender, id, enabled, label = "V−")
                        "CH_UP" -> TvKey(profile, sender, id, enabled, label = "C+")
                        "CH_DOWN" -> TvKey(profile, sender, id, enabled, label = "C−")
                        "POWER" -> TvKey(profile, sender, id, enabled, icon = R.drawable.ic_power)
                        "INPUT" -> TvKey(profile, sender, id, enabled, icon = R.drawable.ic_monitor)
                        "MENU" -> TvKey(profile, sender, id, enabled, icon = R.drawable.ic_menu)
                        "BACK" -> TvKey(profile, sender, id, enabled, icon = R.drawable.ic_arrow_left)
                        "EXIT" -> TvKey(profile, sender, id, enabled, icon = R.drawable.ic_log_out)
                        "MUTE" -> TvKey(profile, sender, id, enabled, icon = R.drawable.ic_volume_x)
                        else -> TvKey(profile, sender, null, enabled, icon = R.drawable.ic_keyboard, onClick = showDigits)
                    }
                } }
                repeat(grid.columns - group.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable private fun TvBoard(profile: IrProfile, sender: RemoteSender, enabled: Boolean, width: Dp, height: Dp, landscape: Boolean, paged: Boolean, page: Int, showDigits: () -> Unit) {
    if (paged) CompactTvPage(profile, sender, enabled, width, height, page, showDigits)
    else if (landscape && width >= RemoteDimensions.tvLandscapeWidth) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            Box(Modifier.width(160.dp)) { TvActions(profile, sender, enabled, showDigits, true) }
            DPad(profile, sender, enabled)
            Row(horizontalArrangement = Arrangement.spacedBy(RemoteDimensions.gap)) { Rocker(profile, sender, enabled, true); Rocker(profile, sender, enabled, false) }
        }
    } else if (height >= 460.dp) Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        Column(verticalArrangement = Arrangement.spacedBy(RemoteDimensions.gap)) {
            TvActionRow(profile, sender, enabled, showDigits, true)
            TvActionRow(profile, sender, enabled, showDigits, false)
        }
        val diameter = minOf(width * 0.75f, height * 0.42f, 280.dp).coerceAtLeast(RemoteDimensions.dpad)
        DPad(profile, sender, enabled, diameter)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            Rocker(profile, sender, enabled, true)
            TvKey(profile, sender, "MUTE", enabled, icon = R.drawable.ic_volume_x)
            Rocker(profile, sender, enabled, false)
        }
    } else Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(RemoteDimensions.gap)) {
        TvActionRow(profile, sender, enabled, showDigits, true)
        TvCenter(profile, sender, enabled, RemoteDimensions.dpad)
        TvKey(profile, sender, "MUTE", enabled, icon = R.drawable.ic_volume_x)
        TvActionRow(profile, sender, enabled, showDigits, false)
    }
}

@Composable private fun NumberDialog(profile: IrProfile, sender: RemoteSender, enabled: Boolean, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(RemoteDimensions.pad), contentAlignment = Alignment.Center) {
            val innerWidth = (minOf(maxWidth, RemoteDimensions.dialogWidth) - Space.lg * 2).coerceAtLeast(0.dp)
            val preferredColumns = if (maxWidth > maxHeight) 5 else 3
            val columns = minOf(preferredColumns, (innerWidth / RemoteDimensions.key).toInt().coerceAtLeast(1))
            Surface(Modifier.widthIn(max = RemoteDimensions.dialogWidth).heightIn(max = maxHeight).fillMaxWidth(), shape = RoundedCornerShape(Dimensions.panelRadius)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(Space.lg), verticalArrangement = Arrangement.spacedBy(RemoteDimensions.gap)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Teclado da TV", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = onDismiss) { Text("Fechar") }
                    }
                    ((1..9).toList() + 0).chunked(columns).forEach { digits ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            digits.forEach { digit -> TvKey(profile, sender, "DIGIT_" + digit, enabled, label = digit.toString()) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun RemoteSettingsDialog(vm: MainViewModel, profile: IrProfile, led: Boolean, selectedLed: String, onProfile: (String) -> Unit, sender: RemoteSender, enabled: Boolean, onDismiss: () -> Unit) {
    var section by rememberSaveable { mutableStateOf("PROFILE") }
    var address by rememberSaveable(profile.id) { mutableStateOf(profile.address.toString(16).uppercase()) }
    var command by rememberSaveable { mutableStateOf("") }
    var extended by rememberSaveable(profile.id) { mutableStateOf(profile.extendedAddress) }
    var error by remember(profile.id) { mutableStateOf<String?>(null) }
    val errorRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(error) { if (error != null) errorRequester.bringIntoView() }
    val sections: @Composable () -> Unit = {
        if (led) listOf("PROFILE" to "Perfil LED", "NEC" to "NEC manual").forEach { (id, label) ->
            TextButton(onClick = { section = id }, modifier = Modifier.heightIn(min = RemoteDimensions.key)) { Text(label, color = if (section == id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
        } else TextButton(onClick = { section = "NEC" }, modifier = Modifier.heightIn(min = RemoteDimensions.key)) { Text("NEC manual") }
    }
    val content: @Composable () -> Unit = {
        if (section == "PROFILE") {
            if (led) listOf(IrProfiles.led24, IrProfiles.led24F7).forEach { option ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selectedLed == option.id, onClick = { onProfile(option.id) })
                    TextButton(onClick = { onProfile(option.id) }) { Text(option.title) }
                }
            } else Text(profile.title, style = MaterialTheme.typography.bodyMedium)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RemoteDimensions.gap)) {
                OutlinedTextField(address, { address = it.take(6); error = null }, isError = error != null, label = { Text("Endereço") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.weight(1f))
                OutlinedTextField(command, { command = it.take(4); error = null }, isError = error != null, label = { Text("Comando") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.weight(1f))
            }
            error?.let { Text(it, Modifier.bringIntoViewRequester(errorRequester), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("NEC estendido", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Switch(checked = extended, onCheckedChange = { extended = it; error = null })
            }
            Button(onClick = {
                runCatching {
                    val a = requireNotNull(address.trim().removePrefix("0x").removePrefix("0X").toIntOrNull(16)) { "Informe o endereço hexadecimal" }
                    val c = requireNotNull(command.trim().removePrefix("0x").removePrefix("0X").toIntOrNull(16)) { "Informe o comando hexadecimal" }
                    NecEncoder.frame(a, c, extended); a to c
                }.onSuccess { (a, c) -> error = null; sender.custom(a, c, extended) }.onFailure { error = it.message ?: "Código NEC inválido" }
            }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = RemoteDimensions.key)) { Text("Enviar NEC") }
            Text("Valores hexadecimais na notação LSB.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().imePadding().padding(RemoteDimensions.pad), contentAlignment = Alignment.Center) {
            val wide = maxWidth >= RemoteDimensions.tvLandscapeWidth && maxWidth > maxHeight
            Surface(Modifier.widthIn(max = if (wide) RemoteDimensions.wideDialogWidth else RemoteDimensions.dialogWidth).heightIn(max = maxHeight).fillMaxWidth(), shape = RoundedCornerShape(Dimensions.panelRadius)) {
                if (wide) Row(Modifier.verticalScroll(rememberScrollState()).padding(Space.lg), horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
                    Column(Modifier.width(RemoteDimensions.dialogRail)) {
                        Text("Configuração IR", style = MaterialTheme.typography.titleMedium)
                        sections()
                        TextButton(onClick = onDismiss) { Text("Fechar") }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(RemoteDimensions.tightGap)) { content() }
                } else Column(Modifier.verticalScroll(rememberScrollState()).padding(Space.lg), verticalArrangement = Arrangement.spacedBy(RemoteDimensions.gap)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Configuração IR", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = onDismiss) { Text("Fechar") }
                    }
                    Row { sections() }
                    content()
                }
            }
        }
    }
}
