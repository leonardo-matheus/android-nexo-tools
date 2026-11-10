package br.com.nexotools.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import br.com.nexotools.MainActivity
import br.com.nexotools.data.PreferencesStore
import br.com.nexotools.domain.SpeedProfile
import java.io.File
import java.util.regex.Pattern
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Single functional pass after the layout matrix; permissions are granted by the emulator fixture. */
@RunWith(AndroidJUnit4::class)
class UiFunctionalSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val store = PreferencesStore(context)
    private val timeout = 15_000L
    private lateinit var output: File

    @Test fun oneFunctionalPassOnEmulator() = runBlocking {
        assumeTrue("Somente emulador", device.executeShellCommand("getprop ro.kernel.qemu").trim() == "1")
        val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        assumeTrue("O serviço de macros NEXO deve estar desligado", enabledServices.split(':').none {
            ComponentName.unflattenFromString(it)?.packageName == context.packageName
        })
        listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA).forEach {
            assertEquals("Conceder mic/câmera na fixture antes do teste", PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(it))
        }
        val saved = withTimeout(timeout) { store.state.first() }
        assertTrue("Executar a matriz de layouts antes deste teste", saved.onboardingDone)
        val scenario = (InstrumentationRegistry.getArguments().getString("scenario") ?: "functional")
            .replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
        output = File(requireNotNull(context.getExternalFilesDir(null)), "ui-functional/$scenario")
        assertTrue(output.isDirectory || output.mkdirs())
        val configurator = Configurator.getInstance()
        val previousIdleTimeout = configurator.waitForIdleTimeout
        configurator.waitForIdleTimeout = 0
        var primaryFailure: Throwable? = null
        try {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            waitFor(By.text("Favoritos"))

            open("Rede", "Latência TCP", "Latência TCP")
            find(By.text("Testar 10 conexões")).click()
            waitFor(By.text("Interromper"))
            find(By.text(Pattern.compile("Testando · \\d+/10|Resultado TCP")))
            waitFor(By.text("Resultado TCP"), 60_000)
            find(By.text("Conexão 10"))
            assertTrue("As dez conexões devem terminar", control("Salvar resultado").isEnabled)
            capture("ping-complete")

            open("Ferramentas", "Ruído", "Medidor de ruído")
            find(By.text("Iniciar")).click()
            waitFor(By.text("Microfone ativo"))
            waitFor(By.pkg(context.packageName).text(Pattern.compile("[-−]?\\d+(?:[.,]\\d+)?")))
            find(By.text("Pausar")).click()
            waitFor(By.text("Medição pausada"))
            waitFor(By.text("Iniciar")); capture("audio-paused")

            open("Utilidades", "Câmera noturna", "Câmera noturna")
            waitFor(By.desc("Prévia processada da câmera"), 30_000)
            find(By.text("Capturar"))
            await("Prévia real deve habilitar Capturar") { control("Capturar").isEnabled }
            capture("camera-preview") // Never click Capturar or open the document picker.

            open("Rede", "Velocidade", "Teste de velocidade")
            assertEquals("Somente o orçamento Economia", 24_000_000L, SpeedProfile.ECONOMY.downloadLimit + SpeedProfile.ECONOMY.uploadLimit)
            control(SpeedProfile.ECONOMY.title).click()
            await("Economia precisa estar selecionada antes de iniciar") {
                control(SpeedProfile.ECONOMY.title).let { it.isChecked || it.isSelected }
            }
            find(By.text("Iniciar teste nativo")).click() // Exactly one Cloudflare test; no retry.
            val stages = Pattern.compile("Aquecendo a conexão|Medindo latência sem carga|Medindo download|Medindo upload|Resultado nativo|Teste não concluído")
            find(By.text(stages))
            val result = waitFor(By.text(Pattern.compile("Resultado nativo|Teste não concluído")), 90_000)
            if (result.text == "Teste não concluído") {
                capture("speed-network-error")
                fail("Falha de rede no teste Cloudflare Economia; verificar speed-network-error.png. O teste não foi repetido.")
            }
            find(By.text("Download médio")); find(By.text("Upload médio"))
            find(By.text("Salvar resultado real"))
            await("Resultado completo precisa conter download e upload") { control("Salvar resultado real").isEnabled }
            capture("speed-complete") // Numeric zero is valid; no speed threshold or result saving.
        } catch (failure: Throwable) {
            primaryFailure = failure
            runCatching { capture("failure") }; throw failure
        } finally {
            try {
                device.pressHome() // ON_STOP releases microphone/camera and cancels unfinished work.
                assertEquals("Preferências e calibrações devem permanecer intactas", saved, withTimeout(timeout) { store.state.first() })
            } catch (cleanup: Throwable) {
                if (primaryFailure == null) throw cleanup else primaryFailure.addSuppressed(cleanup)
            } finally { configurator.waitForIdleTimeout = previousIdleTimeout }
        }
    }

    private fun open(category: String, card: String, title: String) {
        repeat(3) { device.findObject(By.desc("Voltar"))?.let { it.click(); device.wait(Until.gone(By.desc("Voltar")), 500) } }
        listOf("Início", category).forEach { destination ->
            (device.findObjects(By.text(destination)).lastOrNull() ?: error("Categoria ausente: $destination")).click()
            waitFor(By.text(if (destination == "Início") "Favoritos" else destination).clickable(false))
        }
        find(By.text(card)).click(); waitFor(By.desc("Voltar")); waitFor(By.text(title))
    }
    private fun waitFor(selector: BySelector, duration: Long = timeout): UiObject2 =
        device.wait(Until.findObject(selector), duration) ?: error("Elemento ausente: $selector")
    private fun find(selector: BySelector): UiObject2 {
        repeat(16) {
            device.findObject(selector)?.let { return it }
            val scroll = device.findObject(By.scrollable(true)) ?: error("Elemento ausente: $selector")
            if (!scroll.scroll(Direction.DOWN, 0.45f)) return waitFor(selector)
        }
        error("Elemento não alcançado: $selector")
    }
    private fun control(label: String): UiObject2 {
        var node = find(By.text(label))
        while (!node.isClickable && !node.isCheckable && node.className != "android.widget.Button")
            node = node.parent ?: error("Controle sem semântica: $label")
        return node
    }
    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (runCatching(predicate).getOrDefault(false)) return
            SystemClock.sleep(200)
        }
        fail(message)
    }
    private fun capture(name: String) {
        assertEquals(context.packageName, device.currentPackageName)
        assertTrue("Falha na captura $name", device.takeScreenshot(File(output, "$name.png")))
    }
}
