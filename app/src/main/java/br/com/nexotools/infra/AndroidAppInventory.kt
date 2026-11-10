package br.com.nexotools.infra

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import br.com.nexotools.domain.InstalledAppRules
import br.com.nexotools.domain.InstalledUserApp
import java.text.Collator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** In-memory inventory of the current Android profile; manifest declares QUERY_ALL_PACKAGES. */
class AndroidAppInventory(context: Context) {
    private val ownPackage = context.applicationContext.packageName
    private val manager = context.applicationContext.packageManager

    private fun candidate(info: ApplicationInfo): Boolean = InstalledAppRules.isCandidate(
        info.packageName, ownPackage,
        info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
        info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0,
    )

    @Suppress("DEPRECATION")
    suspend fun listUserApps(): List<InstalledUserApp> = withContext(Dispatchers.IO) {
        val applications = if (Build.VERSION.SDK_INT >= 33)
            manager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        else manager.getInstalledApplications(0)
        val collator = Collator.getInstance()
        applications.mapNotNull { info ->
            coroutineContext.ensureActive()
            if (!candidate(info)) return@mapNotNull null
            val label = try {
                manager.getApplicationLabel(info).toString().trim().ifEmpty { info.packageName }
            } catch (_: Resources.NotFoundException) { info.packageName }
            InstalledUserApp(info.packageName, label)
        }.sortedWith { left, right ->
            collator.compare(left.label, right.label).takeIf { it != 0 }
                ?: left.packageName.compareTo(right.packageName)
        }
    }

    suspend fun icon(packageName: String, size: Int): Bitmap? = withContext(Dispatchers.IO) {
        require(size in 1..256)
        coroutineContext.ensureActive()
        try { manager.getApplicationIcon(packageName).toBitmap(size, size) }
        catch (_: PackageManager.NameNotFoundException) { null }
        catch (_: Resources.NotFoundException) { null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RuntimeException) { null }
    }

    /** Creates a confirmation intent only; this method never starts an activity or deletes a package. */
    @Suppress("DEPRECATION")
    suspend fun confirmationIntent(packageName: String): Intent = withContext(Dispatchers.IO) {
        val info = if (Build.VERSION.SDK_INT >= 33)
            manager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        else manager.getApplicationInfo(packageName, 0)
        check(candidate(info)) { "Este app não está disponível para remoção." }
        // Still provided on API 26–36; EXTRA_RETURN_RESULT belongs to this action.
        Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.fromParts("package", info.packageName, null))
            .putExtra(Intent.EXTRA_RETURN_RESULT, true)
    }
}

