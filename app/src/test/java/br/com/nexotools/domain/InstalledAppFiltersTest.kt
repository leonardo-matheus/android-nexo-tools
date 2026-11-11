package br.com.nexotools.domain

import org.junit.Assert.*
import org.junit.Test

class InstalledAppFiltersTest {
    private val now = 2_000_000_000_000L
    private val day = 86_400_000L
    private val numericSorts = AppSort.values().filter { it != AppSort.NAME }
    private fun ids(apps: List<InstalledUserApp>) = apps.map { it.packageName }
    private fun measured(id: String, label: String, value: Long?) = InstalledUserApp(
        "org.example.fixture.$id", label, installedAt = value,
        storage = value?.let { AppStorage(it, 0, 0) }, lastUsedAt = value,
    )

    @Test fun missingMetricsStayLastInBothDirectionsForEveryNumericSort() {
        val low = measured("low", "Zulu", 100)
        val high = measured("high", "Alpha", 200)
        val unknownZ = measured("unknown_z", "Aardvark", null)
        val unknownA = measured("unknown_a", "Aardvark", null)
        numericSorts.forEach { sort ->
            val descending = sort in setOf(AppSort.SIZE_DESC, AppSort.INSTALLED_DESC, AppSort.USED_DESC)
            val known = if (descending) listOf(high, low) else listOf(low, high)
            assertEquals(sort.name, ids(known + listOf(unknownA, unknownZ)),
                ids(InstalledAppRules.filterAndSort(listOf(unknownZ, high, unknownA, low), "", sort, now = now)))
        }
    }

    @Test fun tiesHaveStableAlphabeticalNamesAndPackageTieBreaks() {
        val alphaB = measured("b", "Alpha", 100)
        val beta = measured("c", "Beta", 100)
        val alphaA = measured("a", "Alpha", 100)
        AppSort.values().forEach { sort ->
            assertEquals(sort.name, ids(listOf(alphaA, alphaB, beta)),
                ids(InstalledAppRules.filterAndSort(listOf(beta, alphaB, alphaA), "", sort, now = now)))
        }
    }

    @Test fun decimalSizeThresholdsIncludeExactBoundaryAndExcludeMissingSize() {
        listOf(AppSizeFilter.MB_100, AppSizeFilter.GB_1).forEach { filter ->
            val below = measured("below", "Below", filter.minimum - 1)
            val exact = measured("exact", "Exact", filter.minimum)
            val above = measured("above", "Above", filter.minimum + 1)
            val unknown = measured("unknown", "Unknown", null)
            assertEquals(filter.name, ids(listOf(exact, above)), ids(InstalledAppRules.filterAndSort(
                listOf(unknown, above, below, exact), "", AppSort.SIZE_ASC, size = filter, now = now,
            )))
        }
    }

    @Test fun installWindowsIncludeBothBoundariesButExcludeFutureAndMissingDates() {
        listOf(AppInstallFilter.DAYS_7, AppInstallFilter.DAYS_30).forEach { filter ->
            val cutoff = now - filter.days * day
            val exact = measured("exact", "Exact", cutoff)
            val today = measured("today", "Today", now)
            val before = measured("before", "Before", cutoff - 1)
            val future = measured("future", "Future", now + 1)
            val unknown = measured("unknown", "Unknown", null)
            assertEquals(filter.name, ids(listOf(exact, today)), ids(InstalledAppRules.filterAndSort(
                listOf(future, unknown, before, today, exact), "", AppSort.INSTALLED_ASC,
                installed = filter, now = now,
            )))
        }
    }

    @Test fun elapsedUsageFiltersRequireKnownTimestampAndIncludeExactAge() {
        listOf(AppUsageFilter.DAYS_30 to 30, AppUsageFilter.DAYS_90 to 90).forEach { (filter, days) ->
            val cutoff = now - days * day
            val older = measured("older", "Older", cutoff - 1)
            val exact = measured("exact", "Exact", cutoff)
            val recent = measured("recent", "Recent", cutoff + 1)
            val unknown = measured("unknown", "Unknown", null)
            assertEquals(filter.name, ids(listOf(older, exact)), ids(InstalledAppRules.filterAndSort(
                listOf(unknown, recent, exact, older), "", AppSort.USED_ASC, usage = filter, now = now,
            )))
            assertEquals(ids(listOf(unknown)), ids(InstalledAppRules.filterAndSort(
                listOf(unknown, recent, exact, older), "", AppSort.NAME,
                usage = AppUsageFilter.NO_RECORD, now = now,
            )))
        }
    }

    @Test fun querySizeInstallAndUsageFiltersAreCombinedWithAnd() {
        val cutoff = now - 30 * day
        val good = InstalledUserApp(
            "org.example.fixture.tools.good", "Synthetic tools", cutoff,
            AppStorage(100_000_000, 0, 0), cutoff,
        )
        val apps = listOf(
            good,
            good.copy(packageName = "org.example.fixture.other", label = "Other"),
            good.copy(packageName = "org.example.fixture.tools.small", storage = AppStorage(99_999_999, 0, 0)),
            good.copy(packageName = "org.example.fixture.tools.old", installedAt = cutoff - 1),
            good.copy(packageName = "org.example.fixture.tools.future", installedAt = now + 1),
            good.copy(packageName = "org.example.fixture.tools.recent", lastUsedAt = cutoff + 1),
            good.copy(packageName = "org.example.fixture.tools.no_size", storage = null),
            good.copy(packageName = "org.example.fixture.tools.no_install", installedAt = null),
            good.copy(packageName = "org.example.fixture.tools.no_usage", lastUsedAt = null),
        )
        assertEquals(ids(listOf(good)), ids(InstalledAppRules.filterAndSort(
            apps, "  TOOLS  ", AppSort.NAME, AppSizeFilter.MB_100,
            AppInstallFilter.DAYS_30, AppUsageFilter.DAYS_30, now,
        )))
    }

    @Test fun allFiltersPreserveAppsWhoseMetadataIsUnavailable() {
        val unknown = measured("unknown", "Alpha", null)
        val known = measured("known", "Beta", 100)
        assertEquals(ids(listOf(unknown, known)), ids(InstalledAppRules.filterAndSort(
            listOf(known, unknown), "", AppSort.NAME, now = now,
        )))
    }
}
