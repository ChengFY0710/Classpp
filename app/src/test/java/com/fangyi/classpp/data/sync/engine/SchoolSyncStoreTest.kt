package com.fangyi.classpp.data.sync.engine

import com.fangyi.classpp.data.store.LoadIssue
import com.fangyi.classpp.data.store.LoadOutcome
import com.fangyi.classpp.data.store.StoreResult
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 通用同步状态存储：路径命名空间、原子写、备份恢复、双坏隔离（指导 §2.4 第 6 条 /
 * §2.6）。**不带任何学校历史迁移**——这里同时锁死「只复制原子协议」的边界。
 */
class SchoolSyncStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(id: String = "fzu") = SchoolSyncStore(folder.root, id)

    @Test
    fun `storage lives under filesDir sync school path`() {
        val store = store("fzu")
        assertEquals(StoreResult.Ok, store.save(SchoolSyncFile(uid = "1")))

        val main = File(folder.root, "sync/fzu/sync.json")
        assertTrue("expected $main", main.isFile)
        assertFalse(File(folder.root, "fzu/sync.json").exists())
    }

    @Test
    fun `save then load round trips entries`() {
        val store = store()
        val file = SchoolSyncFile(
            uid = "42",
            accountName = "张三",
            avatarUrl = "https://example.test/a.png",
            lastSyncAtMillis = 100,
            lastProbeAtMillis = 90,
            entries = listOf(
                SchoolSyncEntry(
                    remoteKey = "fzu:1:2",
                    todoId = "fzu-1-2",
                    remoteHash = "r",
                    localHash = "l",
                ),
            ),
        )
        assertEquals(StoreResult.Ok, store.save(file))

        val loaded = store.load()
        assertTrue(loaded is LoadOutcome.Loaded)
        assertEquals(file, (loaded as LoadOutcome.Loaded).file)
        assertEquals(LoadIssue.None, loaded.issue)
    }

    @Test
    fun `school namespaces are isolated`() {
        store("fzu").save(SchoolSyncFile(uid = "fzu-user"))
        store("ncu").save(SchoolSyncFile(uid = "ncu-user"))

        assertEquals("fzu-user", (store("fzu").load() as LoadOutcome.Loaded).file.uid)
        assertEquals("ncu-user", (store("ncu").load() as LoadOutcome.Loaded).file.uid)
    }

    @Test
    fun `missing main file falls back to backup`() {
        val store = store()
        store.save(SchoolSyncFile(uid = "7"))
        File(folder.root, "sync/fzu/sync.json").delete()

        val loaded = store.load()
        assertTrue(loaded is LoadOutcome.Loaded)
        assertEquals(LoadIssue.RestoredFromBackup, loaded.issue)
        assertEquals("7", (loaded as LoadOutcome.Loaded).file.uid)
    }

    @Test
    fun `double corruption quarantines main and returns fresh state`() {
        val store = store()
        store.save(SchoolSyncFile(uid = "9"))
        val root = File(folder.root, "sync/fzu")
        File(root, "sync.json").writeText("{broken")
        File(root, "sync.json.bak").writeText("also broken")

        val loaded = store.load()
        assertTrue(loaded is LoadOutcome.Fresh)
        assertEquals(LoadIssue.ResetAfterCorruption, loaded.issue)
        assertEquals("", (loaded as LoadOutcome.Fresh).file.uid)
        assertTrue(
            root.listFiles().orEmpty().any { it.name.startsWith("sync.corrupt-") },
        )
    }

    @Test
    fun `future format version is treated as corruption`() {
        val store = store()
        val root = File(folder.root, "sync/fzu").apply { mkdirs() }
        File(root, "sync.json").writeText(
            """{"formatVersion":99,"uid":"x","accountName":"","avatarUrl":"","lastSyncAtMillis":0,"lastProbeAtMillis":0,"entries":[]}""",
        )

        val loaded = store.load()
        assertTrue(loaded is LoadOutcome.Fresh)
        assertEquals(LoadIssue.ResetAfterCorruption, loaded.issue)
    }

    @Test
    fun `delete removes state temp backup and quarantine files`() {
        val store = store()
        store.save(SchoolSyncFile(uid = "1"))
        val root = File(folder.root, "sync/fzu")
        File(root, "sync.json.tmp").writeText("tmp")
        File(root, "sync.corrupt-1.json").writeText("bad")

        store.delete()

        assertFalse(File(root, "sync.json").exists())
        assertFalse(File(root, "sync.json.tmp").exists())
        assertFalse(File(root, "sync.json.bak").exists())
        assertFalse(File(root, "sync.corrupt-1.json").exists())
    }
}
