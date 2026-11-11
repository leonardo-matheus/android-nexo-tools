package br.com.nexotools.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import br.com.nexotools.MainViewModel
import br.com.nexotools.R
import br.com.nexotools.domain.*
import br.com.nexotools.infra.AndroidAppInventory
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

private val batchSaver = listSaver<UninstallBatch, Any>(
    save = { listOf(ArrayList(it.remaining), it.inFlight.orEmpty(), it.resultReturned, it.removed, it.total) },
    restore = {
        @Suppress("UNCHECKED_CAST")
        UninstallBatch(it[0] as List<String>, (it[1] as String).ifEmpty { null }, it[2] as Boolean, it[3] as Int, it[4] as Int)
    },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable fun UninstallerScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val inventory = remember(context.applicationContext) { AndroidAppInventory(context.applicationContext) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(AppSort.SIZE_DESC) }
    var sizeFilter by rememberSaveable { mutableStateOf(AppSizeFilter.ALL) }
    var installFilter by rememberSaveable { mutableStateOf(AppInstallFilter.ALL) }
    var usageFilter by rememberSaveable { mutableStateOf(AppUsageFilter.ALL) }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var batch by rememberSaveable(stateSaver = batchSaver) { mutableStateOf(UninstallBatch()) }
    var revision by remember { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf(emptyList<InstalledUserApp>()) }
    var loading by remember { mutableStateOf(true) }
    var metricsLoading by remember { mutableStateOf(false) }
    var access by remember { mutableStateOf(inventory.hasUsageAccess()) }
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var error by remember { mutableStateOf<String?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var filters by rememberSaveable { mutableStateOf(false) }
    var permissionInfo by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    var inspected by rememberSaveable { mutableStateOf<String?>(null) }
    val latestBatch by rememberUpdatedState(batch)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Installer result codes vary; verify the actual package state before advancing.
        batch = batch.returned()
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumed = true
                if (!latestBatch.running) revision++
            } else if (event == Lifecycle.Event.ON_PAUSE) resumed = false
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    BackHandler(batch.running) { batch = batch.stop(); revision++ }

    LaunchedEffect(inventory, revision) {
        loading = true
        metricsLoading = false
        error = null
        access = inventory.hasUsageAccess()
        if (!access) { sizeFilter = AppSizeFilter.ALL; usageFilter = AppUsageFilter.ALL }
        try {
            val base = inventory.listUserApps()
            apps = base
            selected = selected.filter { name -> base.any { it.packageName == name } }
            loading = false
            if (access) {
                metricsLoading = true
                val detailed = inventory.withUsageDetails(base)
                access = inventory.hasUsageAccess()
                val stillListed = apps.mapTo(mutableSetOf()) { it.packageName }
                apps = (if (access) detailed else base).filter { it.packageName in stillListed }
                if (!access) { sizeFilter = AppSizeFilter.ALL; usageFilter = AppUsageFilter.ALL }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Não foi possível atualizar os apps." }
        finally { loading = false; metricsLoading = false }
    }
    LaunchedEffect(batch.remaining.firstOrNull(), batch.inFlight, resumed) {
        val next = batch.remaining.firstOrNull() ?: return@LaunchedEffect
        if (!resumed || batch.inFlight != null) return@LaunchedEffect
        try {
            val intent = inventory.confirmationIntent(next)
            if (!resumed || batch.remaining.firstOrNull() != next || batch.inFlight != null) return@LaunchedEffect
            launcher.launch(intent)
            batch = batch.launched(next)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            batch = batch.stop()
            revision++
            vm.setMessage(when (failure) {
                is PackageManager.NameNotFoundException -> "App não encontrado. Lote interrompido."
                is ActivityNotFoundException -> "Confirmação indisponível neste Android."
                is SecurityException -> "O Android bloqueou a desinstalação. Lote interrompido."
                else -> "Não foi possível desinstalar. Lote interrompido."
            })
        }
    }
    LaunchedEffect(batch.inFlight, batch.resultReturned, resumed) {
        val pending = batch.inFlight ?: return@LaunchedEffect
        if (!resumed || !batch.resultReturned) return@LaunchedEffect
        try {
            val present = inventory.isInstalled(pending)
            if (batch.inFlight != pending || !batch.resultReturned) return@LaunchedEffect
            batch = batch.resolved(pending, present)
            if (!present) {
                selected = selected - pending
                apps = apps.filterNot { it.packageName == pending }
            }
            if (!batch.running) {
                revision++
                vm.setMessage((if (present) "Lote interrompido · " else "") +
                    if (batch.removed == 1) "1 app removido" else batch.removed.toString() + " apps removidos")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            batch = batch.resolved(pending, stillInstalled = true); revision++
            vm.setMessage("Não foi possível confirmar a remoção. Lote interrompido.")
        }
    }
    val filtered = remember(apps, query, sort, sizeFilter, installFilter, usageFilter) {
        InstalledAppRules.filterAndSort(apps, query, sort, sizeFilter, installFilter, usageFilter)
    }
    val visible = filtered.map { it.packageName }
    val selectedVisible = visible.count { it in selected }
    val hiddenSelected = selected.size - selectedVisible
    val activeFilters = listOf(sizeFilter != AppSizeFilter.ALL, installFilter != AppInstallFilter.ALL, usageFilter != AppUsageFilter.ALL).count { it }
    val editable = !batch.running && !loading

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = Space.sm), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        item {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, label = { Text("Buscar app") },
            singleLine = true, enabled = !batch.running, modifier = Modifier.fillMaxWidth(),
            trailingIcon = { if (query.isNotEmpty()) TextButton({ query = "" }, enabled = !batch.running) { Text("Limpar") } },
        )
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            Box {
                TextButton({ sortMenu = true }, enabled = editable) {
                    Text(sort.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Glyph(R.drawable.ic_arrow_down)
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    AppSort.entries.forEach { choice ->
                        DropdownMenuItem(text = { Text(choice.label) },
                            enabled = access || choice in setOf(AppSort.NAME, AppSort.INSTALLED_ASC, AppSort.INSTALLED_DESC),
                            onClick = { sort = choice; sortMenu = false })
                    }
                }
            }
            TextButton({ filters = true }, enabled = editable) {
                Glyph(R.drawable.ic_sliders_horizontal)
                Text(if (activeFilters == 0) "Filtros" else "Filtros (" + activeFilters + ")")
            }
            IconButton({ revision++ }, enabled = editable && !metricsLoading) { Glyph(R.drawable.ic_rotate_ccw, "Atualizar apps") }
        }
        if (!access) {
            OutlinedButton({ permissionInfo = true }, enabled = !batch.running, modifier = Modifier.fillMaxWidth()) { Text("Liberar tamanho e último uso") }
        }
        if (loading || metricsLoading) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Carregando apps" })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TriStateCheckbox(
                state = when { selectedVisible == 0 -> ToggleableState.Off; selectedVisible == visible.size -> ToggleableState.On; else -> ToggleableState.Indeterminate },
                onClick = { selected = if (selectedVisible == visible.size) selected - visible.toSet() else (selected + visible).distinct() },
                enabled = editable && visible.isNotEmpty(),
                modifier = Modifier.semantics { contentDescription = "Selecionar apps visíveis" },
            )
            Text((if (filtered.size == 1) "1 app" else filtered.size.toString() + " apps") +
                (if (selected.isEmpty()) "" else if (selected.size == 1) " · 1 selecionado" else " · " + selected.size + " selecionados") +
                (if (hiddenSelected > 0) " · " + hiddenSelected + " fora do filtro" else ""),
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            if (selected.isNotEmpty()) TextButton({ selected = emptyList() }, enabled = editable) { Text("Limpar") }
        }
        }
        }
            if (!loading && !metricsLoading && error == null && filtered.isEmpty()) item { Hint("Nenhum app encontrado.") }
            items(filtered, key = { it.packageName }) { app ->
                InstalledAppRow(app, inventory, revision, sort, app.packageName in selected, editable,
                    onSelect = { selected = if (app.packageName in selected) selected - app.packageName else selected + app.packageName },
                    onDetails = { inspected = app.packageName })
            }
        }
        if (batch.running) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (batch.remaining.isEmpty()) "Aguardando o Android" else batch.removed.toString() + "/" + batch.total + " removidos", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                OutlinedButton({ batch = batch.stop(); revision++ }, enabled = batch.remaining.isNotEmpty()) { Text("Parar lote") }
            }
        } else if (selected.isNotEmpty()) {
            Button({ confirm = true }, enabled = editable, modifier = Modifier.fillMaxWidth()) { Text("Desinstalar (" + selected.size + ")") }
        }
    }
    if (permissionInfo) AlertDialog(
        onDismissRequest = { permissionInfo = false }, title = { Text("Tamanho e último uso") },
        text = { Text("Permita o acesso ao uso para consultar arquivos, dados, cache e o último uso registrado pelo Android. As informações ficam apenas em memória no aparelho.") },
        confirmButton = { TextButton({
            permissionInfo = false
            try { context.startActivity(inventory.usageAccessIntent()) }
            catch (_: ActivityNotFoundException) {
                runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                    .onFailure { vm.setMessage("Acesso ao uso indisponível neste Android.") }
            } catch (_: SecurityException) { vm.setMessage("O Android bloqueou a abertura dos ajustes.") }
        }) { Text("Abrir ajustes") } },
        dismissButton = { TextButton({ permissionInfo = false }) { Text("Agora não") } },
    )
    if (filters) AlertDialog(
        onDismissRequest = { filters = false }, title = { Text("Filtros") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                AppFilterMenu("Tamanho total", sizeFilter.label, access, AppSizeFilter.entries.map { it.label to { sizeFilter = it } })
                AppFilterMenu("Instalação", installFilter.label, true, AppInstallFilter.entries.map { it.label to { installFilter = it } })
                AppFilterMenu("Último uso", usageFilter.label, access, AppUsageFilter.entries.map { it.label to { usageFilter = it } })
                if (!access) Hint("Tamanho e uso exigem acesso ao uso.")
            }
        },
        confirmButton = { TextButton({ filters = false }) { Text("Pronto") } },
        dismissButton = { TextButton({ sizeFilter = AppSizeFilter.ALL; installFilter = AppInstallFilter.ALL; usageFilter = AppUsageFilter.ALL }) { Text("Limpar filtros") } },
    )
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false }, title = { Text("Desinstalar " + selected.size + if (selected.size == 1) " app?" else " apps?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text("Os dados dos apps serão removidos. Confirme cada remoção no Android; cancelar uma delas interrompe o lote.")
                apps.filter { it.packageName in selected }.forEach { Text(it.label, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton({ confirm = false; batch = UninstallBatch.start(selected) },
                enabled = editable && selected.isNotEmpty() && selected.all { name -> apps.any { it.packageName == name } },
            ) { Text("Desinstalar") }
        },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancelar") } },
    )
    apps.firstOrNull { it.packageName == inspected }?.let { app ->
        val bytes: (Long?) -> String = { it?.let { count -> Formatter.formatFileSize(context, count) } ?: "Indisponível" }
        AlertDialog(
            onDismissRequest = { inspected = null }, title = { Text(app.label) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                Metric("Arquivos", bytes(app.storage?.appBytes))
                Metric("Dados", bytes(app.storage?.dataBytes))
                Metric("Cache", bytes(app.storage?.cacheBytes))
                Metric("Total", bytes(app.storage?.totalBytes))
                Metric("Instalação", app.installedAt.dateLabel())
                Metric("Último uso", app.lastUsedAt.dateLabel())
                Hint("O histórico de uso depende do Android. Sem registro não significa nunca usado.")
            } },
            confirmButton = { TextButton({ inspected = null }) { Text("Fechar") } },
        )
    }
}

