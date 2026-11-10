package br.com.nexotools.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import br.com.nexotools.domain.MacroGestureMath
import br.com.nexotools.domain.NormalizedPoint
import br.com.nexotools.domain.SwipeSpec
import br.com.nexotools.MacroEntryActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** No node tree, window content, text, screen image, clipboard or accessibility input is read. */
class NexoAccessibilityService : AccessibilityService() {
    private data class ForegroundEvent(val packageName: String? = null, val sequence: Long = 0)
    private val foreground = MutableStateFlow(ForegroundEvent())
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() { ExternalAppAutomation.attach(this) }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType !in setOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED)) return
        // Only package metadata is retained in RAM. A missing/unknown package invalidates focus.
        val packageName = event.packageName?.toString()?.takeIf(MacroGestureMath::validPackageName)
        foreground.value = ForegroundEvent(packageName, foreground.value.sequence + 1)
    }
    override fun onInterrupt() { invalidateFocus() }
    override fun onUnbind(intent: Intent?): Boolean { ExternalAppAutomation.detach(this); invalidateFocus(); return super.onUnbind(intent) }
    override fun onDestroy() { ExternalAppAutomation.detach(this); invalidateFocus(); super.onDestroy() }
    private fun invalidateFocus() { foreground.value = ForegroundEvent(sequence = foreground.value.sequence + 1) }

    private fun requireUnlocked() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        val power = getSystemService(PowerManager::class.java)
        check(keyguard != null && !keyguard.isDeviceLocked && power?.isInteractive == true) { "Desbloqueie a tela para executar a macro" }
    }
    private fun guard(targetPackage: String, authorized: () -> Boolean, requireFocus: Boolean = true) {
        check(authorized()) { "A execução externa foi cancelada" }
        require(MacroGestureMath.validPackageName(targetPackage)) { "Aplicativo de destino inválido" }
        requireUnlocked()
        if (requireFocus) check(foreground.value.packageName == targetPackage) { "O aplicativo em foco mudou. A macro foi interrompida." }
    }

    internal suspend fun launchApp(targetPackage: String, authorized: () -> Boolean) = withContext(Dispatchers.Main.immediate) {
        guard(targetPackage, authorized, requireFocus = false)
        if(foreground.value.packageName==targetPackage){guard(targetPackage,authorized);return@withContext}
        val intent = packageManager.getLaunchIntentForPackage(targetPackage)
            ?: throw IllegalStateException("O aplicativo selecionado não está disponível")
        check(intent.component?.packageName == targetPackage) { "Não foi possível confirmar o aplicativo selecionado" }
        val priorEvent = foreground.value.sequence
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        val focused = withTimeoutOrNull(8_000) { foreground.first { it.sequence > priorEvent && it.packageName == targetPackage } }
        check(focused != null) { "O aplicativo selecionado não entrou em foco no prazo" }
        guard(targetPackage, authorized)
    }
    internal suspend fun openNexoTool(route:String,authorized:()->Boolean)=withContext(Dispatchers.Main.immediate){
        check(authorized());requireUnlocked()
        val prior=foreground.value.sequence
        startActivity(Intent(this@NexoAccessibilityService,MacroEntryActivity::class.java).putExtra("nexo_tool_route",route).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        if(foreground.value.packageName!=packageName){
            check(withTimeoutOrNull(8_000){foreground.first{it.sequence>prior && it.packageName==packageName}}!=null){"O NEXO não entrou em foco"}
        }
        guard(packageName,authorized)
    }

    internal suspend fun tap(targetPackage: String, point: NormalizedPoint, authorized: () -> Boolean) = withContext(Dispatchers.Main.immediate) {
        guard(targetPackage, authorized)
        val bounds = displayBounds()
        val pixel = MacroGestureMath.pixels(point, bounds.width(), bounds.height(), bounds.left, bounds.top)
        val path = Path().apply { moveTo(pixel.x, pixel.y) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build()
        dispatch(gesture, 80, targetPackage, authorized)
    }
    internal suspend fun swipe(targetPackage: String, swipe: SwipeSpec, authorized: () -> Boolean) = withContext(Dispatchers.Main.immediate) {
        guard(targetPackage, authorized)
        val bounds = displayBounds()
        val start = MacroGestureMath.pixels(swipe.start, bounds.width(), bounds.height(), bounds.left, bounds.top)
        val end = MacroGestureMath.pixels(swipe.end, bounds.width(), bounds.height(), bounds.left, bounds.top)
        val path = Path().apply { moveTo(start.x, start.y); lineTo(end.x, end.y) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, swipe.durationMillis)).build()
        dispatch(gesture, swipe.durationMillis, targetPackage, authorized)
    }
    internal suspend fun back(targetPackage: String, authorized: () -> Boolean) = withContext(Dispatchers.Main.immediate) {
        guard(targetPackage, authorized)
        check(performGlobalAction(GLOBAL_ACTION_BACK)) { "O Android não aceitou a ação Voltar" }
    }
    internal suspend fun home(targetPackage: String, authorized: () -> Boolean) = withContext(Dispatchers.Main.immediate) {
        guard(targetPackage, authorized)
        check(performGlobalAction(GLOBAL_ACTION_HOME)) { "O Android não aceitou a ação Início" }
    }
    private suspend fun dispatch(gesture: GestureDescription, duration: Long, targetPackage: String, authorized: () -> Boolean) {
        guard(targetPackage, authorized)
        val completed = withTimeoutOrNull(duration + 2_000) { suspendCancellableCoroutine<Boolean> { continuation ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(false) }
            }
            if (!dispatchGesture(gesture, callback, mainHandler) && continuation.isActive) continuation.resume(false)
            // Android has no cancel-only gesture API. Cancellation ignores this callback and
            // stops all following steps; the already dispatched stroke (<=3 s) may finish.
        } } ?: false
        check(completed) { "O gesto foi interrompido ou recusado pelo Android" }
        check(authorized()) { "A execução externa foi cancelada" }
        requireUnlocked()
    }
    @Suppress("DEPRECATION")
    private fun displayBounds(): Rect {
        val window = getSystemService(WindowManager::class.java) ?: throw IllegalStateException("Tela indisponível")
        return if (Build.VERSION.SDK_INT >= 30) window.currentWindowMetrics.bounds else {
            val metrics = DisplayMetrics(); window.defaultDisplay.getRealMetrics(metrics)
            Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
    }
}
