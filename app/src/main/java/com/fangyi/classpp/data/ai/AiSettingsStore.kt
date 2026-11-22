package com.fangyi.classpp.data.ai

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * 活动连接 + Key 的一次性快照（同一把锁内解析，绝不混搭配对）。
 */
data class AiActiveCredentials(
    val connection: AiConnection,
    val apiKey: String?,
)

/**
 * 活动连接与 Key 的一致解析：活动指针有效时取该方案（连接 + 方案 Key），否则回落
 * 「自定义」槽位。抽成纯函数便于 JVM 测试；生产实现调用方持有同一把锁。
 */
internal fun resolveActiveCredentials(
    activeProfileId: String?,
    profiles: List<AiProfile>,
    profileKey: (String) -> String?,
    customConnection: () -> AiConnection,
    customKey: () -> String?,
): AiActiveCredentials {
    if (activeProfileId != null) {
        val profile = profiles.firstOrNull { it.id == activeProfileId }
        if (profile != null) {
            return AiActiveCredentials(profile.connection, profileKey(activeProfileId))
        }
    }
    return AiActiveCredentials(customConnection(), customKey())
}

/**
 * AI 连接设置持久化：方案库 + 「自定义」槽位 + 活动指针。
 *
 * 活动配置二选一：
 * - [loadActiveProfileId] 非空 → 取该方案（[AiProfile.connection] + `api_key_<id>`）；
 * - 为空 → 取「自定义」槽位（复用旧版 `connection` / `api_key` 键，老配置无需迁移）。
 *
 * 一份连接可带多个模型名（[AiConnection.models]），[AiConnection.model] 是当前生效项；
 * 对话浮层切换模型走 [setActiveModel]，API Key 与活动指针不变。
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

    /**
     * 活动连接 + Key 的一次性快照：发送路径必须用它，**不要**分两次调
     * [loadConnection] / [loadApiKey]——与设置页切换方案并发时，两次加锁读取可能
     * 拿到「A 方案的端点 + B 方案的 Key」。
     *
     * 默认实现留给无并发的测试 fake；生产实现同一把锁内解析。
     */
    fun loadActiveCredentials(): AiActiveCredentials =
        AiActiveCredentials(loadConnection(), loadApiKey())

    // ---------- 工具调用（非敏感，明文段） ----------

    /** 原生只读工具总开关；默认开启，关闭后完全走现有 JSON 回退 */
    fun loadToolsEnabled(): Boolean = true

    fun saveToolsEnabled(enabled: Boolean): Boolean = true

    /** 某连接的原生工具能力探测缓存；键见 [toolCapabilityKey] */
    fun loadToolCapability(key: String): AiToolCapability = AiToolCapability.Unknown

    fun saveToolCapability(key: String, capability: AiToolCapability) {}

    // ---------- 交互（非敏感，明文段） ----------

    /** 长按已选中的底部导航胶囊呼出 AI 对话；默认开启 */
    fun loadNavAiLongPressEnabled(): Boolean = true

    fun saveNavAiLongPressEnabled(enabled: Boolean): Boolean = true

    // ---------- 方案库 ----------

    fun loadProfiles(): List<AiProfile>

    /** 方案库曾经解析失败并留有取证文件：设置页据此提示用户重新保存配置 */
    fun hasCorruptProfiles(): Boolean = false

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

    /**
     * 切换活动连接（活动方案 / 自定义槽位）的当前模型；模型必须在该连接的
     * [AiConnection.modelOptions] 中（忽略大小写匹配并保存列表里的写法）。
     * 只改当前模型，不动 API Key 与活动指针。
     */
    fun setActiveModel(model: String): Boolean

    /**
     * 切换活动连接**当前模型**的推理等级；等级必须在该模型的
     * [AiModelConfig.reasoningLevels] 中（忽略大小写匹配并保存列表里的写法）。
     * 只改推理等级，不动模型、API Key 与活动指针。
     */
    fun setActiveReasoningLevel(level: String): Boolean

    // ---------- 「自定义」槽位（旧版单配置的存储位置） ----------

    fun loadCustomConnection(): AiConnection

    /** 解密失败与未配置同形返回 null；UI 用 [secureAvailable] 区分「存了读不出」与「没存过」 */
    fun loadCustomKey(): String?

    /** 写自定义连接并切回自定义槽位（活动指针置空） */
    fun saveCustomConnection(connection: AiConnection): Boolean

    /** null = 清除已存 Key */
    fun saveCustomKey(key: String?): Boolean

    /**
     * 「保存为新方案」把自定义草稿搬到新方案后，清空槽位里的模型名（当前模型 + 列表），
     * 其它连接字段与活动指针不动。已是空模型时直接成功。
     */
    fun clearCustomModels(): Boolean

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

    // ---------- 工具调用（非敏感，明文段） ----------

    @Synchronized
    override fun loadToolsEnabled(): Boolean = prefs.getBoolean(KEY_TOOLS_ENABLED, true)

    @Synchronized
    override fun saveToolsEnabled(enabled: Boolean): Boolean =
        prefs.edit().putBoolean(KEY_TOOLS_ENABLED, enabled).commit()

    @Synchronized
    override fun loadToolCapability(key: String): AiToolCapability {
        val raw = prefs.getString(KEY_TOOL_CAPABILITY_PREFIX + key, null) ?: return AiToolCapability.Unknown
        return try {
            AiToolCapability.valueOf(raw)
        } catch (_: IllegalArgumentException) {
            // 单字段损坏不放大成功能故障：回落 Unknown，下次请求重新探测
            AiToolCapability.Unknown
        }
    }

    @Synchronized
    override fun saveToolCapability(key: String, capability: AiToolCapability) {
        prefs.edit().putString(KEY_TOOL_CAPABILITY_PREFIX + key, capability.name).commit()
    }

    @Synchronized
    override fun loadNavAiLongPressEnabled(): Boolean =
        prefs.getBoolean(KEY_NAV_AI_LONG_PRESS, true)

    @Synchronized
    override fun saveNavAiLongPressEnabled(enabled: Boolean): Boolean =
        prefs.edit().putBoolean(KEY_NAV_AI_LONG_PRESS, enabled).commit()

    // ---------- 方案库 ----------

    @Synchronized
    override fun loadProfiles(): List<AiProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            AiJson.storage.decodeFromString<List<AiProfile>>(raw)
                // 旧配置无 models 字段：读出来即补成 [当前模型]，口径与写入一致
                .map { it.copy(connection = it.connection.withNormalizedModels()) }
        } catch (_: Exception) {
            // 单字段损坏不放大成功能故障：方案库回落空列表；但原始 JSON 先留一份取证，
            // 避免下次保存把用户方案永久覆盖成空
            runCatching {
                appContext.openFileOutput(PROFILES_CORRUPT_FILE, Context.MODE_PRIVATE)
                    .use { it.write(raw.toByteArray(Charsets.UTF_8)) }
            }
            emptyList()
        }
    }

    @Synchronized
    override fun hasCorruptProfiles(): Boolean =
        File(appContext.filesDir, PROFILES_CORRUPT_FILE).isFile

    @Synchronized
    override fun loadActiveProfileId(): String? {
        val id = prefs.getString(KEY_ACTIVE_PROFILE, null) ?: return null
        // 悬空指针（方案被外部清理）按「自定义」解释，避免调用方拿到不存在的方案
        return id.takeIf { active -> loadProfiles().any { it.id == active } }
    }

    @Synchronized
    override fun saveProfile(profile: AiProfile, activate: Boolean): Boolean {
        val normalized = profile.copy(connection = profile.connection.withNormalizedModels())
        val profiles = loadProfiles()
        val next = if (profiles.any { it.id == normalized.id }) {
            profiles.map { if (it.id == normalized.id) normalized else it }
        } else {
            profiles + normalized
        }
        val editor = prefs.edit().putString(KEY_PROFILES, encodeProfiles(next))
        if (activate) editor.putString(KEY_ACTIVE_PROFILE, normalized.id)
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

    @Synchronized
    override fun setActiveModel(model: String): Boolean {
        val target = model.trim()
        if (target.isEmpty()) return false
        val activeId = loadActiveProfileId()
        if (activeId != null) {
            val profile = loadProfiles().firstOrNull { it.id == activeId } ?: return false
            val canonical = profile.connection.modelOptions
                .firstOrNull { it.equals(target, ignoreCase = true) } ?: return false
            // 活动指针保持不动：这里只换当前模型，不是「切换方案」
            return saveProfile(
                profile.copy(connection = profile.connection.copy(model = canonical)),
                activate = false,
            )
        }
        val connection = loadCustomConnection()
        val canonical = connection.modelOptions
            .firstOrNull { it.equals(target, ignoreCase = true) } ?: return false
        return saveCustomConnection(connection.copy(model = canonical))
    }

    @Synchronized
    override fun setActiveReasoningLevel(level: String): Boolean {
        val target = level.trim()
        if (target.isEmpty()) return false
        val activeId = loadActiveProfileId()
        if (activeId != null) {
            val profile = loadProfiles().firstOrNull { it.id == activeId } ?: return false
            val connection = profile.connection
            val config = connection.modelConfig(connection.model)
            val canonical = config.reasoningLevels
                .firstOrNull { it.equals(target, ignoreCase = true) } ?: return false
            return saveProfile(
                profile.copy(
                    connection = connection.withModelConfig(
                        connection.model,
                        config.copy(reasoningLevel = canonical),
                    ),
                ),
                activate = false,
            )
        }
        val connection = loadCustomConnection()
        val config = connection.modelConfig(connection.model)
        val canonical = config.reasoningLevels
            .firstOrNull { it.equals(target, ignoreCase = true) } ?: return false
        return saveCustomConnection(
            connection.withModelConfig(connection.model, config.copy(reasoningLevel = canonical)),
        )
    }

    // ---------- 「自定义」槽位 ----------

    @Synchronized
    override fun loadCustomConnection(): AiConnection {
        val raw = prefs.getString(KEY_CONNECTION, null) ?: return AiConnection()
        return try {
            AiJson.storage.decodeFromString<AiConnection>(raw).withNormalizedModels()
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
            .putString(KEY_CONNECTION, encodeConnection(connection.withNormalizedModels()))
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

    @Synchronized
    override fun clearCustomModels(): Boolean {
        val connection = loadCustomConnection()
        // 模型级配置也随模型一起清空：迁移后槽位里不留已迁走模型的推理等级
        if (connection.model.isBlank() && connection.models.isEmpty() &&
            connection.modelConfigs.isEmpty()
        ) {
            return true
        }
        return prefs.edit()
            .putString(
                KEY_CONNECTION,
                encodeConnection(connection.withModels(emptyList(), "")),
            )
            .commit()
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

    @Synchronized
    override fun loadActiveCredentials(): AiActiveCredentials =
        resolveActiveCredentials(
            activeProfileId = prefs.getString(KEY_ACTIVE_PROFILE, null),
            profiles = loadProfiles(),
            profileKey = ::loadProfileKey,
            customConnection = ::loadCustomConnection,
            customKey = ::loadCustomKey,
        )

    private fun encodeProfiles(profiles: List<AiProfile>): String =
        AiJson.storage.encodeToString(profiles)

    private fun encodeConnection(connection: AiConnection): String =
        AiJson.storage.encodeToString(AiConnection.serializer(), connection)

    private fun profileKeyName(profileId: String): String = KEY_API_KEY_PREFIX + profileId

    private companion object {
        const val FILE_NAME = "ai_secure"

        /** 方案库 JSON 解析失败时的原始内容取证文件（filesDir 下；下次解析失败覆盖） */
        const val PROFILES_CORRUPT_FILE = "ai_profiles.corrupt.json"

        /** 「自定义」槽位（旧版单配置） */
        const val KEY_CONNECTION = "connection"
        const val KEY_API_KEY = "api_key"

        /** 方案库与活动指针 */
        const val KEY_PROFILES = "profiles"
        const val KEY_ACTIVE_PROFILE = "active_profile_id"

        /** 每方案 Key 的键前缀，完整键 = 前缀 + 方案 id */
        const val KEY_API_KEY_PREFIX = "api_key_"

        /** 原生只读工具总开关 */
        const val KEY_TOOLS_ENABLED = "tools_enabled"

        /** 能力缓存键前缀，完整键 = 前缀 + 连接指纹（含 URL，SharedPreferences 键允许任意字符串） */
        const val KEY_TOOL_CAPABILITY_PREFIX = "tool_capability_"

        /** 长按已选中的底部导航胶囊呼出 AI 对话 */
        const val KEY_NAV_AI_LONG_PRESS = "nav_ai_long_press"
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
            available = true
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
                ?: run {
                    // 信封损坏与加解密异常同一语义：标记不可用，绝不静默重生成密钥
                    available = false
                    return null
                }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, parts.iv),
            )
            String(cipher.doFinal(parts.ciphertext), Charsets.UTF_8).also {
                available = true
            }
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
