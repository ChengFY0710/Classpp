package com.fangyi.classpp.data.xmu

import com.fangyi.classpp.data.ScheduleJson
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

/** 与 FileTodoStoreTest 同一套协议断言，只是文件名与信封类型换成同步映射 */
class XmuSyncStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store() = XmuSyncStore(folder.root)

    private fun fileWith(vararg entries: XmuSyncEntry) =
        XmuSyncFile(uid = "9001", accountName = "张三", entries = entries.toList())

    private fun entry(id: Long) = XmuSyncEntry(
        remoteId = id,
        courseId = 100,
        todoId = "xmu-$id",
        remoteHash = "r$id",
        localHash = "l$id",
    )

    private fun mainFile() = File(folder.root, XmuSyncStore.MAIN_NAME)
    private fun bakFile() = File(folder.root, XmuSyncStore.BAK_NAME)
    private fun tmpFile() = File(folder.root, XmuSyncStore.TMP_NAME)

    @Test
    fun `fresh when no file exists`() {
        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Fresh)
        assertEquals(LoadIssue.None, outcome.issue)
    }

    @Test
    fun `save then load roundtrips and leaves no tmp`() {
        val original = fileWith(entry(1), entry(2))
        assertEquals(StoreResult.Ok, store().save(original))

        assertTrue(mainFile().exists())
        assertFalse("tmp must not remain", tmpFile().exists())
        assertTrue("bak mirrors latest save", bakFile().exists())

        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Loaded)
        assertEquals(LoadIssue.None, outcome.issue)
        assertEquals(original, (outcome as LoadOutcome.Loaded<XmuSyncFile>).file)
    }

    @Test
    fun `corrupt main restores from bak`() {
        val state = fileWith(entry(7))
        store().save(state)

        mainFile().writeText("{ this is not json !!!")

        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Loaded)
        assertEquals(LoadIssue.RestoredFromBackup, outcome.issue)
        assertEquals(state, (outcome as LoadOutcome.Loaded<XmuSyncFile>).file)
    }

    @Test
    fun `double corruption quarantines main and resets`() {
        store().save(fileWith(entry(1)))
        mainFile().writeText("garbage")
        bakFile().writeText("also garbage")

        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Fresh)
        assertEquals(LoadIssue.ResetAfterCorruption, outcome.issue)
        val quarantined = folder.root.listFiles { f -> f.name.startsWith(XmuSyncStore.CORRUPT_PREFIX) }
        assertEquals(1, quarantined?.size)
    }

    @Test
    fun `future formatVersion degrades like corruption`() {
        mainFile().writeText("""{"formatVersion":99,"entries":[]}""")
        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Fresh)
        assertEquals(LoadIssue.ResetAfterCorruption, outcome.issue)
    }

    @Test
    fun `delete clears all three files`() {
        store().save(fileWith(entry(1)))
        store().delete()
        assertFalse(mainFile().exists())
        assertFalse(bakFile().exists())
        assertFalse(tmpFile().exists())
        assertTrue(store().load() is LoadOutcome.Fresh)
    }

    @Test
    fun `unknown fields are ignored on read`() {
        store().save(fileWith(entry(1)))
        val withExtra = bakFile().readText().replaceFirst("{", """{"platform_extra":true,""")
        mainFile().writeText(withExtra)
        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Loaded)
        assertEquals(1, (outcome as LoadOutcome.Loaded<XmuSyncFile>).file.entries.size)
    }

    @Test
    fun `json roundtrip keeps hash fidelity`() {
        val state = fileWith(entry(42))
        val text = ScheduleJson.encodeStorage(state)
        assertEquals(state, ScheduleJson.decodeStorage<XmuSyncFile>(text))
    }
}
