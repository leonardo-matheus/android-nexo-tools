package br.com.nexotools.domain

import org.junit.Assert.*
import org.junit.Test

class AppStorageTest {
    @Test fun cacheIsPartOfDataNotAnAdditionalTotal() {
        val storage = AppStorage.fromStats(70, 50, 15)
        assertEquals(AppStorage(70, 35, 15), storage)
        assertEquals(120L, storage.totalBytes)
    }

    @Test fun invalidStatisticsCannotCreateNegativeOrDuplicatedBytes() {
        assertEquals(AppStorage(0, 0, 0), AppStorage.fromStats(-7, -5, 10))
        assertEquals(AppStorage(70, 0, 50), AppStorage.fromStats(70, 50, 80))
        assertEquals(AppStorage(70, 50, 0), AppStorage.fromStats(70, 50, -8))
        assertEquals(120L, AppStorage.fromStats(70, 50, 80).totalBytes)
    }

    @Test fun cachedBytesDoNotPushAnAppAcrossTheSizeFilter() {
        val app = InstalledUserApp(
            "org.example.fixture.cache", "Synthetic cache",
            storage = AppStorage.fromStats(69_000_000, 30_000_000, 20_000_000),
        )
        assertEquals(99_000_000L, app.storage!!.totalBytes)
        assertTrue(InstalledAppRules.filterAndSort(
            listOf(app), "", AppSort.SIZE_DESC, size = AppSizeFilter.MB_100,
        ).isEmpty())
    }
}
