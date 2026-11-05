package com.fangyi.classpp.data.ai

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * AI 连接设置持久化：方案库 + 「自定义」槽位 + 活动指针。
 *
 * 活动配置二选一：
 * - [loadActiveProfileId] 非空 → 取该方案（[AiProfile.connection] + `api_key_<id>`）；
 * - 为空 → 取「自定义」槽位（复用旧版 `connection` / `api_key` 键，老配置无需迁移）。
 *
 * - 连接配置非敏感，明文 JSON 落 SharedPreferences；
 * - API Key（含每个方案各自的 Key）经 Android Keystore 的 AES-256/GCM 加密后落盘，
 *   **绝不退回明文**：任何加解密异常后 [secureAvailable] 置 false，本次进程内不再尝试，
 *   UI 据此置灰并提示重配——与 KeystoreXmuSecureStore 同策略（cipher 从 data.xmu 复制，
 *   密钥别名独立，两个模块不因 internal 可见性耦死）。
 *
 * 文件 `ai_secure` 已在 backup_rules.xml / data_extraction_rules.xml 中排除：
 * Keystore 密钥不随备份迁移，恢复后密文必然解不开，不如不留。
 */
interface AiSettingsStore {

    /** Keystore 是否可用；任何加解密异常后置 false，本次进程内不再尝试 */
    val secureAvailable: Boolean

    // ---------- 方案库 ----------

    fun loadProfiles(): List<AiProfile>

    /** 当前激活的方案 id；null = 使用「自定义」槽位。悬空指针按 null 处理 */
    fun loadActiveProfileId(): String?

    /** 保存（不存在则新增）方案；[activate] = true 同时把活动指针指向它 */
    fun saveProfile(profile: AiProfile, activate: Boolean): Boolean

    /** 只改名称；名称为空或方案不存在返回 false */
    fun renameProfile(id: String, name: String): Boolean

    /** 删除方案及其 Key；删的是活动方案时活动指针回落「自定义」（null） */
    fun deleteProfile(id: String): Boolean

    /** null = 切回「自定义」槽位 */
    fun setActiveProfile(id: String?): Boolean

    // ---------- 「自定义」槽位（旧版单配置的存储位置） ----------

    fun loadCustomConnection(): AiConnection

    /** 解密失败与未配置同形返回 null；UI 用 [secureAvailable] 区分「存了读不出」与「没存过」 */
    fun loadCustomKey(): String?

    /** 写自定义连接并切回自定义槽位（活动指针置空） */
    fun saveCustomConnection(connection: AiConnection): Boolean

    /** null = 清除已存 Key */
    fun saveCustomKey(key: String?): Boolean

    // ---------- 方案 Key ----------

    fun loadProfileKey(profileId: String): String?

    /** null = 清除该方案已存 Key */
    fun saveProfileKey(profileId: String, key: String?): Boolean

    // ---------- 活动配置（对话发送路径读取） ----------

    fun loadConnection(): AiConnection

    /** 解密失败与未配置同形返回 null */
    fun loadApiKey(): String?
}

/** 生产实现：SharedPreferences `ai_secure` + [AiKeystoreCipher] */
class PrefsAiSettingsStore(context: Context) : AiSettingsStore {

