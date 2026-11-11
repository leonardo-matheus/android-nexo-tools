package br.com.nexotools.infra

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.graphics.drawable.toBitmap
import br.com.nexotools.domain.InstalledAppRules
import br.com.nexotools.domain.InstalledUserApp
import br.com.nexotools.domain.AppStorage
import java.text.Collator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** In-memory inventory of the current Android profile; manifest declares QUERY_ALL_PACKAGES. */
class AndroidAppInventory(context: Context) {
    private val application = context.applicationContext
    private val ownPackage = application.packageName
    private val manager = application.packageManager

    @Suppress("DEPRECATION")
    fun hasUsageAccess(): Boolean {
        val operations = application.getSystemService(AppOpsManager::class.java) ?: return false
        return try {
            val mode = if (Build.VERSION.SDK_INT >= 29)
                operations.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ownPackage)
            else operations.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ownPackage)
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: SecurityException) { false }
    }

    fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$ownPackage"))

    private fun candidate(info: ApplicationInfo): Boolean = InstalledAppRules.isCandidate(
        info.packageName, ownPackage,
        info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
        info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0,
    )

    @Suppress("DEPRECATION")
    suspend fun listUserApps(): List<InstalledUserApp> = withContext(Dispatchers.IO) {
        val packages = if (Build.VERSION.SDK_INT >= 33)
            manager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        else manager.getInstalledPackages(0)
        val collator = Collator.getInstance()
        packages.mapNotNull { pkg ->
            coroutineContext.ensureActive()
            val info = pkg.applicationInfo ?: return@mapNotNull null
            if (!candidate(info)) return@mapNotNull null
            val label = try {
                manager.getApplicationLabel(info).toString().trim().ifEmpty { info.packageName }
            } catch (_: Resources.NotFoundException) { info.packageName }
            InstalledUserApp(info.packageName, label, installedAt = pkg.firstInstallTime.takeIf { it > 0 })
        }.sortedWith { left, right ->
            collator.compare(left.label, right.label).takeIf { it != 0 }
                ?: left.packageName.compareTo(right.packageName)
        }
    }

    @Suppress("DEPRECATION")
    suspend fun withUsageDetails(apps: List<InstalledUserApp>): List<InstalledUserApp> = withContext(Dispatchers.IO) {
        if (!hasUsageAccess()) return@withContext apps
        val now = System.currentTimeMillis()
        val usage = try {
            application.getSystemService(UsageStatsManager::class.java)
                ?.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 365L * 86_400_000, now)
                .orEmpty().filter { it.lastTimeUsed in 1..now }
                .groupBy { it.packageName }.mapValues { (_, records) -> records.maxOf { it.lastTimeUsed } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RuntimeException) { emptyMap() }
        val storage = application.getSystemService(StorageStatsManager::class.java)
        apps.map { app ->
            coroutineContext.ensureActive()
            val bytes = try {
                val info = if (Build.VERSION.SDK_INT >= 33)
                    manager.getApplicationInfo(app.packageName, PackageManager.ApplicationInfoFlags.of(0))
                else manager.getApplicationInfo(app.packageName, 0)
                storage?.queryStatsForPackage(info.storageUuid, app.packageName, Process.myUserHandle())?.let {
                    AppStorage.fromStats(it.appBytes, it.dataBytes, it.cacheBytes)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            app.copy(storage = bytes, lastUsedAt = usage[app.packageName]?.takeIf { app.installedAt == null || it >= app.installedAt })
        }
    }

    @Suppress("DEPRECATION")
    suspend fun isInstalled(packageName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= 33) manager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            else manager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) { false }
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

