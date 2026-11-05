package com.fangyi.classpp.data.backup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 存储用量求和：递归、缺失目录、空目录与合计 */
class StorageUsageTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `dirBytes sums nested files`() {
        val root = folder.newFolder("data")
        File(root, "a.txt").writeBytes(ByteArray(10))
        val sub = File(root, "sub").apply { mkdirs() }
        File(sub, "b.txt").writeBytes(ByteArray(5))

        assertEquals(15L, StorageUsageReader.dirBytes(root))
    }

    @Test
    fun `dirBytes returns zero for missing dir and empty dir`() {
        assertEquals(0L, StorageUsageReader.dirBytes(null))
        assertEquals(0L, StorageUsageReader.dirBytes(File(folder.root, "missing")))
        assertEquals(0L, StorageUsageReader.dirBytes(folder.newFolder("empty")))
    }

    @Test
    fun `total is the sum of three segments`() {
        val usage = StorageUsage(appBytes = 13L * 1024 * 1024, userBytes = 1200L, cacheBytes = 21L)
        assertEquals(13L * 1024 * 1024 + 1200L + 21L, usage.totalBytes)
    }
}
