package com.fangyi.classpp.data.ai

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

/** 与 FileTodoStoreTest 同一套协议断言，只是文件名与信封类型换成对话历史 */
class AiChatStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store() = FileAiChatStore(folder.root)

    private fun fileWith(vararg messages: AiChatMessage) =
        AiChatFile(messages = messages.toList())

    private fun message(role: AiChatRole, content: String) =
        AiChatMessage(role = role, content = content, createdAtMillis = 1L)

    private fun mainFile() = File(folder.root, FileAiChatStore.MAIN_NAME)
    private fun bakFile() = File(folder.root, FileAiChatStore.BAK_NAME)
    private fun tmpFile() = File(folder.root, FileAiChatStore.TMP_NAME)

    @Test
    fun `fresh when no file exists`() {
        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Fresh)
        assertEquals(LoadIssue.None, outcome.issue)
        assertTrue((outcome as LoadOutcome.Fresh<AiChatFile>).file.messages.isEmpty())
    }

    @Test
    fun `save then load roundtrips and leaves no tmp`() {
        val original = fileWith(
            message(AiChatRole.User, "帮我看看下周安排"),
            message(AiChatRole.Assistant, "下周三有数学课……"),
        )
        assertEquals(StoreResult.Ok, store().save(original))

        assertTrue(mainFile().exists())
        assertFalse("tmp must not remain", tmpFile().exists())
        assertTrue("bak mirrors latest save", bakFile().exists())

        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Loaded)
        assertEquals(LoadIssue.None, outcome.issue)
        assertEquals(original, (outcome as LoadOutcome.Loaded<AiChatFile>).file)
    }

    @Test
    fun `corrupt main restores from bak`() {
        val state = fileWith(message(AiChatRole.User, "完好数据"))
        store().save(state)

        mainFile().writeText("{ this is not json !!!")

        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Loaded)
        assertEquals(LoadIssue.RestoredFromBackup, outcome.issue)
        assertEquals(state, (outcome as LoadOutcome.Loaded<AiChatFile>).file)
        // 损坏主文件不被覆盖，留待取证/下次保存替换
        assertTrue(mainFile().exists())
    }

    @Test
    fun `missing main falls back to bak`() {
        val state = fileWith(message(AiChatRole.Assistant, "崩溃前最后一次提交"))
        store().save(state)
        mainFile().delete() // 模拟进程在删主与 rename 之间被杀

        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Loaded)
        assertEquals(LoadIssue.RestoredFromBackup, outcome.issue)
        assertEquals(state, (outcome as LoadOutcome.Loaded<AiChatFile>).file)
    }

    @Test
    fun `double corrupt quarantines and resets`() {
        store().save(fileWith(message(AiChatRole.User, "旧数据")))
        mainFile().writeText("not json")
        bakFile().writeText("also not json")

        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Fresh)
        assertEquals(LoadIssue.ResetAfterCorruption, outcome.issue)
        // 主文件被改名留证
        assertFalse(mainFile().exists())
        assertTrue(
            folder.root.listFiles()!!.any {
                it.name.startsWith(FileAiChatStore.CORRUPT_PREFIX)
            },
        )
    }

    @Test
    fun `future version falls back to previous good save`() {
        val previous = fileWith(message(AiChatRole.User, "上一个好版本"))
        store().save(previous)
        // 主文件被未来版本占据（模拟旧 app 写的新格式 / 新 app 降级）
        mainFile().writeText(
            AiJson.storage.encodeToString(
                AiChatFile.serializer(),
                fileWith(message(AiChatRole.User, "未来版本")),
            ).replace("\"formatVersion\":1", "\"formatVersion\":99"),
        )

        // bak 仍是上次提交的好数据：恢复它，而非清库
        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Loaded)
        assertEquals(LoadIssue.RestoredFromBackup, outcome.issue)
        assertEquals(previous, (outcome as LoadOutcome.Loaded<AiChatFile>).file)
    }

    @Test
    fun `future version without bak resets`() {
        mainFile().writeText("""{"formatVersion":99,"messages":[]}""")

        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Fresh)
        assertEquals(LoadIssue.ResetAfterCorruption, outcome.issue)
    }
}
