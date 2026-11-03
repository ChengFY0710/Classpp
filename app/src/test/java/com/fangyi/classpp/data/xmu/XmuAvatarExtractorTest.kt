package com.fangyi.classpp.data.xmu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 头像地址提取（[XmuAvatarExtractor]）：实测键 `avatar_big_url` / `avatar_small_url`
 * 优先，通用候选键 / 容器下探 / 相对路径补全都要覆盖，脏数据一律退化为「没有头像」
 * 而不是抛异常。
 */
class XmuAvatarExtractorTest {

    @Test
    fun `优先取 200 像素档的 avatar_big_url`() {
        val raw = """
            {"id":9001,"user_no":"20260001","name":"张三",
             "avatar_small_url":"https://lnt.xmu.edu.cn/api/uploads/1/modified-image?thumbnail=s",
             "avatar_big_url":"https://lnt.xmu.edu.cn/api/uploads/1/modified-image?thumbnail=b"}
        """.trimIndent()
        assertEquals(
            "https://lnt.xmu.edu.cn/api/uploads/1/modified-image?thumbnail=b",
            XmuAvatarExtractor.extract(raw),
        )
    }

    @Test
    fun `没有大头像时退回 avatar_small_url`() {
        val raw = """{"avatar_small_url":"https://lnt.xmu.edu.cn/api/uploads/1/modified-image?thumbnail=s"}"""
        assertEquals(
            "https://lnt.xmu.edu.cn/api/uploads/1/modified-image?thumbnail=s",
            XmuAvatarExtractor.extract(raw),
        )
    }

    @Test
    fun `avatar 键取绝对地址`() {
        val raw = """{"id":9001,"user_no":"20260001","name":"张三","avatar":"https://cdn.xmu.edu.cn/a/1.jpg"}"""
        assertEquals("https://cdn.xmu.edu.cn/a/1.jpg", XmuAvatarExtractor.extract(raw))
    }

    @Test
    fun `avatar_url 键同样命中`() {
        val raw = """{"id":9001,"avatar_url":"https://cdn.xmu.edu.cn/a/2.png"}"""
        assertEquals("https://cdn.xmu.edu.cn/a/2.png", XmuAvatarExtractor.extract(raw))
    }

    @Test
    fun `相对路径按课程中心域名补全`() {
        val raw = """{"id":9001,"avatar":"/upload/user_avatar/9001.jpg"}"""
        assertEquals(
            "https://lnt.xmu.edu.cn/upload/user_avatar/9001.jpg",
            XmuAvatarExtractor.extract(raw),
        )
    }

    @Test
    fun `资料套一层 user 时下探取到`() {
        val raw = """{"id":9001,"user":{"id":9001,"name":"张三","avatar":"https://cdn.xmu.edu.cn/u.jpg"}}"""
        assertEquals("https://cdn.xmu.edu.cn/u.jpg", XmuAvatarExtractor.extract(raw))
    }

    @Test
    fun `顶层空串时继续试下一个候选键`() {
        val raw = """{"avatar":"   ","avatar_url":"https://cdn.xmu.edu.cn/next.jpg"}"""
        assertEquals("https://cdn.xmu.edu.cn/next.jpg", XmuAvatarExtractor.extract(raw))
    }

    @Test
    fun `没有头像字段返回 null`() {
        assertNull(XmuAvatarExtractor.extract("""{"id":9001,"user_no":"20260001","name":"张三"}"""))
    }

    @Test
    fun `脏 JSON 返回 null 不抛异常`() {
        assertNull(XmuAvatarExtractor.extract("not json"))
        assertNull(XmuAvatarExtractor.extract("<html>login</html>"))
        assertNull(XmuAvatarExtractor.extract("[]"))
    }

    @Test
    fun `非字符串与非 http 取值一律丢弃`() {
        assertNull(XmuAvatarExtractor.extract("""{"avatar":123}"""))
        assertNull(XmuAvatarExtractor.extract("""{"avatar":"data:image/png;base64,AAAA"}"""))
        assertNull(XmuAvatarExtractor.extract("""{"avatar":"upload/a.jpg"}"""))
    }
}
