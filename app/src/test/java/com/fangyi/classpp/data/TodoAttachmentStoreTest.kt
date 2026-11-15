package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.TodoAttachment
import com.fangyi.classpp.data.store.TodoAttachmentStore
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TodoAttachmentStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `safe file name strips paths illegal chars and keeps extension`() {
        assertEquals("report.pdf", TodoAttachmentStore.safeFileName("dir/sub/report.pdf"))
        assertEquals("a_b_.txt", TodoAttachmentStore.safeFileName("a:b?.txt"))
        assertEquals("attachment", TodoAttachmentStore.safeFileName("   "))
        assertEquals("attachment", TodoAttachmentStore.safeFileName(".."))

        val longName = TodoAttachmentStore.safeFileName("a".repeat(200) + ".pdf")
        assertEquals(120, longName.length)
        assertTrue(longName.endsWith(".pdf"))
    }

    @Test
    fun `file name and id safety checks`() {
        assertTrue(TodoAttachmentStore.isSafeFileName("实验报告.pdf"))
        assertFalse(TodoAttachmentStore.isSafeFileName("a/b.txt"))
        assertFalse(TodoAttachmentStore.isSafeFileName(".."))
        assertFalse(TodoAttachmentStore.isSafeFileName("a\u0000b"))

        assertTrue(TodoAttachmentStore.isSafeId("9f1c2a3b-4d5e-6f70-8a9b-0c1d2e3f4a5b"))
        assertFalse(TodoAttachmentStore.isSafeId("../escape"))
        assertFalse(TodoAttachmentStore.isSafeId("a/b"))
    }

    @Test
    fun `write read and delete attachment file`() {
        val root = folder.newFolder("todo_attachments")
        val store = TodoAttachmentStore(root)
        val fileName = TodoAttachmentStore.safeFileName("实验报告.pdf")
        val size = store.write("a1", fileName, ByteArrayInputStream("hello".toByteArray()))

        assertEquals(5L, size)
        val attachment = TodoAttachment(
            id = "a1",
            name = "实验报告.pdf",
            fileName = fileName,
            mimeType = "application/pdf",
            sizeBytes = 5,
        )
        val file = store.file(attachment)!!
        assertTrue(file.isFile)
        assertEquals("hello", file.readText())
        assertFalse("成功后不残留 .part 临时文件", File(root, "a1/$fileName.part").exists())

        store.delete(attachment)
        assertFalse(File(root, "a1").exists())
    }

    @Test
    fun `cleanup removes only unreferenced old directories`() {
        val root = folder.newFolder("todo_attachments")
        val store = TodoAttachmentStore(root)
        store.write("keep", "keep.txt", ByteArrayInputStream("k".toByteArray()))
        store.write("drop", "drop.txt", ByteArrayInputStream("d".toByteArray()))

        store.cleanupOrphans(referencedIds = setOf("keep"), minAgeMillis = 0)

        assertTrue(File(root, "keep/keep.txt").isFile)
        assertFalse(File(root, "drop").exists())
    }
}
