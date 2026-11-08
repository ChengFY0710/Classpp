package com.fangyi.classpp.schools.xmu

import com.fangyi.classpp.data.sync.AutoRenewPolicy
import com.fangyi.classpp.data.sync.credential.SecureStore
import com.fangyi.classpp.data.sync.credential.StoredCredentials
import com.fangyi.classpp.data.sync.engine.SchoolSyncEntry
import com.fangyi.classpp.data.sync.engine.SchoolSyncFile
import com.fangyi.classpp.data.sync.engine.SchoolSyncStore
import com.fangyi.classpp.data.store.LoadOutcome
import com.fangyi.classpp.data.xmu.FakeXmuSecureStore
import com.fangyi.classpp.data.xmu.XmuAutoLoginPolicy
import com.fangyi.classpp.data.xmu.XmuCredentials
import com.fangyi.classpp.data.xmu.XmuSyncEntry
import com.fangyi.classpp.data.xmu.XmuSyncFile
import com.fangyi.classpp.data.xmu.XmuSyncStore
import com.fangyi.classpp.data.xmu.XmuTodoMapper
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 厦大存量数据迁移（通用存储切换的一次性桥）：状态文件/头像/凭据的转换、幂等与
 * 「先写后清」纪律。每日开关迁移走 SharedPreferences（Android 侧，见
 * [XmuLegacyMigrator.migrateDailySyncToggle] 的 provider 接线），不在 JVM 覆盖。
 */
class XmuLegacyMigrationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val filesDir: File get() = folder.root

    private val legacyDir: File get() = File(filesDir, "xmu")
    private val newMain: File get() = File(File(filesDir, "sync"), "xmu/sync.json")
    private val newAvatar: File get() = File(File(filesDir, "sync"), "xmu/avatar.img")

    // ---------- 同步状态 ----------

    @Test
    fun `migrates v2 state file to generic store with remoteKey conversion`() = runBlocking {
        XmuSyncStore(filesDir).save(
            XmuSyncFile(
                uid = "9001",
                accountName = "张三",
                avatarUrl = "https://lnt.xmu.edu.cn/api/uploads/a/modified-image",
                lastSyncAtMillis = 111L,
                lastProbeAtMillis = 222L,
                entries = listOf(
                    XmuSyncEntry(
                        remoteId = 7,
                        courseId = 3,
                        todoId = "xmu-3-7",
                        remoteHash = "RH",
                        localHash = "LH",
                    ),
                    XmuSyncEntry(
                        remoteId = 9,
                        courseId = 3,
                        todoId = "xmu-3-9",
                        remoteHash = "RH2",
                        localHash = "LH2",
                    ),
                ),
            ),
        )

        migrate()

        assertTrue("迁移后旧目录应删除", !legacyDir.exists())
        assertTrue("新状态文件应存在", newMain.isFile)
        val migrated = (SchoolSyncStore(filesDir, "xmu").load() as LoadOutcome.Loaded).file
        assertEquals("9001", migrated.uid)
        assertEquals("张三", migrated.accountName)
        assertEquals(111L, migrated.lastSyncAtMillis)
        assertEquals(222L, migrated.lastProbeAtMillis)
        assertEquals(1, migrated.formatVersion)
        // remoteKey = 冻结口径（courseId + remoteId），todoId 与两个哈希原样沿用
        assertEquals(
            listOf(
                SchoolSyncEntry(
                    remoteKey = "xmu:3:7",
                    todoId = "xmu-3-7",
                    remoteHash = "RH",
                    localHash = "LH",
                ),
                SchoolSyncEntry(
                    remoteKey = "xmu:3:9",
                    todoId = "xmu-3-9",
                    remoteHash = "RH2",
                    localHash = "LH2",
                ),
            ),
            migrated.entries,
        )
        // 转换键与冻结 mapper 同源（哈希基线无缝续用的前提）
        assertEquals(migrated.entries[0].remoteKey, XmuTodoMapper.remoteKey(3, 7))
    }

    @Test
    fun `migrates v1 legacy state file`() = runBlocking {
        // v1：旧 id 口径时代的状态文件（XmuSyncStore 可读取，entries 的 todoId 沿用）
        XmuSyncStore(filesDir).save(
            XmuSyncFile(
                formatVersion = 1,
                uid = "1",
                entries = listOf(
                    XmuSyncEntry(
                        remoteId = 5,
                        courseId = 2,
                        todoId = "xmu-5",
                        remoteHash = "R",
                        localHash = "L",
                    ),
                ),
            ),
        )

        migrate()

        val migrated = (SchoolSyncStore(filesDir, "xmu").load() as LoadOutcome.Loaded).file
        assertEquals("xmu:2:5", migrated.entries.single().remoteKey)
        assertEquals("xmu-5", migrated.entries.single().todoId)
        assertFalse(legacyDir.exists())
    }

    @Test
    fun `falls back to bak when main is corrupt`() = runBlocking {
        XmuSyncStore(filesDir).save(XmuSyncFile(uid = "42"))
        // 主文件写坏（bak 是 save 时复制的好数据）
        File(legacyDir, "xmu-sync.json").writeText("{broken")

        migrate()

        val migrated = (SchoolSyncStore(filesDir, "xmu").load() as LoadOutcome.Loaded).file
        assertEquals("42", migrated.uid)
        assertFalse(legacyDir.exists())
    }

    @Test
    fun `corrupt legacy state migrates as fresh and cleans legacy dir`() = runBlocking {
        assertTrue(legacyDir.mkdirs())
        File(legacyDir, "xmu-sync.json").writeText("{broken")
        File(legacyDir, "xmu-sync.json.bak").writeText("{also broken")

        migrate()

        // 双坏：无可迁移数据，引擎按空状态继续；旧目录（含隔离文件）清理
        assertFalse(legacyDir.exists())
        assertFalse(newMain.exists())
    }

    @Test
    fun `never overwrites newer generic state`() = runBlocking {
        // 引擎已写入较新状态（迁移早已完成、旧目录因上次清理失败残留）
        SchoolSyncStore(filesDir, "xmu").save(SchoolSyncFile(uid = "new"))
        XmuSyncStore(filesDir).save(XmuSyncFile(uid = "stale"))

        migrate()

        // 绝不回灌：新状态保持引擎的较新数据，旧目录只做清理
        val loaded = (SchoolSyncStore(filesDir, "xmu").load() as LoadOutcome.Loaded).file
        assertEquals("new", loaded.uid)
        assertFalse(legacyDir.exists())
    }

    @Test
    fun `no legacy dir is a no-op`() = runBlocking {
        migrate()
        assertFalse(newMain.exists())
    }

    @Test
    fun `keeps legacy dir when generic save fails`() = runBlocking {
        XmuSyncStore(filesDir).save(XmuSyncFile(uid = "9001"))
        // 阻断新落盘：把 sync/xmu 占位成普通文件，SchoolSyncStore.save 必然 Failed
        File(filesDir, "sync").mkdirs()
        File(filesDir, "sync/xmu").writeText("not a directory")

        migrate()

        // 先写后清：新存储写失败 → 旧目录原样保留（下次装配重试，不出现「两边都没有」）
        assertTrue(legacyDir.exists())
        assertTrue(File(legacyDir, "xmu-sync.json").isFile)
    }

    @Test
    fun `migration is idempotent`() = runBlocking {
        XmuSyncStore(filesDir).save(XmuSyncFile(uid = "9001", lastSyncAtMillis = 5L))

        migrate()
        val first = (SchoolSyncStore(filesDir, "xmu").load() as LoadOutcome.Loaded).file

        // 再次装配（无旧目录）：状态不变
        migrate()
        val second = (SchoolSyncStore(filesDir, "xmu").load() as LoadOutcome.Loaded).file
        assertEquals(first, second)
    }

    // ---------- 头像 ----------

    @Test
    fun `copies legacy avatar to generic dir`() = runBlocking {
        XmuSyncStore(filesDir).save(XmuSyncFile(uid = "9001"))
        File(legacyDir, "avatar.img").writeBytes(byteArrayOf(1, 2, 3))

        migrate()

        assertTrue(newAvatar.isFile)
        assertTrue(newAvatar.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertFalse(legacyDir.exists())
    }

    // ---------- 凭据 ----------

    @Test
    fun `migrates credentials with policy`() = runBlocking {
        val legacySecure = FakeXmuSecureStore(
            policy = XmuAutoLoginPolicy.ENABLED,
            credentials = XmuCredentials("20260001", "secret"),
            cookie = "session=S1",
        )
        val secure = FakeSecureStore()

        XmuLegacyMigrator(filesDir, legacySecure, secure).run()

        assertEquals(AutoRenewPolicy.ENABLED, secure.policy)
        assertEquals(StoredCredentials("20260001", "secret"), secure.credentials)
        assertEquals("session=S1", secure.cookie)
        // 旧位置清空（完成标记）：凭据/cookie 删除、策略复位为「未开启」
        assertNull(legacySecure.credentials)
        assertNull(legacySecure.cookie)
        assertEquals(XmuAutoLoginPolicy.USER_LOGGED_OUT, legacySecure.policy)
    }

    @Test
    fun `credential migration is idempotent and never resurrects stale policy`() = runBlocking {
        val legacySecure = FakeXmuSecureStore(
            policy = XmuAutoLoginPolicy.ENABLED,
            credentials = XmuCredentials("20260001", "secret"),
            cookie = "session=S1",
        )
        val secure = FakeSecureStore()
        val migrator = XmuLegacyMigrator(filesDir, legacySecure, secure)

        migrator.run()
        // 迁移后用户关闭了自动续期（新存储 = USER_LOGGED_OUT）
        secure.policy = AutoRenewPolicy.USER_LOGGED_OUT
        secure.credentials = null

        // 再次装配不得把旧文件的残留策略回灌进新存储
        migrator.run()
        assertEquals(AutoRenewPolicy.USER_LOGGED_OUT, secure.policy)
        assertNull(secure.credentials)
    }

    @Test
    fun `unreadable legacy credentials migrate policy only`() = runBlocking {
        // Keystore 失效：旧凭据读不出（available=false），策略是明文仍可读
        val legacySecure = FakeXmuSecureStore(
            policy = XmuAutoLoginPolicy.ENABLED,
            credentials = null,
            cookie = null,
            available = false,
        )
        val secure = FakeSecureStore()

        XmuLegacyMigrator(filesDir, legacySecure, secure).run()

        // 策略照搬；AutoRenewManager.bootstrap 会按既有语义把 ENABLED 归一为 BLOCKED
        assertEquals(AutoRenewPolicy.ENABLED, secure.policy)
        assertNull(secure.credentials)
        assertNull(secure.cookie)
        assertEquals(XmuAutoLoginPolicy.USER_LOGGED_OUT, legacySecure.policy)
    }

    @Test
    fun `skips credential migration when legacy store is already cleared`() = runBlocking {
        val legacySecure = FakeXmuSecureStore(
            policy = XmuAutoLoginPolicy.USER_LOGGED_OUT,
            credentials = null,
            cookie = null,
        )
        val secure = FakeSecureStore()

        XmuLegacyMigrator(filesDir, legacySecure, secure).run()

        // 本就未开启过自动续期：新存储零写入
        assertEquals(AutoRenewPolicy.USER_LOGGED_OUT, secure.policy)
        assertNull(secure.credentials)
        assertNull(secure.cookie)
    }

    @Test
    fun `keeps legacy credentials when new store write fails`() = runBlocking {
        val legacySecure = FakeXmuSecureStore(
            policy = XmuAutoLoginPolicy.ENABLED,
            credentials = XmuCredentials("20260001", "secret"),
            cookie = "session=S1",
        )
        val secure = FakeSecureStore().apply { saveFailed = true }

        XmuLegacyMigrator(filesDir, legacySecure, secure).run()

        // 先写后清：新存储写失败 → 旧位置原样保留（下次装配重试）
        assertEquals(XmuCredentials("20260001", "secret"), legacySecure.credentials)
        assertEquals("session=S1", legacySecure.cookie)
        assertEquals(XmuAutoLoginPolicy.ENABLED, legacySecure.policy)
    }

    // ---------- 工具 ----------

    private suspend fun migrate() {
        XmuLegacyMigrator(
            filesDir = filesDir,
            legacySecure = FakeXmuSecureStore(),
            secure = FakeSecureStore(),
        ).run()
    }

    /** 内存版通用安全存储（与 AutoRenewManagerTest 的替身同构） */
    private class FakeSecureStore(
        var policy: AutoRenewPolicy = AutoRenewPolicy.USER_LOGGED_OUT,
        var credentials: StoredCredentials? = null,
        var cookie: String? = null,
        override var available: Boolean = true,
    ) : SecureStore {
        var saveFailed = false

        override fun loadPolicy(): AutoRenewPolicy = policy

        override fun savePolicy(policy: AutoRenewPolicy): Boolean {
            if (saveFailed) return false
            this.policy = policy
            return true
        }

        override fun loadCredentials(): StoredCredentials? = credentials

        override fun saveCredentials(credentials: StoredCredentials?): Boolean {
            if (saveFailed) return false
            this.credentials = credentials
            return true
        }

        override fun loadCookieHeader(): String? = cookie

        override fun saveCookieHeader(header: String?): Boolean {
            if (saveFailed) return false
            cookie = header
            return true
        }

        override fun clear(): Boolean {
            credentials = null
            cookie = null
            return true
        }
    }
}
