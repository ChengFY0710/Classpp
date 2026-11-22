package com.fangyi.classpp.data.ai

import com.fangyi.classpp.data.ReadResult
import com.fangyi.classpp.data.ScheduleError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 识别回复解析：围栏块提取（取最后一个 / 容忍裸 JSON）与分享载荷导入校验的接线。
 * 课表结构/校验本身由 `ScheduleValidator` 既有用例覆盖，这里只测「AI 回复 → 可导入课表」。
 */
class AiScheduleImportCodecTest {

    @Test
    fun `extracts fenced json block surrounded by prose`() {
        val reply = """
            我看清了这张课表，结果如下。
            ```json
            $AI_SCHEDULE_IMPORT_VALID_JSON
            ```
            假设：第 18 周之后没有课。
        """.trimIndent()

        val result = AiScheduleImportCodec.parse(reply)

        val ok = result as ReadResult.Ok
        assertEquals("2026-2027 第1学期", ok.value.name)
        assertEquals(1, ok.value.courses.size)
        assertEquals("组织胚胎学", ok.value.courses.single().name)
    }

    @Test
    fun `extracts assumptions outside the fenced block`() {
        val reply = """
            已识别，共 1 门课。
            假设一：学期起点按截图第 7 周反推。
            假设二：第 18 周之后无课。
            ```json
            $AI_SCHEDULE_IMPORT_VALID_JSON
            ```
            假设三：两小节合并为一大节。
        """.trimIndent()

        val notes = AiScheduleImportCodec.extractNotes(reply)

        assertTrue(notes.contains("假设一"))
        assertTrue(notes.contains("假设三"))
        // JSON 块本身不进入假设说明
        assertTrue(!notes.contains("formatVersion"))
    }

    @Test
    fun `extracts prose before bare json`() {
        val reply = "假设：学期起点按今天推断。\n$AI_SCHEDULE_IMPORT_VALID_JSON"

        val notes = AiScheduleImportCodec.extractNotes(reply)

        assertEquals("假设：学期起点按今天推断。", notes)
    }

    @Test
    fun `takes the last fenced block`() {
        val reply = """
            ```json
            {"formatVersion": 1}
            ```
            修正后的结果：
            ```json
            $AI_SCHEDULE_IMPORT_VALID_JSON
            ```
        """.trimIndent()

        val result = AiScheduleImportCodec.parse(reply)

        assertTrue(result is ReadResult.Ok)
    }

    @Test
    fun `accepts raw json without fence`() {
        val result = AiScheduleImportCodec.parse(AI_SCHEDULE_IMPORT_VALID_JSON)

        assertTrue(result is ReadResult.Ok)
    }

    @Test
    fun `returns null when reply has no json`() {
        assertNull(AiScheduleImportCodec.parse("抱歉，这张图片太模糊，无法识别课表。"))
    }

    @Test
    fun `malformed json maps to import format error`() {
        val result = AiScheduleImportCodec.parse("```json\n{\"formatVersion\": 1,}\n```")

        val err = result as ReadResult.Err
        assertTrue(err.error is ScheduleError.ImportFormatInvalid)
    }

    @Test
    fun `unsupported format version is rejected`() {
        val result = AiScheduleImportCodec.parse(
            AI_SCHEDULE_IMPORT_VALID_JSON.replace("\"formatVersion\": 1", "\"formatVersion\": 3"),
        )

        val err = result as ReadResult.Err
        assertTrue(err.error is ScheduleError.ImportVersionUnsupported)
    }

    @Test
    fun `wrong kind is rejected`() {
        val result = AiScheduleImportCodec.parse(
            AI_SCHEDULE_IMPORT_VALID_JSON.replace(
                "\"kind\": \"classpp.schedule\"",
                "\"kind\": \"other.schedule\"",
            ),
        )

        val err = result as ReadResult.Err
        assertTrue(err.error is ScheduleError.ImportKindMismatch)
    }

    @Test
    fun `empty course list still parses so repository can report no courses`() {
        val result = AiScheduleImportCodec.parse(AI_SCHEDULE_IMPORT_EMPTY_JSON)

        val ok = result as ReadResult.Ok
        assertTrue(ok.value.courses.isEmpty())
    }

    @Test
    fun `parallel same-name sections are merged before validation`() {
        val result = AiScheduleImportCodec.parse(AI_SCHEDULE_IMPORT_PARALLEL_JSON)

        val ok = result as ReadResult.Ok
        assertEquals(1, ok.value.courses.size)
        assertEquals("数据结构(03)", ok.value.courses.single().name)
    }
}
