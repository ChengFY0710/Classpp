package com.fangyi.classpp.data.xmu

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 复刻厦门大学统一身份认证登录页（iap）的密码加密约定（指导 §3.2）：
 *
 * - 明文 = 64 位随机字母数字 + 密码；
 * - `AES/CBC/PKCS5Padding`，key = `pwdEncryptSalt` 的 UTF-8 字节，iv = 16 位随机 ASCII；
 * - 输出 **Base64(密文)**，不前置 IV。
 *
 * 关键事实（2026-10-31 实测校方 `encrypt.js`）：其 `encryptPassword()` 用
 * `CryptoJS.AES.encrypt(...).toString()` 输出 OpenSSL 格式——无 salt 时只有密文，
 * IV 不随请求发送。服务端按固定 IV 解密：CBC 只有第一块受 IV 影响，而明文前 64 位
 * 本来就是随机噪声、服务端会剥掉，所以 IV 未知不影响第 5 块起的真实密码。
 * 若把 IV 拼进 Base64（早期实现），服务端会解出错误密码并重新渲染登录页。
 *
 * [base64Encoder] 注入以保持纯 JVM 可测：生产传 `android.util.Base64`（minSdk 24
 * 不能用 `java.util.Base64`），单测传 JDK 实现。
 *
 * salt 不是合法 AES key 长度（16/24/32 字节）时返回 null：调用方必须回退 WebView，
 * 不要猜一种畸形加密再提交（失败密码会累积锁号风险）。
 */
internal class XmuPasswordCipher(
    private val base64Encoder: (ByteArray) -> String,
    private val random: SecureRandom = SecureRandom(),
) {

    fun encrypt(password: String, salt: String): String? {
        val keyBytes = salt.toByteArray(Charsets.UTF_8)
        if (keyBytes.size != 16 && keyBytes.size != 24 && keyBytes.size != 32) return null
        return try {
            val iv = randomString(IV_LENGTH).toByteArray(Charsets.US_ASCII)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(iv),
            )
            val plain = (randomString(PREFIX_LENGTH) + password).toByteArray(Charsets.UTF_8)
            // 只输出密文（与校方 encrypt.js 的 CipherParams.toString() 一致）
            base64Encoder(cipher.doFinal(plain))
        } catch (_: Exception) {
            null
        }
    }

    private fun randomString(length: Int): String = buildString(length) {
        repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    private companion object {
        const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
        const val PREFIX_LENGTH = 64
        const val IV_LENGTH = 16
        const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }
}
