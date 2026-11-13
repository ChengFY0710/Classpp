package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.ScheduleFile
import com.fangyi.classpp.data.model.activeSchedule
import com.fangyi.classpp.data.store.FileScheduleStore
import com.fangyi.classpp.data.store.LoadIssue
import com.fangyi.classpp.data.store.LoadOutcome
import com.fangyi.classpp.data.store.StoreResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileScheduleStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store() = FileScheduleStore(folder.root)

    private fun fileWith(vararg schedules: Schedule, active: String? = "s1") = ScheduleFile(
        activeScheduleId = active,
        schedules = schedules.toList(),
    )

    private fun mainFile() = File(folder.root, FileScheduleStore.MAIN_NAME)
    private fun bakFile() = File(folder.root, FileScheduleStore.BAK_NAME)
    private fun tmpFile() = File(folder.root, FileScheduleStore.TMP_NAME)

    @Test
    fun `fresh when no file exists`() {
        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Fresh)
        assertEquals(LoadIssue.None, outcome.issue)
        assertTrue((outcome as LoadOutcome.Fresh<ScheduleFile>).file.schedules.isEmpty())
        assertNull(outcome.file.activeScheduleId)
    }

    @Test
    fun `save then load roundtrips and leaves no tmp`() {
        val original = fileWith(testSchedule(id = "s1"), active = "s1")
        assertEquals(StoreResult.Ok, store().save(original))

        assertTrue(mainFile().exists())
        assertFalse("tmp must not remain", tmpFile().exists())
        assertTrue("bak mirrors latest save", bakFile().exists())

        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Loaded)
        assertEquals(LoadIssue.None, outcome.issue)
        assertEquals(original, (outcome as LoadOutcome.Loaded).file)
    }

    @Test
    fun `bak mirrors latest save for lossless recovery`() {
        val v1 = fileWith(testSchedule(id = "s1", name = "第一版"))
        val v2 = fileWith(testSchedule(id = "s1", name = "第二版"))
        store().save(v1)
        store().save(v2)

        val bakParsed = ScheduleJson.decodeStorage<ScheduleFile>(bakFile().readText())
        assertEquals(v2, bakParsed)
    }

    @Test
    fun `corrupt main restores from bak`() {
        val state = fileWith(testSchedule(id = "s1", name = "完好数据"), active = "s1")
        store().save(state)

        mainFile().writeText("{ this is not json !!!")

        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Loaded)
        assertEquals(LoadIssue.RestoredFromBackup, outcome.issue)
        assertEquals(state, (outcome as LoadOutcome.Loaded).file)
        // 损坏主文件不被覆盖，留待取证/下次保存替换
        assertTrue(mainFile().exists())
    }

    @Test
    fun `double corruption quarantines main and resets`() {
        store().save(fileWith(testSchedule(id = "s1")))
        mainFile().writeText("garbage")
        bakFile().writeText("also garbage")

        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Fresh)
        assertEquals(LoadIssue.ResetAfterCorruption, outcome.issue)

        // 损坏主文件被改名保留
        val quarantined = folder.root.listFiles { f -> f.name.startsWith(FileScheduleStore.CORRUPT_PREFIX) }
        assertEquals(1, quarantined?.size)

        // 重置后可以正常保存/载入
        val fresh = fileWith(testSchedule(id = "s2", name = "重置后"), active = "s2")
        assertEquals(StoreResult.Ok, store().save(fresh))
        assertEquals(fresh, (store().load() as LoadOutcome.Loaded).file)
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
        val future = """{"formatVersion":99,"activeScheduleId":null,"schedules":[]}"""
        mainFile().writeText(future)
        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Fresh)
        assertEquals(LoadIssue.ResetAfterCorruption, outcome.issue)
        // 主文件被隔离而非静默覆盖
        val quarantined = folder.root.listFiles { f -> f.name.startsWith(FileScheduleStore.CORRUPT_PREFIX) }
        assertEquals(1, quarantined?.size)
    }

    @Test
    fun `consecutive saves keep main consistent`() {
        val v1 = fileWith(testSchedule(id = "s1", name = "A"))
        val v2 = fileWith(testSchedule(id = "s1", name = "B"), testSchedule(id = "s2", name = "C"))
        store().save(v1)
        store().save(v2)

        assertEquals(v2, (store().load() as LoadOutcome.Loaded).file)
        // 两次保存后：bak = 最新 = main
        assertEquals(v2, ScheduleJson.decodeStorage<ScheduleFile>(bakFile().readText()))
    }

    @Test
    fun `peek reads without side effects and never quarantines`() {
        val saved = fileWith(testSchedule(id = "s1", name = "A"))
        store().save(saved)
        assertEquals(saved, store().peek())

        // 主文件损坏：与 load 同序降级到 bak（bak 即上次完整落盘），且不隔离、不改文件
        mainFile().writeText("not json")
        assertEquals(saved, store().peek())
        assertTrue("peek 不得搬走主文件", mainFile().exists())
        assertTrue(
            "peek 不得生成 .corrupt-* 留证文件",
            folder.root.listFiles { f -> f.name.startsWith(FileScheduleStore.CORRUPT_PREFIX) }
                .orEmpty().isEmpty(),
        )

        // 主文件被未来版本占据：同样回落 bak，不改文件
        mainFile().writeText("""{"formatVersion":99,"activeScheduleId":null,"schedules":[]}""")
        assertEquals(saved, store().peek())
        assertTrue(mainFile().exists())

        // 主备双坏：只返回 null，仍然不隔离（隔离/取证是 load 的职责）
        bakFile().writeText("also not json")
        assertNull(store().peek())
        assertTrue(mainFile().exists())
        assertTrue(
            folder.root.listFiles { f -> f.name.startsWith(FileScheduleStore.CORRUPT_PREFIX) }
                .orEmpty().isEmpty(),
        )
    }

    @Test
    fun `activeSchedule falls back to first entry when id dangles`() {
        val a = testSchedule(id = "s1", name = "A")
        val b = testSchedule(id = "s2", name = "B")

        // 悬空 id 回落首项；id 为空视为无课表（与仓库装载修复后的语义一致）
        assertEquals(a, ScheduleFile(activeScheduleId = "gone", schedules = listOf(a, b)).activeSchedule)
        assertEquals(b, ScheduleFile(activeScheduleId = "s2", schedules = listOf(a, b)).activeSchedule)
        assertNull(ScheduleFile(activeScheduleId = null, schedules = listOf(a, b)).activeSchedule)
        assertNull(ScheduleFile(activeScheduleId = "s1", schedules = emptyList()).activeSchedule)
    }
}
