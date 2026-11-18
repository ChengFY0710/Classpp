package com.fangyi.classpp.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 学校选择浮层的组标题字母：显示名优先（英文名直接给字母），中文名退回学校 id
 * 首字母（id 按拼音缩写约定），都没有归入 `#`。上机排版由 SchoolPickerSheet 组合。
 */
class SchoolPickerGroupingTest {

    @Test
    fun `latin display name decides the letter`() {
        assertEquals("X", schoolGroupLetter("Xiamen University", "xmu"))
        assertEquals("F", schoolGroupLetter("Fuzhou University", "fzu"))
    }

    @Test
    fun `chinese display name falls back to the school id`() {
        assertEquals("X", schoolGroupLetter("厦门大学", "xmu"))
        assertEquals("F", schoolGroupLetter("福州大学", "fzu"))
    }

    @Test
    fun `letter is uppercased`() {
        assertEquals("X", schoolGroupLetter("xiamen university", "xmu"))
    }

    @Test
    fun `no latin anywhere falls into the hash group`() {
        assertEquals("#", schoolGroupLetter("厦门大学", "厦门"))
    }
}
