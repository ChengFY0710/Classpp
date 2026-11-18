package com.fangyi.classpp.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 字母分组通用规则：A→Z 排序、`#` 兜底在最后、组内保持入参顺序、首字母归一。
 * 拾光教务导入页与账号同步的学校选择浮层共用这套规则。
 */
class AlphabetGroupingTest {

    @Test
    fun `sorts groups A to Z and puts the fallback group last`() {
        val groups = groupInAlphabetOrder(
            items = listOf("C", "#", "A", "B", "#"),
            letterOf = { it },
        )

        assertEquals(listOf("A", "B", "C", "#"), groups.map { it.letter })
        assertEquals(2, groups.last().items.size)
    }

    @Test
    fun `keeps the input order inside each group`() {
        val groups = groupInAlphabetOrder(
            items = listOf("B1", "A1", "B2", "A2"),
            letterOf = { it.take(1) },
        )

        assertEquals(listOf("A1", "A2"), groups[0].items)
        assertEquals(listOf("B1", "B2"), groups[1].items)
    }

    @Test
    fun `first latin letter skips non-latin characters`() {
        assertEquals('X', "厦门XMU".firstLatinLetter())
        assertEquals('c', "cqu".firstLatinLetter())
        assertEquals('Z', " Zhejiang".firstLatinLetter())
        assertNull("重庆大学".firstLatinLetter())
    }
}
