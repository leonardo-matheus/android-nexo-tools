package br.com.nexotools.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun PermissionPanel(permissions:Array<String>,explanation:String,onGranted:(()->Unit)?=null) {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(permissions.all{context.checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED}) }
    var denied by remember { mutableStateOf(false) }
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted=permissions.all{context.checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED}
        denied=!granted
        onGranted?.invoke()
    }
    DisposableEffect(owner,permissions.toList()) {
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME){granted=permissions.all{context.checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED};onGranted?.invoke()}}
        owner.lifecycle.addObserver(observer)
        onDispose{owner.lifecycle.removeObserver(observer)}
    }
    if(!granted) Panel {
        SectionLabel(if(denied)"Permissão não concedida" else "Você controla o acesso")
        Hint(explanation)
        Button(onClick={launcher.launch(permissions)}){Text("Permitir acesso")}
        if(denied) TextButton(onClick={context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))}){Text("Abrir permissões do Android")}
    }
}
