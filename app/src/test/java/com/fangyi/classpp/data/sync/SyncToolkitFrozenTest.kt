package com.fangyi.classpp.data.sync

import com.fangyi.classpp.data.sync.credential.KeystoreCipher
import com.fangyi.classpp.data.sync.credential.KeystoreEnvelope
import com.fangyi.classpp.data.sync.credential.SchoolSecureStore
import com.fangyi.classpp.data.sync.engine.SchoolSyncStore
import com.fangyi.classpp.data.xmu.XmuKeystoreEnvelope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新校适配工具包的持久化常量快照（指导 §2.6 / §五.12）：
 * 目录/文件名、prefs 文件与按校分键、Keystore alias 前缀逐条落断言。
 *
 * 另外做一次**信封交叉解包**：通用 [KeystoreEnvelope] 与冻结实现 `XmuKeystoreEnvelope`
 * 必须逐字节兼容——未来若把新校凭据迁移到通用存储（或反向），密文不能失效。
 */
class SyncToolkitFrozenTest {

    // ---------- 同步状态 ----------

    @Test
    fun `generic sync state storage is frozen`() {
        assertEquals("sync", SchoolSyncStore.ROOT_DIR_NAME)
        assertEquals("sync.json", SchoolSyncStore.MAIN_NAME)
        assertEquals("sync.json.tmp", SchoolSyncStore.TMP_NAME)
        assertEquals("sync.json.bak", SchoolSyncStore.BAK_NAME)
        assertEquals("sync.corrupt-", SchoolSyncStore.CORRUPT_PREFIX)
        assertEquals(1, com.fangyi.classpp.data.sync.engine.SCHOOL_SYNC_FORMAT_VERSION)
    }

    // ---------- 凭据 ----------

    @Test
    fun `sync secure prefs file and per school keys are frozen`() {
        assertEquals("sync_secure", SchoolSecureStore.FILE_NAME)
        assertEquals("fzu/policy", SchoolSecureStore.policyKey("fzu"))
        assertEquals("fzu/credentials", SchoolSecureStore.credentialsKey("fzu"))
        assertEquals("fzu/cookie", SchoolSecureStore.cookieKey("fzu"))
    }

    @Test
    fun `keystore alias derives from school id and must never change`() {
        assertEquals("classpp_sync_fzu_v1", KeystoreCipher.aliasFor("fzu"))
        assertEquals("classpp_sync_", KeystoreCipher.ALIAS_PREFIX)
        assertThrows(IllegalArgumentException::class.java) {
            KeystoreCipher.aliasFor("FZU/../x")
        }
    }

    @Test
    fun `keystore envelope round trips`() {
        val iv = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        val ciphertext = "cipher".toByteArray(Charsets.UTF_8)
        val parts = KeystoreEnvelope.unpack(KeystoreEnvelope.pack(iv, ciphertext))
        assertNotNull(parts)
        assertArrayEquals(iv, parts!!.iv)
        assertArrayEquals(ciphertext, parts.ciphertext)
    }

    @Test
    fun `generic and frozen keystore envelopes are interchangeable`() {
        val iv = ByteArray(12) { it.toByte() }
        val ciphertext = "payload".toByteArray(Charsets.UTF_8)

        // 冻结实现打包 → 通用解包
        val fromFrozen = KeystoreEnvelope.unpack(XmuKeystoreEnvelope.pack(iv, ciphertext))
        assertNotNull(fromFrozen)
        assertArrayEquals(iv, fromFrozen!!.iv)
        assertArrayEquals(ciphertext, fromFrozen.ciphertext)

        // 通用打包 → 冻结解包
        val fromGeneric = XmuKeystoreEnvelope.unpack(KeystoreEnvelope.pack(iv, ciphertext))
        assertNotNull(fromGeneric)
        assertArrayEquals(iv, fromGeneric!!.iv)
        assertArrayEquals(ciphertext, fromGeneric.ciphertext)
    }

    // ---------- 每日同步开关 ----------

    @Test
    fun `sync prefs file and per school key are frozen`() {
        assertEquals("sync_prefs", PrefsDailySyncToggle.FILE_NAME)
        assertEquals("fzu/daily_sync_enabled", PrefsDailySyncToggle.enabledKey("fzu"))
    }

    // ---------- 备份规则 ----------

    @Test
    fun `backup rules exclude generic sync storage in all sections`() {
        val dirs = listOf("src/main/res/xml", "app/src/main/res/xml")
        fun read(name: String): String {
            val file = dirs.map { java.io.File(it, name) }.firstOrNull { it.isFile }
                ?: error("resource not found: $name")
            return file.readText(Charsets.UTF_8)
        }

        val backup = read("backup_rules.xml")
        assertTrue(backup.contains("""<exclude domain="file" path="sync" />"""))
        assertTrue(backup.contains("""<exclude domain="sharedpref" path="sync_secure.xml" />"""))
        assertTrue(backup.contains("""<exclude domain="sharedpref" path="sync_prefs.xml" />"""))

        val extraction = read("data_extraction_rules.xml")
        assertEquals(2, Regex("""<exclude domain="file" path="sync" />""").findAll(extraction).count())
        assertEquals(
            2,
            Regex("""<exclude domain="sharedpref" path="sync_secure.xml" />""")
                .findAll(extraction).count(),
        )
        assertEquals(
            2,
            Regex("""<exclude domain="sharedpref" path="sync_prefs.xml" />""")
                .findAll(extraction).count(),
        )
        // school_prefs 非敏感、不排除（恢复后指向无数据的学校 = 未连接，无害）
        assertFalse(backup.contains("""path="school_prefs.xml""""))
    }
}
