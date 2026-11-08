package com.fangyi.classpp.schools.xmu

import android.content.Context
import com.fangyi.classpp.data.sync.AutoRenewPolicy
import com.fangyi.classpp.data.store.LoadOutcome
import com.fangyi.classpp.data.store.StoreResult
import com.fangyi.classpp.data.sync.PrefsDailySyncToggle
import com.fangyi.classpp.data.sync.credential.SchoolSecureStore
import com.fangyi.classpp.data.sync.credential.SecureStore
import com.fangyi.classpp.data.sync.credential.StoredCredentials
import com.fangyi.classpp.data.sync.engine.SchoolSyncEntry
import com.fangyi.classpp.data.sync.engine.SchoolSyncFile
import com.fangyi.classpp.data.sync.engine.SchoolSyncStore
import com.fangyi.classpp.data.sync.engine.SCHOOL_SYNC_FORMAT_VERSION
import com.fangyi.classpp.data.xmu.XmuAutoLoginPolicy
import com.fangyi.classpp.data.xmu.XmuSecureStore
import com.fangyi.classpp.data.xmu.XmuSyncFile
import com.fangyi.classpp.data.xmu.XmuSyncPreferences
import com.fangyi.classpp.data.xmu.XmuSyncStore
import com.fangyi.classpp.data.xmu.XmuTodoMapper
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 厦大存量数据迁移（通用存储切换的一次性桥，指导 §2.6）：同步状态文件、头像、凭据、
 * 每日开关四类，全部搬进通用层的按校分键/分目录存储。
 *
 * 纪律：
 * - **幂等**：以「目标已在 / 旧位置已清空」为完成标记，重复装配不重跑；
 * - **先写后清**：新位置落盘成功才清理旧位置；任何一步失败旧数据原样保留，
 *   下次装配重试，绝不出现「两边都没有」；
 * - **绝不回灌**：新状态已在时旧目录只做清理，不用旧数据覆盖引擎的较新状态；
 * - 凭据用旧 Keystore 密钥（`classpp_xmu_credentials_v1`）解密、新密钥
 *   （`classpp_sync_xmu_v1`）重加密——信封格式逐字节兼容（SyncToolkitFrozenTest 交叉解包锁定），
 *   只有 alias 不同。
 *
 * 迁移失败不阻断装配：状态按「无数据」继续（引擎按 entriesForActive 重建映射，已导入
 * 待办由确定性 id 找回，不会重复导入）；凭据读不出（Keystore 失效）时用户重新开启
 * 自动续期即可，与改造前 Keystore 失效的后果一致。
 *
 * 测试：[XmuSecureStore] 与 [SecureStore] 都是接口，文件操作只依赖 [filesDir]——
 * 全类可 JVM 单测（见 XmuLegacyMigrationTest）。
 */
