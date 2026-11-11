package br.com.nexotools.domain

import java.text.Collator

data class InstalledUserApp(
    val packageName: String,
    val label: String,
    val installedAt: Long? = null,
    val storage: AppStorage? = null,
    val lastUsedAt: Long? = null,
)

data class AppStorage(val appBytes: Long, val dataBytes: Long, val cacheBytes: Long) {
    val totalBytes: Long get() = appBytes + dataBytes + cacheBytes

    companion object {
        fun fromStats(appBytes: Long, dataIncludingCache: Long, cacheBytes: Long): AppStorage {
            val data = dataIncludingCache.coerceAtLeast(0)
            val cache = cacheBytes.coerceIn(0, data)
            return AppStorage(appBytes.coerceAtLeast(0), data - cache, cache)
        }
    }
}

enum class AppSort { NAME, SIZE_DESC, SIZE_ASC, INSTALLED_DESC, INSTALLED_ASC, USED_DESC, USED_ASC }
enum class AppSizeFilter(val minimum: Long) { ALL(0), MB_100(100_000_000), GB_1(1_000_000_000) }
enum class AppInstallFilter(val days: Int) { ALL(0), DAYS_7(7), DAYS_30(30) }
enum class AppUsageFilter { ALL, DAYS_30, DAYS_90, NO_RECORD }

object InstalledAppRules {
    fun isCandidate(packageName: String, ownPackage: String, system: Boolean, systemUpdate: Boolean): Boolean =
        packageName.isNotBlank() && packageName != ownPackage && !system && !systemUpdate

    fun matches(app: InstalledUserApp, query: String): Boolean {
        val term = query.trim()
        return term.isEmpty() || app.label.contains(term, ignoreCase = true) ||
            app.packageName.contains(term, ignoreCase = true)
    }

    fun filterAndSort(
        apps: List<InstalledUserApp>, query: String, sort: AppSort,
        size: AppSizeFilter = AppSizeFilter.ALL,
        installed: AppInstallFilter = AppInstallFilter.ALL,
        usage: AppUsageFilter = AppUsageFilter.ALL,
        now: Long = System.currentTimeMillis(),
    ): List<InstalledUserApp> {
        val day = 86_400_000L
        val collator = Collator.getInstance()
        val names = Comparator<InstalledUserApp> { a, b ->
            collator.compare(a.label, b.label).takeIf { it != 0 } ?: a.packageName.compareTo(b.packageName)
        }
        val order = Comparator<InstalledUserApp> { a, b ->
            val left = when (sort) {
                AppSort.SIZE_ASC, AppSort.SIZE_DESC -> a.storage?.totalBytes
                AppSort.INSTALLED_ASC, AppSort.INSTALLED_DESC -> a.installedAt
                AppSort.USED_ASC, AppSort.USED_DESC -> a.lastUsedAt
                AppSort.NAME -> null
            }
            val right = when (sort) {
                AppSort.SIZE_ASC, AppSort.SIZE_DESC -> b.storage?.totalBytes
                AppSort.INSTALLED_ASC, AppSort.INSTALLED_DESC -> b.installedAt
                AppSort.USED_ASC, AppSort.USED_DESC -> b.lastUsedAt
                AppSort.NAME -> null
            }
            when {
                left == null && right == null -> 0
                left == null -> 1
                right == null -> -1
                sort in setOf(AppSort.SIZE_DESC, AppSort.INSTALLED_DESC, AppSort.USED_DESC) -> right.compareTo(left)
                else -> left.compareTo(right)
            }.takeIf { it != 0 } ?: names.compare(a, b)
        }
        return apps.filter { app ->
            matches(app, query) &&
                (size == AppSizeFilter.ALL || app.storage?.totalBytes?.let { it >= size.minimum } == true) &&
                (installed == AppInstallFilter.ALL || app.installedAt?.let { it in (now - installed.days * day)..now } == true) &&
                when (usage) {
                    AppUsageFilter.ALL -> true
                    AppUsageFilter.NO_RECORD -> app.lastUsedAt == null
                    AppUsageFilter.DAYS_30 -> app.lastUsedAt?.let { it <= now - 30 * day } == true
                    AppUsageFilter.DAYS_90 -> app.lastUsedAt?.let { it <= now - 90 * day } == true
                }
        }.sortedWith(order)
    }
}

