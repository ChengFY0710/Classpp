package com.fangyi.classpp.data.xmu

import com.fangyi.classpp.data.sync.DailySyncScheduler
import com.fangyi.classpp.data.sync.PrefsActiveSchoolStore
import com.fangyi.classpp.schools.xmu.XmuSchoolProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 冻结常量快照（指导 §四 Phase 1 / 评审红线 §五.12）：持久化目录/文件名、prefs 键、
 * Keystore alias、todo source/id 前缀、每日同步 requestCode/触发时刻/receiver 类名
 * 逐条落断言，评审不依赖记忆。
 *
 * 私有常量（alias、prefs 名、requestCode）通过读取冻结源码文本断言：`data/xmu` 本阶段
 * 零改动，测试不改可见性，只锁值。
 */
class XmuFrozenConstantsTest {

    // ---------- 同步状态文件 ----------

    @Test
    fun `sync state storage namespace is frozen`() {
        assertEquals("xmu", XmuSyncStore.DIR_NAME)
        assertEquals("xmu-sync.json", XmuSyncStore.MAIN_NAME)
        assertEquals("xmu-sync.json.tmp", XmuSyncStore.TMP_NAME)
        assertEquals("xmu-sync.json.bak", XmuSyncStore.BAK_NAME)
        assertEquals("xmu-sync.corrupt-", XmuSyncStore.CORRUPT_PREFIX)
    }

    @Test
    fun `sync state format version is frozen`() {
        assertEquals(2, XMU_SYNC_FORMAT_VERSION)
    }

    @Test
    fun `avatar cache file name is frozen`() {
        val source = frozenSource("XmuAvatarStore.kt")
        assertTrue(source.contains("const val FILE_NAME = \"avatar.img\""))
        assertTrue(source.contains("const val TMP_NAME = \"avatar.img.tmp\""))
    }

    // ---------- 凭据存储 ----------

    @Test
    fun `keystore alias must never change`() {
        val source = frozenSource("XmuSecureStore.kt")
        assertTrue(
            "Keystore alias 改名会让旧密文永久解不开（用户被静默 BLOCKED）",
            source.contains("const val DEFAULT_ALIAS = \"classpp_xmu_credentials_v1\""),
        )
    }

    @Test
    fun `secure prefs file and keys are frozen`() {
        val source = frozenSource("XmuSecureStore.kt")
        assertTrue(source.contains("const val FILE_NAME = \"xmu_secure\""))
        assertTrue(source.contains("const val KEY_POLICY = \"auto_login_policy\""))
        assertTrue(source.contains("const val KEY_CREDENTIALS = \"credentials\""))
        assertTrue(source.contains("const val KEY_COOKIE = \"cookie_header\""))
    }

    // ---------- 每日同步开关 / 闹钟 ----------

    @Test
    fun `daily sync prefs storage is frozen`() {
        val source = frozenSource("XmuSyncPreferences.kt")
        assertTrue(source.contains("const val FILE_NAME = \"xmu_sync_prefs\""))
        assertTrue(source.contains("const val KEY_DAILY_SYNC_ENABLED = \"daily_sync_enabled\""))
    }

    @Test
    fun `daily sync alarm contract is frozen`() {
        assertEquals(6, DailySyncScheduler.TRIGGER_HOUR)
        assertEquals(
            "com.fangyi.classpp.data.xmu.XmuDailySyncAlarmReceiver",
            XmuDailySyncAlarmReceiver::class.java.name,
        )
        assertEquals(
            "com.fangyi.classpp.xmu.action.DAILY_SYNC_FIRE",
            XmuDailySyncAlarmReceiver.ACTION_FIRE,
        )
        // requestCode 与接收类由厦大薄壳注入（Phase 3 迁入 data/sync）：锁源码文本
        assertTrue(xmuSchoolsSource("XmuDailySyncController.kt").contains("REQUEST_CODE = 1001"))
        // Manifest 锚点：已发布的 PendingIntent 与系统广播继续命中
        assertTrue(
            frozenManifest().contains("android:name=\".data.xmu.XmuDailySyncAlarmReceiver\""),
        )
    }

    @Test
    fun `active school prefs storage is frozen`() {
        // school_prefs 非敏感、不排除备份；改名会让用户丢失上次选择的学校（回退首校）
        assertEquals("school_prefs", PrefsActiveSchoolStore.FILE_NAME)
        assertEquals("active_school", PrefsActiveSchoolStore.KEY_ACTIVE_SCHOOL)
    }

    // ---------- todo 标识 ----------

    @Test
    fun `todo source and id prefixes are frozen`() {
        assertEquals("xmu", XmuTodoMapper.SOURCE_XMU)
        assertEquals("xmu-", XmuTodoMapper.ID_PREFIX)
        assertEquals("xmu:homework", XmuTodoMapper.AUTO_GROUP_KEY_HOMEWORK)
        assertEquals("xmu:quiz", XmuTodoMapper.AUTO_GROUP_KEY_QUIZ)
        val dto = XmuTodoDto(id = 7, courseId = 3)
        assertEquals("xmu-3-7", XmuTodoMapper.localId(dto))
        assertEquals("xmu-7", XmuTodoMapper.legacyLocalId(dto))
        assertTrue(XmuTodoMapper.isSyncedLocalId("xmu-3-7"))
    }

    // ---------- 厦大静态声明 ----------

    @Test
    fun `xmu provider spec keeps frozen identity`() {
        val spec = XmuSchoolProvider().spec
        assertEquals("xmu", spec.id)
        assertEquals(
            setOf("NATIVE_LOGIN", "WEBVIEW_LOGIN", "SCHEDULE_IMPORT"),
            spec.capabilities.map { it.name }.toSet(),
        )
        assertEquals(2, spec.loginImages.size)
    }

    // ---------- 源码快照工具 ----------

    private fun frozenSource(name: String): String {
        val dirs = listOf(
            File("src/main/java/com/fangyi/classpp/data/xmu"),
            File("app/src/main/java/com/fangyi/classpp/data/xmu"),
        )
        val file = dirs.map { File(it, name) }.firstOrNull { it.isFile }
            ?: error("frozen source not found: $name")
        return file.readText(Charsets.UTF_8)
    }

    /** 厦大适配层源码（`schools/xmu`）：只读文本断言冻结常量，不依赖可见性 */
    private fun xmuSchoolsSource(name: String): String {
        val dirs = listOf(
            File("src/main/java/com/fangyi/classpp/schools/xmu"),
            File("app/src/main/java/com/fangyi/classpp/schools/xmu"),
        )
        val file = dirs.map { File(it, name) }.firstOrNull { it.isFile }
            ?: error("schools/xmu source not found: $name")
        return file.readText(Charsets.UTF_8)
    }

    private fun frozenManifest(): String {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("AndroidManifest.xml not found")
        return file.readText(Charsets.UTF_8)
    }
}
