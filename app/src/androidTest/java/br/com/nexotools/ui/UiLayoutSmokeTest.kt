package br.com.nexotools.ui

import android.content.Intent
import android.graphics.Rect
import android.hardware.ConsumerIrManager
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
import br.com.nexotools.NexoApplication
import br.com.nexotools.data.PreferencesStore
import br.com.nexotools.domain.IrProfiles
import java.io.File
import java.util.regex.Pattern
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run once per externally configured emulator size/rotation/font scale and theme argument. */
@RunWith(AndroidJUnit4::class)
class UiLayoutSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val store = PreferencesStore(context)
    private val timeout = 15_000L
    private data class Destination(val route: String, val card: String, val title: String, val category: String)
    private val destinations = listOf(
        Destination("wifi", "Wi-Fi", "Analisador Wi-Fi", "Rede"),
        Destination("mobile", "Rede móvel", "Rede móvel", "Rede"),
        Destination("ping", "Latência TCP", "Latência TCP", "Rede"),
        Destination("speed", "Velocidade", "Teste de velocidade", "Rede"),
        Destination("sonar", "Sonar Wi-Fi", "Sonar Wi-Fi", "Rede"),
        Destination("level", "Nível de bolha", "Nível de bolha", "Ferramentas"),
        Destination("angle", "Inclinômetro", "Inclinômetro", "Ferramentas"),
        Destination("audio", "Ruído", "Medidor de ruído", "Ferramentas"),
        Destination("calibration", "Calibração", "Calibração", "Ferramentas"),
        Destination("remotes", "Controles IR", "Controles IR", "Utilidades"),
        Destination("volumeBoost", "Volume +", "Volume +", "Utilidades"),
        Destination("nightCamera", "Câmera noturna", "Câmera noturna", "Utilidades"),
        Destination("macros", "Tarefas e macros", "Tarefas e macros", "Utilidades"),
    )
    private val routes = destinations.map { it.route }.toSet()
    private lateinit var output: File

    @Test fun realAppLayoutsNavigationFavoritesAndConsent() = runBlocking {
        assumeTrue("Somente emulador", device.executeShellCommand("getprop ro.kernel.qemu").trim() == "1")
        assumeFalse("Fixture sem emissor IR", context.getSystemService(ConsumerIrManager::class.java)?.hasIrEmitter() == true)
        assertTrue(context.applicationContext is NexoApplication)
        val args = InstrumentationRegistry.getArguments()
        val theme = (args.getString("theme") ?: "DARK").uppercase()
        require(theme in setOf("DARK", "LIGHT")) { "theme deve ser DARK ou LIGHT" }
        val scenario = (args.getString("scenario") ?: "default").replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
        output = File(requireNotNull(context.getExternalFilesDir(null)), "ui-validation/$scenario-${theme.lowercase()}")
        assertTrue(output.isDirectory || output.mkdirs())
        val saved = withTimeout(timeout) { store.state.first() }
        val configurator = Configurator.getInstance()
        val previousIdleTimeout = configurator.waitForIdleTimeout
        configurator.waitForIdleTimeout = 0
        try {
            store.setTheme(theme)
            store.setExternalAutomationConsent(false)
            replaceFavorites(emptySet())
            // The emulator is a fixture: completing its first onboarding is intentionally durable.
            if (!saved.onboardingDone) store.completeOnboarding()
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            waitFor(By.text("Favoritos"))
            if (args.getString("remoteOnly").toBoolean()) {
                category("Utilidades"); find(By.text("Controles IR")).click(); waitFor(By.desc("Voltar"))
                remoteChecks()
                assertEquals(saved.calibration, store.state.first().calibration)
                return@runBlocking
            }
            capture("home-empty")
            category("Rede")
            click(By.desc("Fixar Wi-Fi"))
            withTimeout(timeout) { store.state.first { "wifi" in it.favorites } }
            assertTrue("Favorito persistido", "wifi" in PreferencesStore(context).state.first().favorites)
            category("Início"); waitFor(By.text("Wi-Fi")); capture("home-favorite")
            click(By.desc("Desafixar Wi-Fi"))
            withTimeout(timeout) { store.state.first { "wifi" !in it.favorites } }
            assertFalse("Remoção persistida", "wifi" in PreferencesStore(context).state.first().favorites)
            replaceFavorites(destinations.take(7).map { it.route }.toSet())
            category("Início"); assertGrid(destinations.take(7)); capture("home-grid-last")
            category("Início"); capture("home-grid")
            listOf("Rede", "Ferramentas", "Utilidades").forEach { label ->
                category(label); assertGrid(destinations.filter { it.category == label }); category(label); capture("catalog-$label")
            }
            destinations.forEach { destination ->
                category(destination.category); find(By.text(destination.card)).click()
                waitFor(By.desc("Voltar")); waitFor(By.text(destination.title)); capture(destination.route)
                when (destination.route) {
                    "wifi" -> {
                        find(By.text("Detalhes da conexão")).click()
                        waitFor(By.text("Detalhes do Wi-Fi")); capture("wifiDetails"); click(By.desc("Voltar"))
                    }
                    "angle" -> { find(By.text("X · Pitch")); find(By.text("Y · Roll")); capture("angle-axes") }
                    "remotes" -> remoteChecks()
                    "macros" -> {
                        find(By.textContains("Configurar acessibilidade")).click()
                        waitFor(By.text("Autorizar macros interativas?")); capture("macros-consent")
                        click(By.text("Cancelar"))
                        assertTrue(device.wait(Until.gone(By.text("Autorizar macros interativas?")), timeout))
                        assertFalse("Cancelar não autoriza", store.state.first().externalAutomationConsent)
                    }
                }
            }
            category("Início"); click(By.desc("Histórico")); waitFor(By.text("Histórico")); capture("history")
            category("Início"); click(By.desc("Ajustes")); waitFor(By.text("Ajustes")); capture("settings")
            find(By.text("Permissões e privacidade")).click()
            waitFor(By.text("Permissões e privacidade")); capture("privacy")
            assertEquals("A calibração deve permanecer intacta", saved.calibration, store.state.first().calibration)
        } catch (failure: Throwable) {
            runCatching { capture("failure") }; throw failure
        } finally {
            try {
                store.setTheme(saved.theme)
                store.setDecimals(saved.decimals)
                store.setExternalAutomationConsent(saved.externalAutomationConsent)
                replaceFavorites(saved.favorites)
            } finally { configurator.waitForIdleTimeout = previousIdleTimeout }
        }
    }

    private suspend fun replaceFavorites(desired: Set<String>) {
        (store.state.first().favorites + desired).filter { it in routes }.forEach { store.setFavorite(it, it in desired) }
        withTimeout(timeout) { store.state.first { it.favorites.intersect(routes) == desired.intersect(routes) } }
    }
    private fun category(label: String) {
        repeat(3) { device.findObject(By.desc("Voltar"))?.let { it.click(); device.wait(Until.gone(By.desc("Voltar")), 500) } }
        // Visiting another catalog resets the route-scoped scroll position before each search.
        listOf(if (label == "Início") "Rede" else "Início", label).forEach { destination ->
            (device.findObjects(By.text(destination)).lastOrNull() ?: error("Categoria ausente: $destination")).click()
            waitFor(By.text(if (destination == "Início") "Favoritos" else destination).clickable(false))
        }
    }
    private fun waitFor(selector: BySelector): UiObject2 =
        device.wait(Until.findObject(selector), timeout) ?: error("Elemento ausente: $selector")
    private fun click(selector: BySelector) { waitFor(selector).click() }
    private fun find(selector: BySelector): UiObject2 {
        device.findObject(selector)?.let { return it }
        val container = device.findObject(By.scrollable(true)) ?: return waitFor(selector)
        for (attempt in 0 until 16) if (!container.scroll(Direction.UP, 0.9f)) break
        repeat(16) {
            device.findObject(selector)?.let { return it }
            val scroll = device.findObject(By.scrollable(true)) ?: error("Elemento ausente: $selector")
            if (!scroll.scroll(Direction.DOWN, 0.45f)) return waitFor(selector)
        }
        error("Elemento não alcançado: $selector")
    }
    private fun card(title: String): UiObject2 {
        var node = find(By.text(title))
        while (!node.isClickable) node = node.parent ?: error("Cartão não clicável: $title")
        return node
    }
    private fun assertGrid(items: List<Destination>) {
        fun bounds(title: String): Rect {
            val tile = card(title)
            val scroll = requireNotNull(device.findObject(By.scrollable(true)))
            for (attempt in 0 until 4) {
                val delta = tile.visibleBounds.centerY() - scroll.visibleBounds.centerY()
                val amount = (kotlin.math.abs(delta).toFloat() / scroll.visibleBounds.height()).coerceIn(0.03f, 0.4f)
                if (kotlin.math.abs(delta) <= 2 || !scroll.scroll(if (delta > 0) Direction.DOWN else Direction.UP, amount)) break
            }
            return tile.visibleBounds
        }
        val reference = bounds(items.first().card)
        items.forEach { item ->
            val actual = bounds(item.card)
            assertEquals("Largura uniforme, inclusive último: ${item.card}", reference.width().toDouble(), actual.width().toDouble(), 2.0)
            assertEquals("Altura uniforme: ${item.card}", reference.height().toDouble(), actual.height().toDouble(), 2.0)
        }
    }
    private fun remoteChecks() {
        assertFalse("IR não deve rolar", device.hasObject(By.scrollable(true)))
        checkRemotePage(IrProfiles.led24.commands.map { it.label }.toSet(), "remoteLed")
        if (device.hasObject(By.desc("Selecionar TV LG"))) click(By.desc("Selecionar TV LG"))
        else { click(By.desc("Selecionar e configurar controle IR")); click(By.text("TV LG")) }
        waitFor(By.desc(Pattern.compile("^TV: .+")))
        checkRemotePage(IrProfiles.lgTv.commands.filterNot { it.id.startsWith("DIGIT_") }.map { "TV: ${it.label}" }.toSet(), "remoteLg")
        findOnRemotePages(By.desc("Teclado numérico da TV")).click(); waitFor(By.text("Teclado da TV"))
        ((1..9).toList() + 0).forEach { digit -> val key = find(By.desc("TV: $digit")); assertTarget(key); assertFalse(key.isEnabled) }
        capture("remoteLg-keypad"); find(By.text("Fechar")).click()
        if (device.hasObject(By.desc("Configurar controle IR"))) click(By.desc("Configurar controle IR"))
        else { click(By.desc("Selecionar e configurar controle IR")); click(By.text("Configurar controle IR")) }
        waitFor(By.text("Configuração IR")); capture("remote-settings")
        find(By.text("NEC manual")).click(); find(By.textContains("Endereço")); find(By.textContains("Comando")); capture("remote-settings-nec")
        find(By.text("Fechar")).click()
        assertTrue(device.wait(Until.gone(By.text("Configuração IR")), timeout))
        assertFalse(device.hasObject(By.textContains("Transmitindo")))
        assertFalse(device.hasObject(By.scrollable(true)))
    }
    private fun checkRemotePage(expected: Set<String>, name: String) {
        val seen = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        waitFor(By.desc(Pattern.compile(expected.joinToString("|", "(?:", ")") { Pattern.quote(it) })))
        for (page in 1..24) {
            if (!visited.add(pageMarker() ?: "single")) break
            expected.forEach { description -> device.findObject(By.desc(description))?.let { assertTarget(it); seen += description } }
            assertFalse(device.hasObject(By.textContains("Transmitindo"))); capture("$name-page$page")
            if (!nextRemotePage()) break
        }
        assertEquals("Todos os comandos IR alcançáveis sem rolagem", expected, seen)
    }
    private fun pageMarker(): String? = device.findObject(By.text(Pattern.compile("^[0-9]+/[0-9]+$")))?.text
    private fun nextRemotePage(): Boolean {
        val pager = device.findObject(By.desc("Próxima página de comandos")) ?: return false
        val marker = requireNotNull(pageMarker()) { "Pager IR sem indicador 1/N" }
        if (!pager.isEnabled || marker.substringAfter('/').toInt() <= 1) return false
        pager.click()
        assertTrue("O pager IR deve avançar", device.wait(Until.gone(By.text(marker)), timeout))
        waitFor(By.text(Pattern.compile("^[0-9]+/[0-9]+$")))
        return true
    }
    private fun findOnRemotePages(selector: BySelector): UiObject2 {
        val visited = mutableSetOf<String>()
        for (page in 1..24) {
            device.findObject(selector)?.let { return it }
            if (!visited.add(pageMarker() ?: "single") || !nextRemotePage()) break
        }
        error("Comando não alcançável no pager IR: $selector")
    }
    private fun assertTarget(node: UiObject2) {
        val bounds = node.visibleBounds
        val minimum = 48 * context.resources.displayMetrics.density - 2
        assertTrue("Comando IR sem corte: ${node.contentDescription}", bounds.width() >= minimum && bounds.height() >= minimum)
    }
    private fun capture(name: String) {
        assertEquals(context.packageName, device.currentPackageName)
        val safeName = name.replace(Regex("[^A-Za-z0-9_-]"), "_")
        assertTrue("Falha na captura $safeName", device.takeScreenshot(File(output, "$safeName.png")))
    }
}
