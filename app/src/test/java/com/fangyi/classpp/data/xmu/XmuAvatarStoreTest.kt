package com.fangyi.classpp.data.xmu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 头像缓存落盘（[XmuAvatarStore]）：单文件、覆盖写、超限拒绝、退出登录删除。
 * 目录与同步状态文件同处 `filesDir/xmu/`（备份规则已整目录排除）。
 */
class XmuAvatarStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `保存后可读回`() {
        val store = XmuAvatarStore(folder.root)
        assertNull(store.cachedImage())

        val saved = store.save(byteArrayOf(1, 2, 3))

        assertTrue(saved != null && saved.file.exists())
        assertArrayEquals(byteArrayOf(1, 2, 3), saved!!.file.readBytes())
        assertEquals(saved, store.cachedImage())
    }

    @Test
    fun `再次保存覆盖旧图并换版本号`() {
        val store = XmuAvatarStore(folder.root)
        val first = store.save(byteArrayOf(1, 2, 3))!!
        // 把旧图时间戳往回拨：避免依赖文件系统时间粒度，第二次保存必然拿到更晚的修改时间
        first.file.setLastModified(first.file.lastModified() - 10_000)

        val second = store.save(byteArrayOf(9, 9))!!

        // 同一路径就地覆盖：版本号（修改时间）必须变，UI 才会重建 Coil 请求
        assertEquals(first.file, second.file)
        assertNotEquals(first.version, second.version)
        assertArrayEquals(byteArrayOf(9, 9), second.file.readBytes())
    }

    @Test
    fun `空数据与超限数据都不落盘`() {
        val store = XmuAvatarStore(folder.root)

        assertNull(store.save(ByteArray(0)))
        assertNull(store.save(ByteArray(XmuAvatarStore.MAX_BYTES + 1)))

        assertNull(store.cachedImage())
    }

    @Test
    fun `删除后不再存在，重复删除不抛异常`() {
        val store = XmuAvatarStore(folder.root)
        store.save(byteArrayOf(1))

        store.delete()
        assertNull(store.cachedImage())
        store.delete()

        assertNull(store.cachedImage())
    }
}
