package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.TodoFile
import com.fangyi.classpp.data.store.FileTodoStore
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

/** 与 FileScheduleStoreTest 同一套协议断言，只是文件名与信封类型换成待办 */
class FileTodoStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store() = FileTodoStore(folder.root)

    private fun fileWith(vararg todos: com.fangyi.classpp.data.model.Todo) =
        TodoFile(todos = todos.toList())

    private fun mainFile() = File(folder.root, FileTodoStore.MAIN_NAME)
    private fun bakFile() = File(folder.root, FileTodoStore.BAK_NAME)
    private fun tmpFile() = File(folder.root, FileTodoStore.TMP_NAME)

    @Test
    fun `fresh when no file exists`() {
        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Fresh)
        assertEquals(LoadIssue.None, outcome.issue)
        assertTrue((outcome as LoadOutcome.Fresh<TodoFile>).file.todos.isEmpty())
    }

    @Test
    fun `save then load roundtrips and leaves no tmp`() {
        val original = fileWith(testTodo(id = "t1"), testTodo(id = "t2", name = "实验报告"))
        assertEquals(StoreResult.Ok, store().save(original))

        assertTrue(mainFile().exists())
        assertFalse("tmp must not remain", tmpFile().exists())
        assertTrue("bak mirrors latest save", bakFile().exists())

        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Loaded)
        assertEquals(LoadIssue.None, outcome.issue)
        assertEquals(original, (outcome as LoadOutcome.Loaded<TodoFile>).file)
    }

    @Test
    fun `bak mirrors latest save for lossless recovery`() {
        val v1 = fileWith(testTodo(id = "t1", name = "第一版"))
        val v2 = fileWith(testTodo(id = "t1", name = "第二版"))
        store().save(v1)
        store().save(v2)

        val bakParsed = ScheduleJson.decodeStorage<TodoFile>(bakFile().readText())
        assertEquals(v2, bakParsed)
    }

    @Test
    fun `corrupt main restores from bak`() {
        val state = fileWith(testTodo(id = "t1", name = "完好数据"))
        store().save(state)

        mainFile().writeText("{ this is not json !!!")

        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Loaded)
        assertEquals(LoadIssue.RestoredFromBackup, outcome.issue)
        assertEquals(state, (outcome as LoadOutcome.Loaded<TodoFile>).file)
        // 损坏主文件不被覆盖，留待取证/下次保存替换
        assertTrue(mainFile().exists())
    }

    @Test
    fun `double corruption quarantines main and resets`() {
        store().save(fileWith(testTodo(id = "t1")))
        mainFile().writeText("garbage")
        bakFile().writeText("also garbage")

        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Fresh)
        assertEquals(LoadIssue.ResetAfterCorruption, outcome.issue)

        // 损坏主文件被改名保留
        val quarantined = folder.root.listFiles { f -> f.name.startsWith(FileTodoStore.CORRUPT_PREFIX) }
        assertEquals(1, quarantined?.size)

        // 重置后可以正常保存/载入
        val fresh = fileWith(testTodo(id = "t2", name = "重置后"))
        assertEquals(StoreResult.Ok, store().save(fresh))
        assertEquals(fresh, (store().load() as LoadOutcome.Loaded<TodoFile>).file)
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
        val future = """{"formatVersion":99,"todos":[]}"""
        mainFile().writeText(future)
        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Fresh)
        assertEquals(LoadIssue.ResetAfterCorruption, outcome.issue)
        // 主文件被隔离而非静默覆盖
        val quarantined = folder.root.listFiles { f -> f.name.startsWith(FileTodoStore.CORRUPT_PREFIX) }
        assertEquals(1, quarantined?.size)
    }

    @Test
    fun `consecutive saves keep main consistent`() {
        val v1 = fileWith(testTodo(id = "t1", name = "A"))
        val v2 = fileWith(testTodo(id = "t1", name = "B"), testTodo(id = "t2", name = "C"))
        store().save(v1)
        store().save(v2)

        assertEquals(v2, (store().load() as LoadOutcome.Loaded<TodoFile>).file)
        // 两次保存后：bak = 最新 = main
        assertEquals(v2, ScheduleJson.decodeStorage<TodoFile>(bakFile().readText()))
    }
}
