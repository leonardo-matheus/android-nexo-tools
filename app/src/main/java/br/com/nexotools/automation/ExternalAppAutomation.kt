package br.com.nexotools.automation

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.view.accessibility.AccessibilityManager
import br.com.nexotools.data.PreferencesStore
import br.com.nexotools.domain.MacroGestureMath
import br.com.nexotools.domain.MacroStep
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class AppOption(val packageName: String, val label: String)

@Singleton
class ExternalAppAutomation @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: PreferencesStore,
) {
    companion object {
        @Volatile private var connectedService: NexoAccessibilityService? = null
        internal fun attach(service: NexoAccessibilityService) { connectedService = service }
        internal fun detach(service: NexoAccessibilityService) { if (connectedService === service) connectedService = null }
    }
    private val sequence = AtomicLong()
    private val currentSession = AtomicLong()

    /** Call only from an explicit user-started foreground execution / notification tap. */
    fun beginUserSession() { currentSession.set(sequence.incrementAndGet()) }
    /** Call from Stop notification and execution cancellation/finally. Invalidates queued steps. */
    fun endUserSession() { currentSession.set(0) }

    fun serviceEnabled(): Boolean {
        val accessibility = context.getSystemService(AccessibilityManager::class.java) ?: return false
        val expected = ComponentName(context, NexoAccessibilityService::class.java)
        return connectedService != null && accessibility.isEnabled &&
            accessibility.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { ComponentName.unflattenFromString(it.id) == expected }
    }

    /** Check before any mixed macro step, including IR, to prevent partial runs while blocked. */
    suspend fun requireReady() = withContext(Dispatchers.IO) {
        check(preferences.state.first().externalAutomationConsent) { "Autorize o uso de acessibilidade nas configurações do NEXO" }
        check(serviceEnabled()) { "Ative o serviço de acessibilidade do NEXO no Android" }
        check(connectedService != null) { "Serviço de acessibilidade não conectado" }
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        check(keyguard != null && !keyguard.isDeviceLocked && power?.isInteractive == true) { "Desbloqueie a tela para executar a macro" }
    }
    suspend fun openNexoTool(route:String)=withContext(Dispatchers.IO){
        requireReady()
        require(route in br.com.nexotools.domain.MacroRules.toolRoutes)
        val token=currentSession.get();check(token>0)
        val service=connectedService?:error("Serviço indisponível")
        service.openNexoTool(route){currentSession.get()==token && connectedService===service}
    }

    suspend fun run(step: MacroStep) = withContext(Dispatchers.IO) {
        requireReady()
        val token = currentSession.get()
        check(token > 0) { "Inicie a macro externa pela tela ou toque na notificação" }
        require(MacroGestureMath.validPackageName(step.argument)) { "Selecione um aplicativo de destino" }
        val service = connectedService ?: throw IllegalStateException("Serviço de acessibilidade não conectado")
        val authorized = { currentSession.get() == token && connectedService === service }
        // A closed action set executes only the user's stored deterministic steps.
        when (step.action.name) {
            "OPEN_APP" -> service.launchApp(step.argument, authorized)
            "TAP" -> service.tap(step.argument, MacroGestureMath.parseTap(step.detail), authorized)
            "SWIPE" -> service.swipe(step.argument, MacroGestureMath.parseSwipe(step.detail), authorized)
            "BACK" -> service.back(step.argument, authorized)
            "HOME" -> service.home(step.argument, authorized)
            else -> throw IllegalArgumentException("Ação externa não suportada")
        }
    }

    @Suppress("DEPRECATION")
    suspend fun listApps(): List<AppOption> = withContext(Dispatchers.IO) {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val manager = context.packageManager
        val activities = if (Build.VERSION.SDK_INT >= 33)
            manager.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
        else manager.queryIntentActivities(launcher, 0)
        activities.mapNotNull { result ->
            val packageName = result.activityInfo?.packageName ?: return@mapNotNull null
            if (packageName == context.packageName || !MacroGestureMath.validPackageName(packageName)) null
            else AppOption(packageName, result.loadLabel(manager).toString())
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }
}
