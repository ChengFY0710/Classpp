package com.fangyi.classpp.data.shiguang

import com.fangyi.classpp.data.shiguang.ShiguangTestFixtures.adapterBytes
import com.fangyi.classpp.data.shiguang.ShiguangTestFixtures.indexBytes
import com.fangyi.classpp.data.shiguang.ShiguangTestFixtures.int32Field
import com.fangyi.classpp.data.shiguang.ShiguangTestFixtures.schoolBytes
import com.fangyi.classpp.data.shiguang.ShiguangTestFixtures.stringField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `school_index.pb` proto v2 手写解析与适配过滤 */
class ShiguangIndexPbTest {

    @Test
    fun `parses schools and adapters`() {
        val bytes = indexBytes(
            versionId = "TIME_20260101120000_123",
            schools = listOf(
                schoolBytes(
                    id = "CQU",
                    name = "重庆大学",
                    initial = "C",
                    resourceFolder = "CQU",
                    adapters = listOf(adapterBytes()),
                ),
            ),
        )

        val index = ShiguangIndexParser.parse(bytes)
        assertNotNull(index)
        index!!
        assertEquals(2, index.protocolVersion)
        assertEquals("TIME_20260101120000_123", index.versionId)
        assertEquals(1, index.schools.size)
        val school = index.schools.single()
        assertEquals("CQU", school.id)
        assertEquals("重庆大学", school.name)
        assertEquals("C", school.initial)
        assertEquals("CQU", school.resourceFolder)
        val adapter = school.adapters.single()
        assertEquals("CQU", adapter.adapterId)
        assertEquals("重庆大学教务", adapter.adapterName)
        assertEquals(ShiguangAdapterCategory.BACHELOR_AND_ASSOCIATE, adapter.category)
        assertEquals("cqu.js", adapter.assetPath)
        assertEquals("https://my.cqu.edu.cn/", adapter.importUrl)
        assertEquals("适配重庆大学智慧教务系统", adapter.description)
        assertEquals("茵符草", adapter.maintainer)
    }

    @Test
    fun `unknown fields are skipped`() {
        val unknownTopLevel = com.fangyi.classpp.data.shiguang.ShiguangTestFixtures.concat(
            int32Field(9, 42),
            stringField(10, "ignored"),
        )
        val index = ShiguangIndexParser.parse(indexBytes(extraTopLevel = unknownTopLevel))
        assertNotNull(index)
        assertEquals(1, index!!.schools.size)
    }

    @Test
    fun `optional import url may be absent`() {
        val index = ShiguangIndexParser.parse(
            indexBytes(schools = listOf(schoolBytes(adapters = listOf(adapterBytes(importUrl = null))))),
        )!!
        assertNull(index.schools.single().adapters.single().importUrl)
    }

    @Test
    fun `missing protocol version is rejected`() {
        val bytes = com.fangyi.classpp.data.shiguang.ShiguangTestFixtures.concat(
            stringField(2, "TIME_TEST"),
        )
        assertNull(ShiguangIndexParser.parse(bytes))
    }

    @Test
    fun `empty payload is rejected`() {
        assertNull(ShiguangIndexParser.parse(ByteArray(0)))
    }

    @Test
    fun `truncated length delimited field is rejected`() {
        val bytes = com.fangyi.classpp.data.shiguang.ShiguangTestFixtures.concat(
            int32Field(1, 2),
            byteArrayOf(0x12, 0x7F, 0x01), // field 2, length 127, only 1 byte follows
        )
        assertNull(ShiguangIndexParser.parse(bytes))
    }

    @Test
    fun `wrong wire type is rejected`() {
        val bytes = com.fangyi.classpp.data.shiguang.ShiguangTestFixtures.concat(
            byteArrayOf(0x0A, 0x01, 0x02), // field 1 with length-delimited wire type
        )
        assertNull(ShiguangIndexParser.parse(bytes))
    }

    @Test
    fun `non v2 index still parses for caller side gating`() {
        val index = ShiguangIndexParser.parse(indexBytes(protocolVersion = 1))
        assertEquals(1, index!!.protocolVersion)
    }

    @Test
    fun `flatten keeps only importable school adapters`() {
        val adapters = listOf(
            adapterBytes(adapterId = "A1", category = 2, assetPath = "a.js"),
            adapterBytes(adapterId = "A2", category = 1, assetPath = "tool.js"),
            adapterBytes(adapterId = "A3", category = 2, assetPath = "b.js", importUrl = null),
            adapterBytes(adapterId = "A4", category = 0, assetPath = "c.js"),
            adapterBytes(
                adapterId = "A5",
                category = 3,
                assetPath = "sub/dir.js",
                importUrl = "http://jw.example.edu.cn/",
            ),
            adapterBytes(
                adapterId = "A6",
                category = 2,
                assetPath = "d.js",
                importUrl = "ftp://jw.example.edu.cn/",
            ),
            adapterBytes(
                adapterId = "A7",
                category = 2,
                assetPath = "e.js",
                importUrl = "https://user@jw.example.edu.cn/",
            ),
            adapterBytes(adapterId = "A8", category = 2, assetPath = "../evil.js"),
            adapterBytes(adapterId = "A1", category = 2, assetPath = "a.js"),
        )
        val index = ShiguangIndexParser.parse(
            indexBytes(
                schools = listOf(
                    schoolBytes(adapters = adapters),
                    schoolBytes(
                        id = "BAD",
                        name = "坏目录",
                        resourceFolder = "../other",
                        adapters = listOf(adapterBytes()),
                    ),
                ),
            ),
        )!!

        val flattened = index.toAdapters()
        assertEquals(listOf("A1", "A5"), flattened.map { it.adapterId })
        assertEquals("CQU", flattened.first().schoolId)
        assertEquals("重庆大学", flattened.first().schoolName)
        assertEquals(ShiguangAdapterCategory.POSTGRADUATE, flattened.last().category)
    }

    @Test
    fun `path and url validation rejects traversal and non http`() {
        assertTrue(isSafeResourceFolder("CQU"))
        assertTrue(isSafeResourceFolder("zhengfang_jiaowu"))
        assertFalse(isSafeResourceFolder("../CQU"))
        assertFalse(isSafeResourceFolder("CQU/other"))
        assertFalse(isSafeResourceFolder(""))

        assertTrue(isSafeAssetPath("cqu.js"))
        assertTrue(isSafeAssetPath("sub/dir.js"))
        assertFalse(isSafeAssetPath("/cqu.js"))
        assertFalse(isSafeAssetPath("../cqu.js"))
        assertFalse(isSafeAssetPath("cqu.txt"))
        assertFalse(isSafeAssetPath("cqu js.js"))

        assertTrue(isSafeImportUrl("https://my.cqu.edu.cn/"))
        assertTrue(isSafeImportUrl("http://jw.example.edu.cn/path"))
        assertFalse(isSafeImportUrl("ftp://example.edu.cn/"))
        assertFalse(isSafeImportUrl("https://user@example.edu.cn/"))
        assertFalse(isSafeImportUrl("not a url"))
        assertFalse(isSafeImportUrl(""))
    }
}
