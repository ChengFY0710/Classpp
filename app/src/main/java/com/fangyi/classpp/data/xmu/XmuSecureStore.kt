package com.fangyi.classpp.data.xmu

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.fangyi.classpp.data.ScheduleJson
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable

/** 自动续期所需的凭据（只在登录瞬间短暂驻留内存） */
internal data class XmuCredentials(val username: String, val password: String)

/**
 * 自动续期的持久化（`SharedPreferences` 文件 `xmu_secure`）：
 * - 策略（ENABLED/BLOCKED/USER_LOGGED_OUT）是明文，便于 Keystore 失效时仍能展示暂停态；
 * - 学号/密码与 lnt Cookie 头经 Android Keystore 的 AES-256/GCM 加密后落盘；
 * - 加解密失败返回 null/false，**绝不退回明文**。
 *
 * 文件已在备份规则中排除；Keystore 密钥本身不可导出、不随备份迁移。
 */
internal interface XmuSecureStore {
    /** Keystore 是否可用；任何加解密异常后置 false，本次进程内不再尝试 */
    val available: Boolean

    fun loadPolicy(): XmuAutoLoginPolicy
    fun savePolicy(policy: XmuAutoLoginPolicy): Boolean

    fun loadCredentials(): XmuCredentials?
    fun saveCredentials(credentials: XmuCredentials?): Boolean

    fun loadCookieHeader(): String?
    fun saveCookieHeader(header: String?): Boolean

    /** 删除凭据与 Cookie（策略由调用方另写） */
    fun clear(): Boolean
}

internal class KeystoreXmuSecureStore(context: Context) : XmuSecureStore {

    private val appContext = context.applicationContext
    private val cipher = XmuKeystoreCipher()
    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    }

    override val available: Boolean get() = cipher.available

    override fun loadPolicy(): XmuAutoLoginPolicy {
        val raw = prefs.getString(KEY_POLICY, null) ?: return XmuAutoLoginPolicy.USER_LOGGED_OUT
        return try {
            XmuAutoLoginPolicy.valueOf(raw)
        } catch (_: IllegalArgumentException) {
            XmuAutoLoginPolicy.USER_LOGGED_OUT
        }
    }

    override fun savePolicy(policy: XmuAutoLoginPolicy): Boolean =
        prefs.edit().putString(KEY_POLICY, policy.name).commit()

    override fun loadCredentials(): XmuCredentials? {
        val blob = prefs.getString(KEY_CREDENTIALS, null) ?: return null
        val json = cipher.decrypt(blob) ?: return null
        val dto = try {
            ScheduleJson.decodeStorage<XmuCredentialBlob>(json)
        } catch (_: Exception) {
            return null
        }
        if (dto.username.isBlank() || dto.password.isEmpty()) return null
        return XmuCredentials(dto.username, dto.password)
    }

    override fun saveCredentials(credentials: XmuCredentials?): Boolean {
        val editor = prefs.edit()
        if (credentials == null) {
            return editor.remove(KEY_CREDENTIALS).commit()
        }
        val json = ScheduleJson.encodeStorage(
            XmuCredentialBlob(credentials.username, credentials.password),
        )
        val blob = cipher.encrypt(json) ?: return false
        return editor.putString(KEY_CREDENTIALS, blob).commit()
    }

    override fun loadCookieHeader(): String? =
        prefs.getString(KEY_COOKIE, null)?.let { cipher.decrypt(it) }

    override fun saveCookieHeader(header: String?): Boolean {
        val editor = prefs.edit()
        if (header.isNullOrBlank()) {
            return editor.remove(KEY_COOKIE).commit()
        }
        val blob = cipher.encrypt(header) ?: return false
        return editor.putString(KEY_COOKIE, blob).commit()
    }

    override fun clear(): Boolean =
        prefs.edit()
            .remove(KEY_CREDENTIALS)
            .remove(KEY_COOKIE)
            .commit()

    private companion object {
        const val FILE_NAME = "xmu_secure"
        const val KEY_POLICY = "auto_login_policy"
        const val KEY_CREDENTIALS = "credentials"
        const val KEY_COOKIE = "cookie_header"
    }
}

/**
 * Android Keystore 的 AES-256/GCM 封装。密钥别名固定，首次使用时生成。
 *
 * 信封格式：`[version=1][ivLen][iv][ciphertext]`，Base64(NO_WRAP) 后写入
 * SharedPreferences。GCM 自带完整性校验，解密失败（换机/密钥被清）返回 null。
 */
internal class XmuKeystoreCipher(
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
                XmuKeystoreEnvelope.pack(cipher.iv, ciphertext),
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
            val parts = XmuKeystoreEnvelope.unpack(Base64.decode(encoded, Base64.NO_WRAP))
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
        const val DEFAULT_ALIAS = "classpp_xmu_credentials_v1"
    }
}

/** 纯字节信封：与 Android 无关，可在 JVM 单测直接覆盖 */
internal object XmuKeystoreEnvelope {

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

@Serializable
private data class XmuCredentialBlob(
    val username: String,
    val password: String,
)
