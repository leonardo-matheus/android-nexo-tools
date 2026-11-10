package br.com.nexotools.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import br.com.nexotools.MainViewModel
import br.com.nexotools.R
import br.com.nexotools.domain.InstalledAppRules
import br.com.nexotools.domain.InstalledUserApp
import br.com.nexotools.infra.AndroidAppInventory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Needs a bounded route container, like the IR screen; do not wrap it in verticalScroll. */
@Composable fun UninstallerScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val inventory = remember(context.applicationContext) { AndroidAppInventory(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf(emptyList<InstalledUserApp>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingPackage by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pendingPackage = null
        revision++
        vm.setMessage(when (result.resultCode) {
            Activity.RESULT_OK -> "Desinstalação concluída pelo Android."
            Activity.RESULT_CANCELED -> "Desinstalação cancelada ou não concluída."
            else -> "O Android não concluiu a desinstalação."
        })
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                pendingPackage = null
                revision++
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(inventory, revision) {
        loading = true
        error = null
        try { apps = inventory.listUserApps() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Não foi possível listar os apps." }
        loading = false
    }
    val filtered = remember(apps, query) { apps.filter { InstalledAppRules.matches(it, query) } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, label = { Text("Buscar app") },
            placeholder = { Text("Nome ou pacote") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            trailingIcon = { if (query.isNotEmpty()) TextButton({ query = "" }) { Text("Limpar") } },
        )
        Hint("Apps do usuário neste perfil. O Android confirma e pode impedir a remoção.")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (filtered.size == 1) "1 app" else "${filtered.size} apps", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { revision++ }, enabled = !loading && pendingPackage == null) { Text("Atualizar") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            if (!loading && error == null && filtered.isEmpty()) item {
                Hint(if (query.isBlank()) "Nenhum app disponível para remoção neste perfil." else "Nenhum app encontrado.")
            }
            items(filtered, key = { it.packageName }) { app ->
                InstalledAppRow(app, inventory, revision, pendingPackage == null && !loading) {
                    pendingPackage = app.packageName
                    scope.launch {
                        var launched = false
                        try {
                            val intent = inventory.confirmationIntent(app.packageName)
                            launcher.launch(intent)
                            launched = true
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: PackageManager.NameNotFoundException) {
                            vm.setMessage("Este app não está mais instalado."); revision++
                        } catch (_: ActivityNotFoundException) {
                            vm.setMessage("Este Android não disponibilizou a confirmação de desinstalação.")
                        } catch (_: SecurityException) {
                            vm.setMessage("O Android bloqueou o pedido de desinstalação.")
                        } catch (_: Exception) {
                            vm.setMessage("Não foi possível abrir a confirmação de desinstalação."); revision++
                        } finally {
                            if (!launched) pendingPackage = null
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun InstalledAppRow(
    app: InstalledUserApp, inventory: AndroidAppInventory, revision: Int, enabled: Boolean, onRemove: () -> Unit,
) {
    val iconSize = with(LocalDensity.current) { 40.dp.toPx().roundToInt().coerceIn(40, 192) }
    val bitmap by produceState<Bitmap?>(null, app.packageName, iconSize, revision) {
        value = inventory.icon(app.packageName, iconSize)
    }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(Space.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                val image = bitmap
                if (image == null) Glyph(R.drawable.ic_smartphone)
                else Image(image.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(app.packageName, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(
                onClick = onRemove, enabled = enabled,
                modifier = Modifier.heightIn(min = Space.touch).semantics { contentDescription = "Desinstalar ${app.label}" },
            ) { Text("Remover", maxLines = 1) }
        }
    }
}

