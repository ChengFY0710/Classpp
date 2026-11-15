package com.fangyi.classpp.data

import com.fangyi.classpp.data.store.FileTodoStore
import com.fangyi.classpp.data.store.TodoAttachmentStore
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TodoAttachmentTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val now = FakeNow()
    private val attachmentRoot by lazy { folder.newFolder("todo_attachments") }
    private val attachmentStore by lazy { TodoAttachmentStore(attachmentRoot) }

    private suspend fun repo() = TodoRepository.create(
        store = FileTodoStore(folder.root),
        nowMillis = now,
        attachmentStore = attachmentStore,
    )

    private fun source(name: String, mime: String, text: String) = TodoAttachmentSource(name, mime) {
        ByteArrayInputStream(text.toByteArray())
    }

    private fun TodoReadResult<String>.okId(): String {
        assertTrue("expected Ok, got ${(this as? TodoReadResult.Err)?.error?.message}", this is TodoReadResult.Ok)
        return (this as TodoReadResult.Ok).value
    }

    private fun TodoOpResult.assertOk(): TodoOpResult {
        assertTrue("expected Ok, got ${(this as? TodoOpResult.Err)?.error?.message}", this is TodoOpResult.Ok)
        return this
    }

    @Test
    fun `add attachments copies files and survives reopen`() = runBlocking {
        val repository = repo()
        val todoId = repository.addTodo(testTodo(name = "交实验报告")).okId()

        repository.addAttachments(
            todoId,
            listOf(
                source("报告.pdf", "application/pdf", "pdf"),
                source("截图.png", "image/png", "png"),
            ),
        ).assertOk()

        val todo = repository.todos.value.single()
        assertEquals(listOf("报告.pdf", "截图.png"), todo.attachments.map { it.name })
        assertEquals(listOf(3L, 3L), todo.attachments.map { it.sizeBytes })
        assertEquals("pdf", repository.attachmentFile(todoId, todo.attachments.first().id)!!.readText())

        val reopened = repo()
        val persisted = reopened.todos.value.single()
        assertEquals(2, persisted.attachments.size)
        assertEquals(
            "png",
            reopened.attachmentFile(todoId, persisted.attachments.last().id)!!.readText(),
        )
    }

    @Test
    fun `remove attachment deletes metadata and file`() = runBlocking {
        val repository = repo()
        val todoId = repository.addTodo(testTodo()).okId()
        repository.addAttachments(todoId, listOf(source("a.txt", "text/plain", "a"))).assertOk()

        val attachment = repository.todos.value.single().attachments.single()
        val file = repository.attachmentFile(todoId, attachment.id)!!
        repository.removeAttachment(todoId, attachment.id).assertOk()

        assertTrue(repository.todos.value.single().attachments.isEmpty())
        assertFalse(file.parentFile!!.exists())
    }

    @Test
    fun `soft delete keeps file and restore still opens it`() = runBlocking {
        val repository = repo()
        val todoId = repository.addTodo(testTodo()).okId()
        repository.addAttachments(todoId, listOf(source("a.txt", "text/plain", "a"))).assertOk()
        val attachment = repository.todos.value.single().attachments.single()

        repository.removeTodo(todoId).assertOk()
        assertTrue(repository.attachmentFile(todoId, attachment.id)!!.isFile)

        repository.restoreTodo(todoId).assertOk()
        assertTrue(repository.attachmentFile(todoId, attachment.id)!!.isFile)
    }

    @Test
    fun `purge todo deletes attachment files`() = runBlocking {
        val repository = repo()
        val todoId = repository.addTodo(testTodo()).okId()
        repository.addAttachments(todoId, listOf(source("a.txt", "text/plain", "a"))).assertOk()
        val attachment = repository.todos.value.single().attachments.single()
        val file = repository.attachmentFile(todoId, attachment.id)!!

        repository.removeTodo(todoId).assertOk()
        repository.purgeTodo(todoId).assertOk()

        assertFalse(file.parentFile!!.exists())
        assertTrue(attachmentRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `failed import leaves no metadata or partial files`() = runBlocking {
        val repository = repo()
        val todoId = repository.addTodo(testTodo()).okId()
        val failing = TodoAttachmentSource("bad.txt", "text/plain") { null }

        val result = repository.addAttachments(
            todoId,
            listOf(source("ok.txt", "text/plain", "ok"), failing),
        )

        assertTrue(result is TodoOpResult.Err)
        assertTrue(repository.todos.value.single().attachments.isEmpty())
        assertTrue(attachmentRoot.listFiles().orEmpty().isEmpty())
    }
}