    private val appContext = context.applicationContext
    private val cipher = AiKeystoreCipher()
    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    }

    override val secureAvailable: Boolean get() = cipher.available

    // ---------- 方案库 ----------

    @Synchronized
    override fun loadProfiles(): List<AiProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            AiJson.storage.decodeFromString<List<AiProfile>>(raw)
        } catch (_: Exception) {
            // 单字段损坏不放大成功能故障：方案库回落空列表，用户重存即可
            emptyList()
        }
    }

    @Synchronized
    override fun loadActiveProfileId(): String? {
        val id = prefs.getString(KEY_ACTIVE_PROFILE, null) ?: return null
        // 悬空指针（方案被外部清理）按「自定义」解释，避免调用方拿到不存在的方案
        return id.takeIf { active -> loadProfiles().any { it.id == active } }
    }

    @Synchronized
    override fun saveProfile(profile: AiProfile, activate: Boolean): Boolean {
        val profiles = loadProfiles()
        val next = if (profiles.any { it.id == profile.id }) {
            profiles.map { if (it.id == profile.id) profile else it }
        } else {
            profiles + profile
        }
        val editor = prefs.edit().putString(KEY_PROFILES, encodeProfiles(next))
        if (activate) editor.putString(KEY_ACTIVE_PROFILE, profile.id)
        return editor.commit()
    }

    @Synchronized
    override fun renameProfile(id: String, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val profiles = loadProfiles()
        if (profiles.none { it.id == id }) return false
        val next = profiles.map { if (it.id == id) it.copy(name = trimmed) else it }
        return prefs.edit().putString(KEY_PROFILES, encodeProfiles(next)).commit()
    }

    @Synchronized
    override fun deleteProfile(id: String): Boolean {
        val profiles = loadProfiles()
        if (profiles.none { it.id == id }) return false
        val next = profiles.filterNot { it.id == id }
        val editor = prefs.edit()
            .putString(KEY_PROFILES, encodeProfiles(next))
            // 方案与 Key 同生共死：留着孤儿密文没有恢复入口，只会占地方
            .remove(profileKeyName(id))
        if (prefs.getString(KEY_ACTIVE_PROFILE, null) == id) {
            // 删的是活动方案 → 活动指针回落「自定义」，与课程时间方案的「落回自定义」同语义
            editor.remove(KEY_ACTIVE_PROFILE)
        }
        return editor.commit()
    }

    @Synchronized
    override fun setActiveProfile(id: String?): Boolean {
        val editor = prefs.edit()
        if (id == null) editor.remove(KEY_ACTIVE_PROFILE) else editor.putString(KEY_ACTIVE_PROFILE, id)
        return editor.commit()
    }

    // ---------- 「自定义」槽位 ----------

    @Synchronized
    override fun loadCustomConnection(): AiConnection {
        val raw = prefs.getString(KEY_CONNECTION, null) ?: return AiConnection()
        return try {
            AiJson.storage.decodeFromString<AiConnection>(raw)
        } catch (_: Exception) {
            AiConnection()
        }
    }

    @Synchronized
    override fun loadCustomKey(): String? {
        val blob = prefs.getString(KEY_API_KEY, null) ?: return null
        return cipher.decrypt(blob)
    }

    @Synchronized
    override fun saveCustomConnection(connection: AiConnection): Boolean =
        prefs.edit()
            .putString(KEY_CONNECTION, encodeConnection(connection))
            // 写自定义配置即切回自定义槽位：活动指针置空，读取路径不再命中旧方案
            .remove(KEY_ACTIVE_PROFILE)
            .commit()

    @Synchronized
    override fun saveCustomKey(key: String?): Boolean {
        val editor = prefs.edit()
        if (key == null) {
            return editor.remove(KEY_API_KEY).commit()
        }
        val blob = cipher.encrypt(key) ?: return false
        return editor.putString(KEY_API_KEY, blob).commit()
    }

    // ---------- 方案 Key ----------

    @Synchronized
    override fun loadProfileKey(profileId: String): String? {
        val blob = prefs.getString(profileKeyName(profileId), null) ?: return null
        return cipher.decrypt(blob)
    }

    @Synchronized
    override fun saveProfileKey(profileId: String, key: String?): Boolean {
        val editor = prefs.edit()
        if (key == null) {
            return editor.remove(profileKeyName(profileId)).commit()
        }
        val blob = cipher.encrypt(key) ?: return false
        return editor.putString(profileKeyName(profileId), blob).commit()
    }

    // ---------- 活动配置 ----------

    @Synchronized
    override fun loadConnection(): AiConnection {
        val activeId = prefs.getString(KEY_ACTIVE_PROFILE, null)
        if (activeId != null) {
            loadProfiles().firstOrNull { it.id == activeId }?.let { return it.connection }
        }
        return loadCustomConnection()
    }

    @Synchronized
    override fun loadApiKey(): String? {
        val activeId = prefs.getString(KEY_ACTIVE_PROFILE, null)
        if (activeId != null && loadProfiles().any { it.id == activeId }) {
            return loadProfileKey(activeId)
        }
        return loadCustomKey()
    }

    private fun encodeProfiles(profiles: List<AiProfile>): String =
        AiJson.storage.encodeToString(profiles)

    private fun encodeConnection(connection: AiConnection): String =
        AiJson.storage.encodeToString(AiConnection.serializer(), connection)

    private fun profileKeyName(profileId: String): String = KEY_API_KEY_PREFIX + profileId

    private companion object {
        const val FILE_NAME = "ai_secure"

        /** 「自定义」槽位（旧版单配置） */
        const val KEY_CONNECTION = "connection"
        const val KEY_API_KEY = "api_key"

        /** 方案库与活动指针 */
        const val KEY_PROFILES = "profiles"
        const val KEY_ACTIVE_PROFILE = "active_profile_id"

        /** 每方案 Key 的键前缀，完整键 = 前缀 + 方案 id */
        const val KEY_API_KEY_PREFIX = "api_key_"
    }
}

