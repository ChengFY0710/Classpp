package com.fangyi.classpp.data.sync.credential

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore 的 AES-256/GCM 封装（通用层版本）：密钥别名由学校 id 派生
 * （`classpp_sync_<id>_v1`），首次使用时生成。
 *
 * 信封格式与既有实现逐字节一致：`[version=1][ivLen][iv][ciphertext]`，
 * Base64(NO_WRAP) 后写入 SharedPreferences。GCM 自带完整性校验，解密失败
 * （换机/密钥被清）返回 null，**绝不退回明文**。
 *
 * 通用层不引用任何学校实现的类型（架构守卫），因此这里保留一份等价实现；
 * 信封格式由 `SyncToolkitTest` 与冻结实现做交叉解包验证。
 */
class KeystoreCipher(
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
                KeystoreEnvelope.pack(cipher.iv, ciphertext),
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
            val parts = KeystoreEnvelope.unpack(Base64.decode(encoded, Base64.NO_WRAP))
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

    companion object {
        /** 新校 alias 前缀；完整值 = [aliasFor] */
        const val ALIAS_PREFIX = "classpp_sync_"

        /** 兼容用默认值（通用层自身不持久化任何凭据；生产一律按校传 alias） */
        private const val DEFAULT_ALIAS = "classpp_sync_default_v1"

        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        /**
         * 学校 id → Keystore alias。id 必须匹配注册表的格式（小写字母/数字/下划线）：
         * alias 改名 = 旧密文永久解不开，用户会被静默 BLOCKED 强制重输密码（不可逆事故）。
         */
        fun aliasFor(schoolId: String): String {
            require(SAFE_ID.matches(schoolId)) { "invalid school id for keystore alias: '$schoolId'" }
            return "$ALIAS_PREFIX${schoolId}_v1"
        }

        private val SAFE_ID = Regex("^[a-z][a-z0-9_]{0,31}$")
    }
}

/** 纯字节信封：与 Android 无关，可在 JVM 单测直接覆盖 */
object KeystoreEnvelope {

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