internal class XmuLegacyMigrator(
    private val filesDir: File,
    private val legacySecure: XmuSecureStore,
    private val secure: SecureStore,
) {

    /** 阻塞 I/O，装配时调用一次（注册表已把 createSession 下沉 IO，这里再包一层语义自洽） */
    suspend fun run() {
        withContext(Dispatchers.IO) {
            migrateSyncState()
            migrateCredentials()
        }
    }

    // ---------- 同步状态 + 头像 ----------

    private fun migrateSyncState() {
        val legacyDir = File(filesDir, XmuSyncStore.DIR_NAME)
        if (!legacyDir.isDirectory) return

        val newRoot = File(File(filesDir, SchoolSyncStore.ROOT_DIR_NAME), SCHOOL_ID)
        val newMain = File(newRoot, SchoolSyncStore.MAIN_NAME)
        if (newMain.exists()) {
            // 新状态已在（上次迁移成功、或引擎已写入较新状态）：旧目录只做清理，
            // 绝不用旧数据回灌覆盖
            deleteLegacyDir()
            return
        }

        when (val outcome = XmuSyncStore(filesDir).load()) {
            is LoadOutcome.Loaded<XmuSyncFile> -> {
                copyLegacyAvatar(newRoot)
                when (SchoolSyncStore(filesDir, SCHOOL_ID).save(convert(outcome.file))) {
                    StoreResult.Ok -> deleteLegacyDir()
                    is StoreResult.Failed -> Unit // 落盘失败：旧目录保留，下次装配重试
                }
            }
            // 无可迁移数据（空目录 / 主备双坏已隔离）：旧目录只剩垃圾，直接清掉；
            // 引擎按空状态继续，映射与头像由下次同步重建/重下
            is LoadOutcome.Fresh<XmuSyncFile> -> deleteLegacyDir()
        }
    }

    private fun copyLegacyAvatar(newRoot: File) {
        val legacyAvatar = File(File(filesDir, XmuSyncStore.DIR_NAME), AVATAR_FILE_NAME)
        if (!legacyAvatar.isFile) return
        runCatching {
            newRoot.mkdirs()
            // 新状态目录必然为空（newMain 不存在），正常不会撞名；撞名按已迁移处理
            legacyAvatar.copyTo(File(newRoot, AVATAR_FILE_NAME), overwrite = false)
        }
    }

    private fun deleteLegacyDir() {
        File(filesDir, XmuSyncStore.DIR_NAME).deleteRecursively()
    }

    /**
     * 状态信封转换：`XmuSyncFile → SchoolSyncFile`。字段一一对应；entries 的
     * `remoteId + courseId` 合成冻结口径的 remoteKey，todoId 与两个哈希原样沿用——
     * 编码器产出的哈希与旧仓库同源，基线无缝续用（否则首同步会误判全部变化）。
     */
    private fun convert(legacy: XmuSyncFile): SchoolSyncFile = SchoolSyncFile(
        formatVersion = SCHOOL_SYNC_FORMAT_VERSION,
        uid = legacy.uid,
        accountName = legacy.accountName,
        avatarUrl = legacy.avatarUrl,
        lastSyncAtMillis = legacy.lastSyncAtMillis,
        lastProbeAtMillis = legacy.lastProbeAtMillis,
        entries = legacy.entries.map { entry ->
            SchoolSyncEntry(
                remoteKey = XmuTodoMapper.remoteKey(entry.courseId, entry.remoteId),
                todoId = entry.todoId,
                remoteHash = entry.remoteHash,
                localHash = entry.localHash,
            )
        },
    )

    // ---------- 凭据 ----------

    private fun migrateCredentials() {
        // 完成标记：旧位置已清空（上次迁移成功）或本就未开启过自动续期
        val stale = legacySecure.loadPolicy() == XmuAutoLoginPolicy.USER_LOGGED_OUT &&
            legacySecure.loadCredentials() == null &&
            legacySecure.loadCookieHeader() == null
        if (stale) return

        val credentials = legacySecure.loadCredentials()
        val cookie = legacySecure.loadCookieHeader()

        secure.savePolicy(legacySecure.loadPolicy().toGeneric())
        val credsSaved = credentials == null ||
            secure.saveCredentials(StoredCredentials(credentials.username, credentials.password))
        val cookieSaved = cookie == null || secure.saveCookieHeader(cookie)
        // 部分失败（磁盘/Keystore 异常）：旧位置原样保留，下次装配重试；
        // 新位置已写的部分会被下次迁移覆盖，不会出现两边都不一致
        if (!credsSaved || !cookieSaved) return

        legacySecure.clear()
        // 旧策略复位为「未开启」：完成标记的一部分（下次装配 stale 命中，不再重跑），
        // 也避免旧文件里的残留策略在后续版本回灌新存储
        legacySecure.savePolicy(XmuAutoLoginPolicy.USER_LOGGED_OUT)
    }

    /** 冻结策略枚举与通用枚举同名同值（落盘即策略名），一一同构映射不兜底 */
    private fun XmuAutoLoginPolicy.toGeneric(): AutoRenewPolicy = when (this) {
        XmuAutoLoginPolicy.ENABLED -> AutoRenewPolicy.ENABLED
        XmuAutoLoginPolicy.BLOCKED -> AutoRenewPolicy.BLOCKED
        XmuAutoLoginPolicy.USER_LOGGED_OUT -> AutoRenewPolicy.USER_LOGGED_OUT
    }

    companion object {
        /** 迁移目标学校 id（provider.spec.id；此处常量化便于 JVM 测试直接构造） */
        internal const val SCHOOL_ID = "xmu"

        /** 头像文件名与 [AvatarCache] 的落盘名一致（其常量私有，此处按值锁定并注释） */
        private const val AVATAR_FILE_NAME = "avatar.img"

        /**
         * 每日开关迁移（`xmu_sync_prefs` → `sync_prefs` 按 `<id>/` 分键）：provider 的
         * `dailySyncToggle` 是冷启动与后台重排的共同入口，新键已存在（迁移完成或用户已改）
         * 则跳过。写动作幂等，旧 prefs 文件保留不删（单个布尔键，备份已排除）。
         */
        fun migrateDailySyncToggle(context: Context) {
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(
                PrefsDailySyncToggle.FILE_NAME,
                Context.MODE_PRIVATE,
            )
            val newKey = PrefsDailySyncToggle.enabledKey(XmuTodoMapper.SOURCE_XMU)
            if (prefs.contains(newKey)) return
            prefs.edit()
                .putBoolean(newKey, XmuSyncPreferences.isDailySyncEnabled(appContext))
                .apply()
        }
    }
}
