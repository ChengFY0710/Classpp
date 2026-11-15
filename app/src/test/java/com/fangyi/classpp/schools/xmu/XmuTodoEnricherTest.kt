package com.fangyi.classpp.schools.xmu

import com.fangyi.classpp.data.xmu.XmuActivityAttachment
import com.fangyi.classpp.data.xmu.XmuActivityRequirement
import com.fangyi.classpp.data.xmu.XmuActivityRequirementSource
import com.fangyi.classpp.data.xmu.XmuTodoDto
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 作业要求/附件 → 待办补充内容：按课程分组、备注与附件元数据、无内容不写、失败降级 */
class XmuTodoEnricherTest {

    private fun dto(id: Long, courseId: Long = 10L): XmuTodoDto = XmuTodoDto(
        id = id,
        title = "作业$id",
        type = "homework",
        courseId = courseId,
        courseName = "课程$courseId",
        endTime = "2026-12-01T00:00:00Z",
    )

    private fun attachment(
        id: Long = 17141442,
        referenceId: Long = 10898379,
        name: String = "Talk9-作业.pdf",
        type: String = "document",
        sizeBytes: Long = 1_320_718,
    ) = XmuActivityAttachment(
        id = id,
        referenceId = referenceId,
        name = name,
        type = type,
        sizeBytes = sizeBytes,
    )

    private class FakeSource(
        private val data: Map<Long, Map<Long, XmuActivityRequirement>> = emptyMap(),
        private val failure: Exception? = null,
    ) : XmuActivityRequirementSource {
        var calls = 0
            private set
        var requested: List<Long> = emptyList()
            private set

        override suspend fun fetch(
            courseIds: Collection<Long>,
        ): Map<Long, Map<Long, XmuActivityRequirement>> {
            calls++
            requested = courseIds.toList()
            failure?.let { throw it }
            return data.filterKeys { it in courseIds }
        }
    }

    @Test
    fun `builds note and attachment metadata for a fetched activity`() = runBlocking {
        val source = FakeSource(
            mapOf(
                10L to mapOf(
                    1L to XmuActivityRequirement(
                        description = "如图说明",
                        attachments = listOf(attachment()),
                    ),
                ),
            ),
        )

        val extras = XmuTodoEnricher(source).extrasFor(listOf(dto(1)))

        val extra = requireNotNull(extras["xmu:10:1"])
        assertEquals(
            "课程：课程10\n类型：homework\n作业要求：如图说明\n" +
                "https://lnt.xmu.edu.cn/course/10/learning-activity#/1",
            extra.note,
        )
        assertFalse(requireNotNull(extra.note).contains("附件："))
        val todoAttachment = requireNotNull(extra.remoteAttachments).single()
        assertEquals("xmu-10-1-10898379", todoAttachment.id)
        assertEquals("Talk9-作业.pdf", todoAttachment.name)
        assertEquals("Talk9-作业.pdf", todoAttachment.fileName)
        assertEquals("application/pdf", todoAttachment.mimeType)
        assertEquals(1_320_718L, todoAttachment.sizeBytes)
        assertEquals(
            "https://lnt.xmu.edu.cn/api/uploads/reference/10898379/blob",
            todoAttachment.remoteUrl,
        )
    }

    @Test
    fun `description empty keeps base note and still syncs attachments`() = runBlocking {
        val source = FakeSource(
            mapOf(10L to mapOf(1L to XmuActivityRequirement("", listOf(attachment())))),
        )

        val extra = requireNotNull(XmuTodoEnricher(source).extrasFor(listOf(dto(1)))["xmu:10:1"])

        assertEquals(
            "课程：课程10\n类型：homework\n" +
                "https://lnt.xmu.edu.cn/course/10/learning-activity#/1",
            extra.note,
        )
        assertEquals(1, requireNotNull(extra.remoteAttachments).size)
    }

    @Test
    fun `activity without requirement still returns base note and empty attachment list`() = runBlocking {
        val source = FakeSource(
            mapOf(10L to mapOf(1L to XmuActivityRequirement(" ", emptyList()))),
        )

        val extra = requireNotNull(XmuTodoEnricher(source).extrasFor(listOf(dto(1)))["xmu:10:1"])

        assertEquals(
            "课程：课程10\n类型：homework\n" +
                "https://lnt.xmu.edu.cn/course/10/learning-activity#/1",
            extra.note,
        )
        assertEquals(emptyList<Any>(), extra.remoteAttachments)
    }

    @Test
    fun `missing activity produces no entry`() = runBlocking {
        val source = FakeSource(
            mapOf(10L to mapOf(999L to XmuActivityRequirement("x", emptyList()))),
        )

        assertTrue(XmuTodoEnricher(source).extrasFor(listOf(dto(1))).isEmpty())
    }

    @Test
    fun `groups remotes by distinct course ids`() = runBlocking {
        val source = FakeSource(
            mapOf(
                10L to mapOf(1L to XmuActivityRequirement("A", emptyList())),
                20L to mapOf(2L to XmuActivityRequirement("B", emptyList())),
            ),
        )

        val extras = XmuTodoEnricher(source).extrasFor(
            listOf(dto(1), dto(3), dto(2, courseId = 20L)),
        )

        assertEquals(listOf(10L, 20L), source.requested)
        assertEquals(2, extras.size)
        assertTrue(requireNotNull(extras["xmu:10:1"]?.note).contains("作业要求：A"))
        assertTrue(requireNotNull(extras["xmu:20:2"]?.note).contains("作业要求：B"))
    }

    @Test
    fun `source failure degrades to empty extras`() = runBlocking {
        val source = FakeSource(failure = IOException("offline"))

        assertTrue(XmuTodoEnricher(source).extrasFor(listOf(dto(1))).isEmpty())
    }

    @Test
    fun `cancellation is rethrown`() {
        val source = FakeSource(failure = CancellationException("cancelled"))

        assertThrows(CancellationException::class.java) {
            runBlocking { XmuTodoEnricher(source).extrasFor(listOf(dto(1))) }
        }
    }

    @Test
    fun `description is capped`() = runBlocking {
        val source = FakeSource(
            mapOf(
                10L to mapOf(
                    1L to XmuActivityRequirement("长".repeat(1_300), emptyList()),
                ),
            ),
        )

        val note = requireNotNull(
            XmuTodoEnricher(source).extrasFor(listOf(dto(1)))["xmu:10:1"]?.note,
        )

        assertTrue(note.contains("…"))
        assertFalse(note.contains("长".repeat(1_300)))
    }

    @Test
    fun `attachment falls back to upload id when reference id is missing`() = runBlocking {
        val source = FakeSource(
            mapOf(
                10L to mapOf(
                    1L to XmuActivityRequirement(
                        "",
                        listOf(attachment(id = 77, referenceId = 0, name = "photo.jpeg")),
                    ),
                ),
            ),
        )

        val extra = requireNotNull(XmuTodoEnricher(source).extrasFor(listOf(dto(1)))["xmu:10:1"])
        val todoAttachment = requireNotNull(extra.remoteAttachments).single()
        assertEquals("xmu-10-1-77", todoAttachment.id)
        assertEquals("image/jpeg", todoAttachment.mimeType)
        assertEquals(
            "https://lnt.xmu.edu.cn/api/uploads/77/blob",
            todoAttachment.remoteUrl,
        )
    }

    @Test
    fun `empty remote list makes no request`() = runBlocking {
        val source = FakeSource()

        assertTrue(XmuTodoEnricher(source).extrasFor(emptyList()).isEmpty())
        assertEquals(0, source.calls)
    }
}
