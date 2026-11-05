package com.fangyi.classpp.data.backup

import com.fangyi.classpp.data.ScheduleJson
import com.fangyi.classpp.data.ai.AiChatFile
import com.fangyi.classpp.data.ai.AiChatMessage
import com.fangyi.classpp.data.ai.AiChatRole
import com.fangyi.classpp.data.model.ScheduleFile
import com.fangyi.classpp.data.model.TagEntry
import com.fangyi.classpp.data.model.TagFile
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

    private fun payload(imageNames: List<String> = emptyList()): BackupArchive.Payload {
        val imageDir = folder.newFolder("images-src")
        imageNames.forEach { name ->
            File(imageDir, name).writeBytes(byteArrayOf(0x11, 0x22, 0x33))
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
                ),
            ),
            schedulesJson = ScheduleJson.encodeStorage(
                ScheduleFile(schedules = listOf(testSchedule())),
            ),
            todosJson = ScheduleJson.encodeStorage(
                TodoFile(todos = listOf(testTodo(id = "t1", name = "高数作业"))),
            ),
            tagsJson = ScheduleJson.encodeStorage(
                TagFile(tags = listOf(TagEntry(name = "数学"))),
            ),
            aiChatJson = ScheduleJson.encodeStorage(
                AiChatFile(
                    messages = listOf(
                        AiChatMessage(
                            role = AiChatRole.User,
                            content = "你好",
                            createdAtMillis = 1L,
                        ),
                    ),
                ),
            ),
            dayImagesJson = ScheduleJson.encodeStorage(
                BackupDayImages(images = imageNames, selected = imageNames.firstOrNull()),
            ),
            imageDir = imageDir,
            imageNames = imageNames,
        )
    }

    private fun writeToBytes(payload: BackupArchive.Payload): ByteArray =
        ByteArrayOutputStream().also { BackupArchive.write(it, payload) }.toByteArray()

    @Test
    fun `write then extract roundtrips all entries`() {
        val names = listOf("a.jpg", "b.jpg")
        val bytes = writeToBytes(payload(names))
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

        val tags = ScheduleJson.decodeStorage<TagFile>(
            File(target, BackupArchive.ENTRY_TAGS).readText(),
        )
        assertEquals("数学", tags.tags.single().name)

        val ai = ScheduleJson.decodeStorage<AiChatFile>(
            File(target, BackupArchive.ENTRY_AI_CHAT).readText(),
        )
        assertEquals("你好", ai.messages.single().content)

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
    }

    @Test
    fun `readManifest returns manifest without extracting images`() {
        val bytes = writeToBytes(payload(listOf("a.jpg")))
        val manifest = BackupArchive.readManifest(ByteArrayInputStream(bytes))
        assertEquals(BACKUP_KIND, manifest?.kind)
        assertEquals(1, manifest?.counts?.dayImages)
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
    fun `unsupported manifest version is not supported`() {
        val manifest = BackupManifest(formatVersion = BACKUP_FORMAT_VERSION + 1)
        assertTrue(!manifest.isSupported())
        assertTrue(!BackupManifest(kind = "other").isSupported())
    }
}