@Composable private fun InstalledAppRow(
    app: InstalledUserApp, inventory: AndroidAppInventory, revision: Int, sort: AppSort,
    selected: Boolean, enabled: Boolean, onSelect: () -> Unit, onDetails: () -> Unit,
) {
    val context = LocalContext.current
    val iconSize = with(LocalDensity.current) { 32.dp.toPx().roundToInt().coerceIn(32, 192) }
    val bitmap by produceState<Bitmap?>(null, app.packageName, iconSize, revision) { value = inventory.icon(app.packageName, iconSize) }
    val detail = when (sort) {
        AppSort.INSTALLED_ASC, AppSort.INSTALLED_DESC -> "Instalado " + app.installedAt.dateLabel()
        AppSort.USED_ASC, AppSort.USED_DESC -> if (app.lastUsedAt == null) "Sem registro de uso" else "Uso " + app.lastUsedAt.dateLabel()
        else -> app.storage?.let { Formatter.formatShortFileSize(context, it.totalBytes) } ?: "Tamanho indisponível"
    }
    Surface(shape = MaterialTheme.shapes.medium, color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().toggleable(selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onSelect() })
            .semantics { contentDescription = "Selecionar " + app.label }.padding(horizontal = Space.xs, vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            IconButton(onDetails, modifier = Modifier.semantics { contentDescription = "Detalhes de " + app.label }) {
                val image = bitmap
                if (image == null) Glyph(R.drawable.ic_smartphone)
                else Image(image.asImageBitmap(), contentDescription = null, modifier = Modifier.size(32.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
        }
    }
}

@Composable private fun AppFilterMenu(label: String, value: String, enabled: Boolean, options: List<Pair<String, () -> Unit>>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.labelSmall); Text(value) }
            Glyph(R.drawable.ic_arrow_down)
        }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { (name, select) -> DropdownMenuItem(text = { Text(name) }, onClick = { select(); expanded = false }) }
        }
    }
}

