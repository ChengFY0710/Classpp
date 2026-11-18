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

/** 拾光适配脚本的 URL 构造、内容校验与缓存回退 */
class ShiguangAdapterStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class SequenceFetcher(vararg responses: ByteArray?) : ShiguangWarehouseFetcher {
        private val queue = responses.toMutableList()
        val urls = mutableListOf<String>()

        override suspend fun fetch(url: String): ByteArray? {
            urls += url
            return if (queue.isEmpty()) null else queue.removeAt(0)
        }
    }

    private val validScript = "window.shiguangBridgePromise.saveImportedCourses('[]');".toByteArray()

    @Test
    fun `script url is built from parsed paths`() {
        val url = scriptUrl(ShiguangTestFixtures.validAdapter())
        assertEquals(
            ShiguangTestFixtures.SCRIPT_URL_PREFIX + "CQU/cqu.js",
            url,
        )
    }

    @Test
    fun `unsafe paths are rejected before fetching`() = runBlocking {
        val fetcher = SequenceFetcher(validScript)
        val store = ShiguangAdapterStore(fetcher, temp.root)
        assertNull(
            store.ensureScript(ShiguangTestFixtures.validAdapter(resourceFolder = "../CQU")),
        )
        assertNull(
            store.ensureScript(ShiguangTestFixtures.validAdapter(assetPath = "../cqu.js")),
        )
        assertTrue(fetcher.urls.isEmpty())
    }

    @Test
    fun `downloaded script is cached and reused offline`() = runBlocking {
        val adapter = ShiguangTestFixtures.validAdapter()
        val online = ShiguangAdapterStore(SequenceFetcher(validScript), temp.root)
        assertEquals(validScript.toString(Charsets.UTF_8), online.ensureScript(adapter))
        assertTrue(File(temp.root, adapterCacheFileName(adapter)).isFile)

        val offline = ShiguangAdapterStore(SequenceFetcher(null), temp.root)
        assertEquals(validScript.toString(Charsets.UTF_8), offline.ensureScript(adapter))
    }

    @Test
    fun `html response falls back to cached script`() = runBlocking {
        val adapter = ShiguangTestFixtures.validAdapter()
        ShiguangAdapterStore(SequenceFetcher(validScript), temp.root).ensureScript(adapter)

        val store = ShiguangAdapterStore(
            SequenceFetcher("<html>anti hotlink</html>".toByteArray()),
            temp.root,
        )
        assertEquals(validScript.toString(Charsets.UTF_8), store.ensureScript(adapter))
    }

    @Test
    fun `html response without cache is rejected`() = runBlocking {
        val store = ShiguangAdapterStore(
            SequenceFetcher("<html>anti hotlink</html>".toByteArray()),
            temp.root,
        )
        assertNull(store.ensureScript(ShiguangTestFixtures.validAdapter()))
    }

    @Test
    fun `script without import marker is rejected`() = runBlocking {
        val store = ShiguangAdapterStore(
            SequenceFetcher("console.log('hello')".toByteArray()),
            temp.root,
        )
        assertNull(store.ensureScript(ShiguangTestFixtures.validAdapter()))
    }

    @Test
    fun `cache file name only keeps safe characters`() {
        val adapter = ShiguangTestFixtures.validAdapter(
            schoolId = "a/b",
            adapterId = "c:d",
        )
        assertEquals("a_b_c_d.js", adapterCacheFileName(adapter))
    }

    @Test
    fun `script content validation`() {
        assertTrue(isValidShiguangScript("var x=1;saveImportedCourses('[]')"))
        assertFalse(isValidShiguangScript(""))
        assertFalse(isValidShiguangScript("   "))
        assertFalse(isValidShiguangScript("<html>saveImportedCourses</html>"))
        assertFalse(isValidShiguangScript("console.log('no marker')"))
    }
}
