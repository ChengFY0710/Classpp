package com.fangyi.classpp.schools.xmu

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

/** 作业要求 → 备注补充：按课程分组、备注格式、无要求不写、失败降级 */
class XmuNoteEnricherTest {

    private fun dto(id: Long, courseId: Long = 10L): XmuTodoDto = XmuTodoDto(
        id = id,
        title = "作业$id",
        type = "homework",
        courseId = courseId,
        courseName = "课程$courseId",
        endTime = "2026-12-01T00:00:00Z",
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
    fun `builds note with description attachments and activity url last`() = runBlocking {
        val source = FakeSource(
            mapOf(
                10L to mapOf(
                    1L to XmuActivityRequirement("如图说明", listOf("a.png", "b.pdf")),
                ),
            ),
        )

        val notes = XmuNoteEnricher(source).notesFor(listOf(dto(1)))

        assertEquals(
            "课程：课程10\n类型：homework\n作业要求：如图说明\n附件：a.png、b.pdf\n" +
                "https://lnt.xmu.edu.cn/course/10/learning-activity#/1",
            notes["xmu:10:1"],
        )
    }

    @Test
    fun `description empty keeps only attachment line`() = runBlocking {
        val source = FakeSource(
            mapOf(10L to mapOf(1L to XmuActivityRequirement("", listOf("Talk9-作业.pdf")))),
        )

        val notes = XmuNoteEnricher(source).notesFor(listOf(dto(1)))

        val note = requireNotNull(notes["xmu:10:1"])
        assertFalse(note.contains("作业要求："))
        assertTrue(note.contains("附件：Talk9-作业.pdf"))
    }

    @Test
    fun `no requirement content produces no entry`() = runBlocking {
        val source = FakeSource(
            mapOf(10L to mapOf(1L to XmuActivityRequirement(" ", emptyList()))),
        )

        assertTrue(XmuNoteEnricher(source).notesFor(listOf(dto(1))).isEmpty())
    }

    @Test
    fun `missing activity produces no entry`() = runBlocking {
        val source = FakeSource(mapOf(10L to mapOf(999L to XmuActivityRequirement("x", emptyList()))))

        assertTrue(XmuNoteEnricher(source).notesFor(listOf(dto(1))).isEmpty())
    }

    @Test
    fun `groups remotes by distinct course ids`() = runBlocking {
        val source = FakeSource(
            mapOf(
                10L to mapOf(1L to XmuActivityRequirement("A", emptyList())),
                20L to mapOf(2L to XmuActivityRequirement("B", emptyList())),
            ),
        )

        val notes = XmuNoteEnricher(source).notesFor(listOf(dto(1), dto(3), dto(2, courseId = 20L)))

        assertEquals(listOf(10L, 20L), source.requested)
        assertEquals(2, notes.size)
        assertTrue(requireNotNull(notes["xmu:10:1"]).contains("作业要求：A"))
        assertTrue(requireNotNull(notes["xmu:20:2"]).contains("作业要求：B"))
    }

    @Test
    fun `source failure degrades to empty notes`() = runBlocking {
        val source = FakeSource(failure = IOException("offline"))

        assertTrue(XmuNoteEnricher(source).notesFor(listOf(dto(1))).isEmpty())
    }

    @Test
    fun `cancellation is rethrown`() {
        val source = FakeSource(failure = CancellationException("cancelled"))

        assertThrows(CancellationException::class.java) {
            runBlocking { XmuNoteEnricher(source).notesFor(listOf(dto(1))) }
        }
    }

    @Test
    fun `description and attachment counts are capped`() = runBlocking {
        val source = FakeSource(
            mapOf(
                10L to mapOf(
                    1L to XmuActivityRequirement(
                        description = "长".repeat(1_300),
                        attachments = (1..10).map { "附件$it.pdf" },
                    ),
                ),
            ),
        )

        val note = requireNotNull(XmuNoteEnricher(source).notesFor(listOf(dto(1)))["xmu:10:1"])

        assertTrue(note.contains("…"))
        assertFalse(note.contains("长".repeat(1_300)))
        assertTrue(note.contains("附件：附件1.pdf"))
        assertTrue(note.contains("附件8.pdf"))
        assertFalse(note.contains("附件9.pdf"))
    }

    @Test
    fun `empty remote list makes no request`() = runBlocking {
        val source = FakeSource()

        assertTrue(XmuNoteEnricher(source).notesFor(emptyList()).isEmpty())
        assertEquals(0, source.calls)
    }
}
