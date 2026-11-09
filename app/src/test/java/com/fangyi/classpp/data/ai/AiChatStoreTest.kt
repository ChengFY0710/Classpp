package com.fangyi.classpp.data.ai

import com.fangyi.classpp.data.model.IsoDate
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
            ).replace("\"formatVersion\":$AI_CHAT_FORMAT_VERSION", "\"formatVersion\":99"),
        )

        // bak 仍是上次提交的好数据：恢复它，而非清库
        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Loaded)
        assertEquals(LoadIssue.RestoredFromBackup, outcome.issue)
        assertEquals(previous, (outcome as LoadOutcome.Loaded<AiChatFile>).file)
    }

    @Test
    fun `legacy v1 envelope with top-level messages loads`() {
        // 旧版本（v1）落盘的文件：只有顶层 messages，没有 conversations 字段——
        // 反序列化得 conversations=[]、formatVersion=1（≤ 当前版本），正常载入；
        // 包装成对话由仓库 bootstrap 的迁移负责，store 只认文件协议
        mainFile().writeText(
            """{"formatVersion":1,"messages":[{"role":"User","content":"旧消息","createdAtMillis":42}]}""",
        )

        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Loaded)
        assertEquals(LoadIssue.None, outcome.issue)
        val file = (outcome as LoadOutcome.Loaded<AiChatFile>).file
        assertTrue(file.conversations.isEmpty())
        assertEquals(listOf("旧消息"), file.messages.map { it.content })
    }

    @Test
    fun `v2 envelope roundtrips conversations and active pointer`() {
        val conversation = AiConversation(
            id = "conv-1",
            title = "帮我看看",
            createdAtMillis = 10L,
            updatedAtMillis = 30L,
            messages = listOf(message(AiChatRole.User, "帮我看看下周安排")),
        )
        val original = AiChatFile(
            conversations = listOf(conversation),
            activeConversationId = "conv-1",
        )
        assertEquals(StoreResult.Ok, store().save(original))

        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Loaded)
        assertEquals(original, (outcome as LoadOutcome.Loaded<AiChatFile>).file)
    }

    @Test
    fun `todo changes and message id roundtrip`() {
        val message = AiChatMessage(
            role = AiChatRole.Assistant,
            content = "请确认",
            createdAtMillis = 1L,
            id = "m-1",
            todoChanges = listOf(
                AiTodoChange(
                    kind = AiTodoChangeKind.Update,
                    todoId = "todo-1",
                    todoName = "实验报告",
                    patch = AiTodoPatch(name = "新名字", tags = listOf("期末")),
                ),
                AiTodoChange(
                    kind = AiTodoChangeKind.Delete,
                    todoId = "todo-2",
                    todoName = "英语作业",
                ),
            ),
            todoChangeDropped = true,
            todoCompletions = listOf(
                AiTodoCompletion(
                    todoId = "todo-3",
                    todoName = "晨跑",
                    completed = true,
                    date = IsoDate.of(2026, 11, 9),
                ),
            ),
        )
        val original = AiChatFile(
            conversations = listOf(AiConversation(id = "conv-1", messages = listOf(message))),
            activeConversationId = "conv-1",
        )
        assertEquals(StoreResult.Ok, store().save(original))

        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Loaded)
        assertEquals(original, (outcome as LoadOutcome.Loaded<AiChatFile>).file)
    }

    @Test
    fun `legacy message without new fields defaults empty`() {
        mainFile().writeText(
            """{"formatVersion":2,"conversations":[{"id":"c","messages":[""" +
                """{"role":"Assistant","content":"旧回复"}]}]}""",
        )

        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Loaded)
        val message = (outcome as LoadOutcome.Loaded<AiChatFile>)
            .file.conversations.single().messages.single()
        assertEquals("", message.id)
        assertTrue(message.todoChanges.isEmpty())
        assertFalse(message.todoChangeDropped)
        assertTrue(message.todoCompletions.isEmpty())
    }

    @Test
    fun `future version without bak resets`() {
        mainFile().writeText("""{"formatVersion":99,"messages":[]}""")

        val outcome = store().load()
        assertTrue(outcome is LoadOutcome.Fresh)
        assertEquals(LoadIssue.ResetAfterCorruption, outcome.issue)
    }
}
