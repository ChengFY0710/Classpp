package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.TagEntry
import com.fangyi.classpp.data.model.TagFile
import com.fangyi.classpp.data.store.FileTagStore
import com.fangyi.classpp.data.store.LoadIssue
import com.fangyi.classpp.data.store.LoadOutcome
import com.fangyi.classpp.data.store.StoreResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 与 FileTodoStoreTest 同一套协议断言，只是文件名与信封类型换成用户标签 */
class FileTagStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store() = FileTagStore(folder.root)

    private fun fileWith(vararg tags: TagEntry) = TagFile(tags = tags.toList())

    private fun mainFile() = File(folder.root, FileTagStore.MAIN_NAME)
    private fun bakFile() = File(folder.root, FileTagStore.BAK_NAME)
    private fun tmpFile() = File(folder.root, FileTagStore.TMP_NAME)

    @Test
    fun `fresh when no file exists`() {
        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Fresh)
        assertEquals(LoadIssue.None, outcome.issue)
        assertTrue((outcome as LoadOutcome.Fresh<TagFile>).file.tags.isEmpty())
    }

    @Test
    fun `save then load roundtrips and leaves no tmp`() {
        val original = fileWith(
            TagEntry("紧急"),
            TagEntry("实验报告", colorArgb = 0xFF22B14C),
        )
        assertEquals(StoreResult.Ok, store().save(original))

        assertTrue(mainFile().exists())
        assertFalse("tmp must not remain", tmpFile().exists())
        assertTrue("bak mirrors latest save", bakFile().exists())

        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Loaded)
        assertEquals(LoadIssue.None, outcome.issue)
        assertEquals(original, (outcome as LoadOutcome.Loaded<TagFile>).file)
    }

    @Test
    fun `bak mirrors latest save for lossless recovery`() {
        val v1 = fileWith(TagEntry("第一版"))
        val v2 = fileWith(TagEntry("第二版"))
        store().save(v1)
        store().save(v2)

        val bakParsed = ScheduleJson.decodeStorage<TagFile>(bakFile().readText())
        assertEquals(v2, bakParsed)
    }

    @Test
    fun `corrupt main restores from bak`() {
        val state = fileWith(TagEntry("完好数据"))
        store().save(state)

        mainFile().writeText("{ this is not json !!!")

        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Loaded)
        assertEquals(LoadIssue.RestoredFromBackup, outcome.issue)
        assertEquals(state, (outcome as LoadOutcome.Loaded<TagFile>).file)
        // 损坏主文件不被覆盖，留待取证/下次保存替换
        assertTrue(mainFile().exists())
    }

    @Test
    fun `double corruption quarantines main and resets`() {
        store().save(fileWith(TagEntry("紧急")))
        mainFile().writeText("garbage")
        bakFile().writeText("also garbage")

        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Fresh)
        assertEquals(LoadIssue.ResetAfterCorruption, outcome.issue)

        // 损坏主文件被改名保留
        val quarantined = folder.root.listFiles { f -> f.name.startsWith(FileTagStore.CORRUPT_PREFIX) }
        assertEquals(1, quarantined?.size)

        // 重置后可以正常保存/载入
        val fresh = fileWith(TagEntry("重置后"))
        assertEquals(StoreResult.Ok, store().save(fresh))
        assertEquals(fresh, (store().load() as LoadOutcome.Loaded<TagFile>).file)
    }

    @Test
    fun `empty main file degrades to fresh`() {
        mainFile().writeText("")
        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Fresh)
        assertEquals(LoadIssue.ResetAfterCorruption, outcome.issue)
    }

    @Test
    fun `future formatVersion degrades like corruption`() {
        val future = """{"formatVersion":99,"tags":[]}"""
        mainFile().writeText(future)
        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Fresh)
        assertEquals(LoadIssue.ResetAfterCorruption, outcome.issue)
        // 主文件被隔离而非静默覆盖
        val quarantined = folder.root.listFiles { f -> f.name.startsWith(FileTagStore.CORRUPT_PREFIX) }
        assertEquals(1, quarantined?.size)
    }

    @Test
    fun `consecutive saves keep main consistent`() {
        val v1 = fileWith(TagEntry("A"))
        val v2 = fileWith(TagEntry("B"), TagEntry("C", colorArgb = 0xFFE3C160))
        store().save(v1)
        store().save(v2)

        assertEquals(v2, (store().load() as LoadOutcome.Loaded<TagFile>).file)
        // 两次保存后：bak = 最新 = main
        assertEquals(v2, ScheduleJson.decodeStorage<TagFile>(bakFile().readText()))
    }
}