/**
 * Android Keystore 的 AES-256/GCM 封装（与 XmuKeystoreCipher 同构的独立副本，
 * 密钥别名 `classpp_ai_api_key_v1`，两处凭据互不可见）。
 *
 * 信封格式：`[version=1][ivLen][iv][ciphertext]`，Base64(NO_WRAP) 后写入
 * SharedPreferences。GCM 自带完整性校验，解密失败（换机/密钥被清）返回 null 并
 * 置 [available] = false——本次进程内读方会看到「安全存储不可用」，绝不静默重生成
 * 密钥把旧密文变废纸还假装成功。
 */
class AiKeystoreCipher(
    private val alias: String = DEFAULT_ALIAS,
) {
    @Volatile
    var available: Boolean = true
        private set

    @Synchronized
    fun encrypt(plain: String): String? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(
                AiKeystoreEnvelope.pack(cipher.iv, ciphertext),
                Base64.NO_WRAP,
            )
        } catch (_: Exception) {
            available = false
            null
        }
    }

    @Synchronized
    fun decrypt(encoded: String): String? {
        return try {
            val parts = AiKeystoreEnvelope.unpack(Base64.decode(encoded, Base64.NO_WRAP))
                ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, parts.iv),
            )
            String(cipher.doFinal(parts.ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            available = false
            null
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val DEFAULT_ALIAS = "classpp_ai_api_key_v1"
    }
}

/** 纯字节信封：与 Android 无关，可在 JVM 单测直接覆盖（同 XmuKeystoreEnvelope） */
object AiKeystoreEnvelope {

    private const val VERSION = 1
    private const val HEADER_BYTES = 2

    data class Parts(val iv: ByteArray, val ciphertext: ByteArray)

    fun pack(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val out = ByteArray(HEADER_BYTES + iv.size + ciphertext.size)
        out[0] = VERSION.toByte()
        out[1] = iv.size.toByte()
        iv.copyInto(out, HEADER_BYTES)
        ciphertext.copyInto(out, HEADER_BYTES + iv.size)
        return out
    }

    fun unpack(bytes: ByteArray): Parts? {
        if (bytes.size <= HEADER_BYTES) return null
        if (bytes[0].toInt() != VERSION) return null
        val ivLength = bytes[1].toInt() and 0xFF
        if (ivLength <= 0 || bytes.size <= HEADER_BYTES + ivLength) return null
        return Parts(
            iv = bytes.copyOfRange(HEADER_BYTES, HEADER_BYTES + ivLength),
            ciphertext = bytes.copyOfRange(HEADER_BYTES + ivLength, bytes.size),
        )
    }
}
