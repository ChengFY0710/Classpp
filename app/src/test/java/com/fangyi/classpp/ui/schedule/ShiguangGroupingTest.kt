package com.fangyi.classpp.ui.schedule

import com.fangyi.classpp.data.shiguang.ShiguangAdapter
import com.fangyi.classpp.data.shiguang.ShiguangAdapterCategory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 拾光学校浮层的首字母分组：只验证纯逻辑——分组键归一、A→Z 排序、`#` 兜底在最后、
 * 组内保持入参顺序。上机排版由 ShiguangImportSheet 组合。
 */
class ShiguangGroupingTest {

    @Test
    fun `groups by initial in alphabetical order`() {
        val groups = groupShiguangAdapters(
            listOf(
                adapter(name = "重庆大学", initial = "C", schoolId = "CQU"),
                adapter(name = "北京大学", initial = "B", schoolId = "PKU"),
                adapter(name = "安徽大学", initial = "A", schoolId = "AHU"),
                adapter(name = "北京师范大学", initial = "B", schoolId = "BNU"),
            ),
        )

        assertEquals(listOf("A", "B", "C"), groups.map { it.letter })
        // 组内保持索引顺序，不强按名称重排
        assertEquals(
            listOf("北京大学", "北京师范大学"),
            groups[1].items.map { it.schoolName },
        )
    }

    @Test
    fun `normalizes case and whitespace and keeps only the first latin letter`() {
        val groups = groupShiguangAdapters(
            listOf(adapter(name = "重庆大学", initial = " cqu ", schoolId = "CQU")),
        )

        assertEquals(listOf("C"), groups.map { it.letter })
    }

    @Test
    fun `falls back to the school name then hash`() {
        val groups = groupShiguangAdapters(
            listOf(
                adapter(name = "Zhejiang University", initial = "", schoolId = "ZJU"),
                adapter(name = "重庆大学", initial = "", schoolId = "CQU"),
                adapter(name = "重庆师范大学", initial = "重", schoolId = "CQNU"),
                // initial 无拉丁字母时继续看学校名，而不是直接落进 #
                adapter(name = "CQU 重庆大学", initial = "重庆", schoolId = "CQU-ALT"),
            ),
        )

        assertEquals(listOf("C", "Z", "#"), groups.map { it.letter })
        // 完全没有拉丁字母的学校不丢，统一进 # 组
        assertEquals(2, groups.last().items.size)
    }

    @Test
    fun `hash group sorts after all letters`() {
        val groups = groupShiguangAdapters(
            listOf(
                adapter(name = "重庆大学", initial = "", schoolId = "CQU"),
                adapter(name = "安徽大学", initial = "A", schoolId = "AHU"),
            ),
        )

        assertEquals(listOf("A", "#"), groups.map { it.letter })
    }

    private fun adapter(
        name: String,
        initial: String,
        schoolId: String,
    ): ShiguangAdapter = ShiguangAdapter(
        schoolId = schoolId,
        schoolName = name,
        initial = initial,
        resourceFolder = schoolId,
        adapterId = schoolId,
        adapterName = "${name}教务",
        category = ShiguangAdapterCategory.BACHELOR_AND_ASSOCIATE,
        assetPath = "adapter.js",
        importUrl = "https://example.com/",
        description = "",
        maintainer = "",
    )
}
