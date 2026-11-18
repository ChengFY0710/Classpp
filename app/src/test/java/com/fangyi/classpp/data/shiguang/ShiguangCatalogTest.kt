package com.fangyi.classpp.data.shiguang

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 拾光索引的拉取、缓存与回退 */
class ShiguangCatalogTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class FakeFetcher(var response: ByteArray?) : ShiguangWarehouseFetcher {
        val urls = mutableListOf<String>()

        override suspend fun fetch(url: String): ByteArray? {
            urls += url
            return response
        }
    }

    @Test
    fun `refresh writes cache and loadCached reads it back`() = runBlocking {
        val fetcher = FakeFetcher(ShiguangTestFixtures.indexBytes())
        val catalog = ShiguangCatalog(fetcher, temp.root)

        assertFalse(catalog.hasCache())
        val fresh = catalog.refresh()
        assertEquals(listOf("CQU"), fresh!!.map { it.adapterId })
        assertEquals(listOf(ShiguangWarehouseConfig.INDEX_URL), fetcher.urls)
        assertTrue(catalog.hasCache())
        assertTrue(File(temp.root, ShiguangWarehouseConfig.INDEX_CACHE_FILE).isFile)

        val cached = catalog.loadCached()
        assertEquals(fresh, cached)
    }

    @Test
    fun `refresh failure falls back to existing cache`() = runBlocking {
        val cacheDir = temp.root
        ShiguangCatalog(FakeFetcher(ShiguangTestFixtures.indexBytes()), cacheDir).refresh()

        val offline = ShiguangCatalog(FakeFetcher(null), cacheDir)
        assertNull(offline.refresh())
        assertEquals(listOf("CQU"), offline.loadCached().map { it.adapterId })
    }

    @Test
    fun `unsupported protocol version is rejected`() = runBlocking {
        val catalog = ShiguangCatalog(
            FakeFetcher(ShiguangTestFixtures.indexBytes(protocolVersion = 3)),
            temp.root,
        )
        assertNull(catalog.refresh())
        assertTrue(catalog.loadCached().isEmpty())
    }

    @Test
    fun `unsupported cached version is ignored`() = runBlocking {
        File(temp.root, ShiguangWarehouseConfig.INDEX_CACHE_FILE)
            .writeBytes(ShiguangTestFixtures.indexBytes(protocolVersion = 3))
        val catalog = ShiguangCatalog(FakeFetcher(null), temp.root)
        assertFalse(catalog.hasCache())
        assertTrue(catalog.loadCached().isEmpty())
    }

    @Test
    fun `empty but valid cache still counts as cached`() = runBlocking {
        val catalog = ShiguangCatalog(
            FakeFetcher(ShiguangTestFixtures.indexBytes(schools = emptyList())),
            temp.root,
        )
        assertFalse(catalog.hasCache())
        assertTrue(catalog.refresh()!!.isEmpty())
        // 空列表也是成功拉取过的索引：后续打开不应再自动联网
        assertTrue(catalog.hasCache())
        assertTrue(catalog.loadCached().isEmpty())
    }

    @Test
    fun `initial fetch marker is one-shot and survives restart`() = runBlocking {
        val catalog = ShiguangCatalog(FakeFetcher(null), temp.root)
        assertFalse(catalog.hasInitialFetchAttempted())

        catalog.markInitialFetchAttempted()
        assertTrue(catalog.hasInitialFetchAttempted())

        val restarted = ShiguangCatalog(FakeFetcher(null), temp.root)
        assertTrue(restarted.hasInitialFetchAttempted())
    }

    @Test
    fun `html anti hotlink response is rejected`() = runBlocking {
        val catalog = ShiguangCatalog(
            FakeFetcher("<html><body>denied</body></html>".toByteArray()),
            temp.root,
        )
        assertNull(catalog.refresh())
    }

    @Test
    fun `empty index is a valid empty list`() = runBlocking {
        val catalog = ShiguangCatalog(
            FakeFetcher(ShiguangTestFixtures.indexBytes(schools = emptyList())),
            temp.root,
        )
        assertTrue(catalog.refresh()!!.isEmpty())
        assertTrue(catalog.loadCached().isEmpty())
    }

    @Test
    fun `fetcher exception is contained`() = runBlocking {
        val catalog = ShiguangCatalog(
            ShiguangWarehouseFetcher { error("network down") },
            temp.root,
        )
        assertNull(catalog.refresh())
        assertTrue(catalog.loadCached().isEmpty())
    }

    @Test
    fun `parsed cache is reused without touching disk again`() = runBlocking {
        val catalog = ShiguangCatalog(FakeFetcher(ShiguangTestFixtures.indexBytes()), temp.root)
        catalog.refresh()
        // 删掉磁盘缓存：本实例已成功解析过一次，后续读取必须命中进程内缓存
        assertTrue(File(temp.root, ShiguangWarehouseConfig.INDEX_CACHE_FILE).delete())
        assertEquals(listOf("CQU"), catalog.loadCached().map { it.adapterId })
        assertTrue(catalog.hasCache())
        assertTrue(catalog.cachedAdaptersOrNull() != null)
    }

    @Test
    fun `loadCachedOrNull distinguishes missing cache from an empty one`() = runBlocking {
        val catalog = ShiguangCatalog(
            FakeFetcher(ShiguangTestFixtures.indexBytes(schools = emptyList())),
            temp.root,
        )
        // 没缓存 → null（入口据此走首次拉取 / 错误态）
        assertNull(catalog.loadCachedOrNull())
        assertTrue(catalog.refresh()!!.isEmpty())
        // 缓存有效但 0 个适配 → 空列表（入口据此展示空态，不再联网）
        assertEquals(emptyList<ShiguangAdapter>(), catalog.loadCachedOrNull())
    }
}
