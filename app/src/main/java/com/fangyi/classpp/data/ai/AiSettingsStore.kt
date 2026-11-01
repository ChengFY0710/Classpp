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

/**
 * AI 连接设置持久化。
 *
 * - 连接配置（[AiConnection]）非敏感，明文 JSON 落 SharedPreferences；
 * - API Key 经 Android Keystore 的 AES-256/GCM 加密后落盘，**绝不退回明文**：
 *   任何加解密异常后 [secureAvailable] 置 false，本次进程内不再尝试，UI 据此
 *   置灰并提示重配——与 KeystoreXmuSecureStore 同策略（cipher 从 data.xmu 复制，
 *   密钥别名独立，两个模块不因 internal 可见性耦死）。
 *
 * 文件 `ai_secure` 已在 backup_rules.xml / data_extraction_rules.xml 中排除：
 * Keystore 密钥不随备份迁移，恢复后密文必然解不开，不如不留。
 */
interface AiSettingsStore {

    /** Keystore 是否可用；任何加解密异常后置 false，本次进程内不再尝试 */
    val secureAvailable: Boolean

    fun loadConnection(): AiConnection
    fun saveConnection(connection: AiConnection): Boolean

    /** 解密失败与未配置同形返回 null；UI 用 [secureAvailable] 区分「存了读不出」与「没存过」 */
    fun loadApiKey(): String?

    /** null = 清除已存 Key（用户「移除配置」时与清连接配置一并调用） */
    fun saveApiKey(key: String?): Boolean
}

/** 生产实现：SharedPreferences `ai_secure` + [AiKeystoreCipher] */
class PrefsAiSettingsStore(context: Context) : AiSettingsStore {

    private val appContext = context.applicationContext
    private val cipher = AiKeystoreCipher()
    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    }

    override val secureAvailable: Boolean get() = cipher.available

    override fun loadConnection(): AiConnection {
        val raw = prefs.getString(KEY_CONNECTION, null) ?: return AiConnection()
        return try {
            AiJson.storage.decodeFromString<AiConnection>(raw)
        } catch (_: Exception) {
            // 单字段损坏不放大成功能故障：回落默认配置，用户重配即可
            AiConnection()
        }
    }

    override fun saveConnection(connection: AiConnection): Boolean =
        prefs.edit()
            .putString(KEY_CONNECTION, AiJson.storage.encodeToString(AiConnection.serializer(), connection))
            .commit()

    override fun loadApiKey(): String? {
        val blob = prefs.getString(KEY_API_KEY, null) ?: return null
        return cipher.decrypt(blob)
    }

    override fun saveApiKey(key: String?): Boolean {
        val editor = prefs.edit()
        if (key == null) {
            return editor.remove(KEY_API_KEY).commit()
        }
        val blob = cipher.encrypt(key) ?: return false
        return editor.putString(KEY_API_KEY, blob).commit()
    }

    private companion object {
        const val FILE_NAME = "ai_secure"
        const val KEY_CONNECTION = "connection"
        const val KEY_API_KEY = "api_key"
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