private fun Long?.dateLabel(): String = this?.let { DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it)) } ?: "Sem registro"
private val AppSort.label: String get() = when (this) {
    AppSort.NAME -> "Nome (A–Z)"
    AppSort.SIZE_DESC -> "Maior tamanho"
    AppSort.SIZE_ASC -> "Menor tamanho"
    AppSort.INSTALLED_DESC -> "Instalação recente"
    AppSort.INSTALLED_ASC -> "Instalação antiga"
    AppSort.USED_DESC -> "Uso recente"
    AppSort.USED_ASC -> "Menos usados"
}
private val AppSizeFilter.label: String get() = when (this) { AppSizeFilter.ALL -> "Qualquer tamanho"; AppSizeFilter.MB_100 -> "A partir de 100 MB"; AppSizeFilter.GB_1 -> "A partir de 1 GB" }
private val AppInstallFilter.label: String get() = when (this) { AppInstallFilter.ALL -> "Qualquer data"; AppInstallFilter.DAYS_7 -> "Últimos 7 dias"; AppInstallFilter.DAYS_30 -> "Últimos 30 dias" }
private val AppUsageFilter.label: String get() = when (this) { AppUsageFilter.ALL -> "Qualquer uso"; AppUsageFilter.DAYS_30 -> "Sem uso há 30 dias"; AppUsageFilter.DAYS_90 -> "Sem uso há 90 dias"; AppUsageFilter.NO_RECORD -> "Sem registro disponível" }
