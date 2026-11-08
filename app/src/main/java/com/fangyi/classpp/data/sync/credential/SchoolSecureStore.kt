package com.fangyi.classpp.data.sync.credential

import android.content.Context
import android.content.SharedPreferences
import com.fangyi.classpp.data.ScheduleJson
import com.fangyi.classpp.data.sync.AutoRenewPolicy
import kotlinx.serialization.Serializable

/** 自动续期所需的凭据（只在登录瞬间短暂驻留内存） */
data class StoredCredentials(val username: String, val password: String)

/**
 * 自动续期的持久化契约（生产 = [SchoolSecureStore]，测试 = 内存 fake）：
 * - 策略（ENABLED/BLOCKED/USER_LOGGED_OUT）是明文，便于 Keystore 失效时仍能展示暂停态；
 * - 账号/密码与 cookie 头经 Android Keystore 加密后落盘；
 * - 加解密失败返回 null/false，**绝不退回明文**。
 */
interface SecureStore {
    /** Keystore 是否可用；任何加解密异常后置 false，本次进程内不再尝试 */
    val available: Boolean

    fun loadPolicy(): AutoRenewPolicy
    fun savePolicy(policy: AutoRenewPolicy): Boolean

    fun loadCredentials(): StoredCredentials?
    fun saveCredentials(credentials: StoredCredentials?): Boolean

    fun loadCookieHeader(): String?
    fun saveCookieHeader(header: String?): Boolean

    /** 删除凭据与 Cookie（策略由调用方另写） */
    fun clear(): Boolean
}

/**
 * 新校凭据存储：所有学校**共用一个** `sync_secure` prefs 文件，按 `<id>/...` 分键
 * （指导 §2.6）。备份排除只需固定加一条 `sharedpref/sync_secure.xml`，一次加完永久
 * 覆盖所有未来学校，不用每校改 XML。
 *
 * Keystore alias = `classpp_sync_<id>_v1`；alias 改名 = 旧密文永久解不开（红线）。
 * 文件已在备份规则中排除；Keystore 密钥本身不可导出、不随备份迁移。
 */
class SchoolSecureStore(
    context: Context,
    private val schoolId: String,
) : SecureStore {

    init {
        require(SAFE_ID.matches(schoolId)) { "invalid school id for secure store: '$schoolId'" }
    }

    private val appContext = context.applicationContext
    private val cipher = KeystoreCipher(KeystoreCipher.aliasFor(schoolId))
    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    }

    override val available: Boolean get() = cipher.available

    override fun loadPolicy(): AutoRenewPolicy {
        val raw = prefs.getString(policyKey(schoolId), null)
            ?: return AutoRenewPolicy.USER_LOGGED_OUT
        return try {
            AutoRenewPolicy.valueOf(raw)
        } catch (_: IllegalArgumentException) {
            AutoRenewPolicy.USER_LOGGED_OUT
        }
    }

    override fun savePolicy(policy: AutoRenewPolicy): Boolean =
        prefs.edit().putString(policyKey(schoolId), policy.name).commit()

    override fun loadCredentials(): StoredCredentials? {
        val blob = prefs.getString(credentialsKey(schoolId), null) ?: return null
        val json = cipher.decrypt(blob) ?: return null
        val dto = try {
            ScheduleJson.decodeStorage<SyncCredentialBlob>(json)
        } catch (_: Exception) {
            return null
        }
        if (dto.username.isBlank() || dto.password.isEmpty()) return null
        return StoredCredentials(dto.username, dto.password)
    }

    override fun saveCredentials(credentials: StoredCredentials?): Boolean {
        val editor = prefs.edit()
        if (credentials == null) {
            return editor.remove(credentialsKey(schoolId)).commit()
        }
        val json = ScheduleJson.encodeStorage(
            SyncCredentialBlob(credentials.username, credentials.password),
        )
        val blob = cipher.encrypt(json) ?: return false
        return editor.putString(credentialsKey(schoolId), blob).commit()
    }

    override fun loadCookieHeader(): String? =
        prefs.getString(cookieKey(schoolId), null)?.let { cipher.decrypt(it) }

    override fun saveCookieHeader(header: String?): Boolean {
        val editor = prefs.edit()
        if (header.isNullOrBlank()) {
            return editor.remove(cookieKey(schoolId)).commit()
        }
        val blob = cipher.encrypt(header) ?: return false
        return editor.putString(cookieKey(schoolId), blob).commit()
    }

    override fun clear(): Boolean =
        prefs.edit()
            .remove(credentialsKey(schoolId))
            .remove(cookieKey(schoolId))
            .commit()

    companion object {
        /** 所有新校共用的凭据 prefs 文件（备份固定排除一条） */
        const val FILE_NAME = "sync_secure"

        fun policyKey(schoolId: String): String = "$schoolId/policy"
        fun credentialsKey(schoolId: String): String = "$schoolId/credentials"
        fun cookieKey(schoolId: String): String = "$schoolId/cookie"

        private val SAFE_ID = Regex("^[a-z][a-z0-9_]{0,31}$")
    }
}

@Serializable
private data class SyncCredentialBlob(
    val username: String,
    val password: String,
)
