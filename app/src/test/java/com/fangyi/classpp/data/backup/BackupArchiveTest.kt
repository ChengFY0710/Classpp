package com.fangyi.classpp.data.backup

import com.fangyi.classpp.data.ScheduleJson
import com.fangyi.classpp.data.ai.AiChatFile
import com.fangyi.classpp.data.ai.AiChatMessage
import com.fangyi.classpp.data.ai.AiChatRole
import com.fangyi.classpp.data.ai.AiConversation
import com.fangyi.classpp.data.model.ScheduleFile
import com.fangyi.classpp.data.model.TagEntry
import com.fangyi.classpp.data.model.TagFile
import com.fangyi.classpp.data.model.TodoAttachment
import com.fangyi.classpp.data.model.TodoFile
import com.fangyi.classpp.data.testSchedule
import com.fangyi.classpp.data.testTodo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 备份包读写：结构往返、manifest 预检、entry 白名单与路径穿越防护 */
class BackupArchiveTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun payload(
        imageNames: List<String> = emptyList(),
        attachments: List<BackupArchive.AttachmentRef> = emptyList(),
    ): BackupArchive.Payload {
        val imageDir = folder.newFolder("images-src")
        imageNames.forEach { name ->
            File(imageDir, name).writeBytes(byteArrayOf(0x11, 0x22, 0x33))
        }
        val attachmentDir = folder.newFolder("attachments-src")
        attachments.forEach { attachment ->
            val file = File(attachmentDir, attachment.relativePath)
            file.parentFile?.mkdirs()
            file.writeBytes(byteArrayOf(0x44, 0x55))
        }
        return BackupArchive.Payload(
            manifest = BackupManifest(
                appVersionName = "0.1.5-alpha",
                exportedAtMillis = 123L,
                counts = BackupCounts(
                    schedules = 1,
                    courses = 0,
                    todos = 1,
                    tags = 1,
                    aiMessages = 1,
                    dayImages = imageNames.size,
                    attachments = attachments.size,
                ),
            ),
            schedulesJson = ScheduleJson.encodeStorage(
                ScheduleFile(schedules = listOf(testSchedule())),
            ),
            todosJson = ScheduleJson.encodeStorage(
                TodoFile(
                    todos = listOf(
                        testTodo(
                            id = "t1",
                            name = "高数作业",
                            attachments = attachments.map {
                                TodoAttachment(
                                    id = it.id,
                                    name = it.fileName,
                                    fileName = it.fileName,
                                    mimeType = "application/octet-stream",
                                    sizeBytes = 2,
                                )
                            },
                        ),
                    ),
                ),
            ),
            tagsJson = ScheduleJson.encodeStorage(
                TagFile(tags = listOf(TagEntry(name = "数学"))),
            ),
            aiChatJson = ScheduleJson.encodeStorage(
                AiChatFile(
                    conversations = listOf(
                        AiConversation(
                            id = "conv-1",
                            title = "你好",
                            createdAtMillis = 1L,
                            updatedAtMillis = 1L,
                            messages = listOf(
                                AiChatMessage(
                                    role = AiChatRole.User,
                                    content = "你好",
                                    createdAtMillis = 1L,
                                ),
                            ),
                        ),
                    ),
                    activeConversationId = "conv-1",
                ),
            ),
            dayImagesJson = ScheduleJson.encodeStorage(
                BackupDayImages(images = imageNames, selected = imageNames.firstOrNull()),
            ),
            imageDir = imageDir,
            imageNames = imageNames,
            attachmentDir = attachmentDir,
            attachments = attachments,
        )
    }

    private fun writeToBytes(payload: BackupArchive.Payload): ByteArray =
        ByteArrayOutputStream().also { BackupArchive.write(it, payload) }.toByteArray()

    @Test
    fun `write then extract roundtrips all entries`() {
        val names = listOf("a.jpg", "b.jpg")
        val attachments = listOf(
            BackupArchive.AttachmentRef(id = "att-1", fileName = "报告.pdf"),
            BackupArchive.AttachmentRef(id = "att-2", fileName = "截图 1.png"),
        )
        val bytes = writeToBytes(payload(names, attachments))
        val target = folder.newFolder("extracted")

        BackupArchive.extract(ByteArrayInputStream(bytes), target)

        val schedules = ScheduleJson.decodeStorage<ScheduleFile>(
            File(target, BackupArchive.ENTRY_SCHEDULES).readText(),
        )
        assertEquals("s1", schedules.schedules.single().id)

        val todos = ScheduleJson.decodeStorage<TodoFile>(
            File(target, BackupArchive.ENTRY_TODOS).readText(),
        )
        assertEquals("高数作业", todos.todos.single().name)
        assertEquals(attachments.map { it.id }, todos.todos.single().attachments.map { it.id })

        val tags = ScheduleJson.decodeStorage<TagFile>(
            File(target, BackupArchive.ENTRY_TAGS).readText(),
        )
        assertEquals("数学", tags.tags.single().name)

        val ai = ScheduleJson.decodeStorage<AiChatFile>(
            File(target, BackupArchive.ENTRY_AI_CHAT).readText(),
        )
        assertEquals("conv-1", ai.activeConversationId)
        assertEquals("你好", ai.conversations.single().messages.single().content)

        val dayImages = ScheduleJson.decodeStorage<BackupDayImages>(
            File(target, BackupArchive.ENTRY_DAY_IMAGES).readText(),
        )
        assertEquals(names, dayImages.images)

        names.forEach { name ->
            assertArrayEquals(
                byteArrayOf(0x11, 0x22, 0x33),
                File(target, BackupArchive.IMAGE_ENTRY_PREFIX + name).readBytes(),
            )
        }
        attachments.forEach { attachment ->
            assertArrayEquals(
                byteArrayOf(0x44, 0x55),
                File(target, BackupArchive.ATTACHMENT_ENTRY_PREFIX + attachment.relativePath).readBytes(),
            )
        }
    }

    @Test
    fun `readManifest returns manifest without extracting images`() {
        val attachments = listOf(BackupArchive.AttachmentRef(id = "att-1", fileName = "报告.pdf"))
        val bytes = writeToBytes(payload(listOf("a.jpg"), attachments))
        val manifest = BackupArchive.readManifest(ByteArrayInputStream(bytes))
        assertEquals(BACKUP_KIND, manifest?.kind)
        assertEquals(1, manifest?.counts?.dayImages)
        assertEquals(1, manifest?.counts?.attachments)
        assertTrue(manifest!!.isSupported())
    }

    @Test
    fun `readManifest returns null when archive has no manifest`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(BackupArchive.ENTRY_TAGS))
            zip.write("{}".toByteArray())
            zip.closeEntry()
        }
        assertNull(BackupArchive.readManifest(ByteArrayInputStream(out.toByteArray())))
    }

    @Test
    fun `extract rejects path traversal entries`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("../evil.txt"))
            zip.write("bad".toByteArray())
            zip.closeEntry()
        }
        val target = folder.newFolder("target")
        try {
            BackupArchive.extract(ByteArrayInputStream(out.toByteArray()), target)
            fail("path traversal entry must be rejected")
        } catch (_: BackupArchiveException) {
            // expected
        }
        assertTrue(!File(folder.root, "evil.txt").exists())
    }

    @Test
    fun `extract rejects unknown data entries`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("data/unknown.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
        }
        try {
            BackupArchive.extract(
                ByteArrayInputStream(out.toByteArray()),
                folder.newFolder("target2"),
            )
            fail("unknown entry must be rejected")
        } catch (_: BackupArchiveException) {
            // expected
        }
    }

    @Test
    fun `image name guard rejects separators and dot segments`() {
        assertTrue(BackupArchive.isSafeImageName("a.jpg"))
        assertTrue(!BackupArchive.isSafeImageName(""))
        assertTrue(!BackupArchive.isSafeImageName(".."))
        assertTrue(!BackupArchive.isSafeImageName("dir/a.jpg"))
        assertTrue(!BackupArchive.isSafeImageName("dir\\a.jpg"))
    }

    @Test
    fun `attachment entry guard requires exactly two safe segments`() {
        assertTrue(BackupArchive.isSafeAttachmentEntry("att-1/report.pdf"))
        assertTrue(BackupArchive.isSafeAttachmentEntry("att_2/截图 1.png"))
        assertFalse(BackupArchive.isSafeAttachmentEntry(""))
        assertFalse(BackupArchive.isSafeAttachmentEntry("../report.pdf"))
        assertFalse(BackupArchive.isSafeAttachmentEntry("att-1/../report.pdf"))
        assertFalse(BackupArchive.isSafeAttachmentEntry("att-1/sub/report.pdf"))
        assertFalse(BackupArchive.isSafeAttachmentEntry("att-1\\report.pdf"))
    }

    @Test
    fun `extract rejects unsafe attachment entries`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(BackupArchive.ATTACHMENT_ENTRY_PREFIX + "att-1/../../evil.txt"))
            zip.write("bad".toByteArray())
            zip.closeEntry()
        }
        try {
            BackupArchive.extract(ByteArrayInputStream(out.toByteArray()), folder.newFolder("target3"))
            fail("traversal attachment entry must be rejected")
        } catch (_: BackupArchiveException) {
            // expected
        }
        assertFalse(File(folder.root, "evil.txt").exists())
    }

    @Test
    fun `unsupported manifest version is not supported`() {
        val manifest = BackupManifest(formatVersion = BACKUP_FORMAT_VERSION + 1)
        assertTrue(!manifest.isSupported())
        assertTrue(!BackupManifest(kind = "other").isSupported())
    }

    @Test
    fun `backup copy strips ai chat image references`() {
        val original = AiChatFile(
            conversations = listOf(
                AiConversation(
                    id = "c1",
                    messages = listOf(
                        AiChatMessage(
                            role = AiChatRole.User,
                            content = "看图",
                            images = listOf("a.jpg", "b.jpg"),
                            id = "m1",
                        ),
                        AiChatMessage(role = AiChatRole.Assistant, content = "好", id = "m2"),
                    ),
                ),
            ),
            // v1 顶层消息同样清理（迁移前旧库兜底）
            messages = listOf(
                AiChatMessage(
                    role = AiChatRole.User,
                    content = "旧消息",
                    images = listOf("old.jpg"),
                    id = "m3",
                ),
            ),
        )

        val stripped = original.withoutAiImages()

        assertTrue(stripped.conversations.single().messages.all { it.images.isEmpty() })
        assertTrue(stripped.messages.all { it.images.isEmpty() })
        assertEquals("看图", stripped.conversations.single().messages.first().content)
        // 原对象不被修改
        assertEquals(
            listOf("a.jpg", "b.jpg"),
            original.conversations.single().messages.first().images,
        )
    }
}
