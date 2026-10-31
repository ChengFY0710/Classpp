package com.fangyi.classpp.data.xmu

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 密码加密（指导 §3.2）：AES/CBC + 64 位随机前缀，输出纯密文（不前置 IV） */
class XmuPasswordCipherTest {

    private val cipher = jvmCipher()

    private val SALT = "0123456789abcdef"

    @Test
    fun `payload is ciphertext only and block aligned`() {
        val encoded = cipher.encrypt("secret", SALT)
        assertNotNull(encoded)

        val ciphertext = Base64.getDecoder().decode(encoded!!)
        // 明文 64 + "secret"(6) = 70，PKCS7 补齐到 80；不能多出 16 字节 IV 前缀
        assertEquals(80, ciphertext.size)
        assertEquals(0, ciphertext.size % 16)
    }

    @Test
    fun `server decrypts with any iv because the prefix is noise`() {
        val ciphertext = Base64.getDecoder().decode(cipher.encrypt("secret", SALT)!!)

        // 服务端用固定/零 IV 解密：CBC 只有第一块受 IV 影响，而前 64 位本来就是随机噪声。
        // 因此从第 16 字节起必须与明文一致，结尾是真实密码。
        val plain = decrypt(ciphertext, SALT, ByteArray(16))

        assertEquals(64 + "secret".length, plain.length)
        assertTrue(plain.endsWith("secret"))
        assertTrue(plain.substring(16, 64).all { it.isLetterOrDigit() })
    }

    @Test
    fun `same password produces different payloads`() {
        assertTrue(cipher.encrypt("secret", SALT) != cipher.encrypt("secret", SALT))
    }

    @Test
    fun `accepts 24 and 32 byte salts`() {
        assertNotNull(cipher.encrypt("secret", "0123456789abcdef01234567"))
        assertNotNull(cipher.encrypt("secret", "0123456789abcdef0123456789abcdef"))
    }

    @Test
    fun `rejects salts that are not valid aes key lengths`() {
        assertNull(cipher.encrypt("secret", "too-short"))
        assertNull(cipher.encrypt("secret", "0123456789"))
        assertNull(cipher.encrypt("secret", ""))
    }

    private fun decrypt(ciphertext: ByteArray, salt: String, iv: ByteArray): String {
        val aes = Cipher.getInstance("AES/CBC/PKCS5Padding")
        aes.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(salt.toByteArray(), "AES"),
            IvParameterSpec(iv),
        )
        return String(aes.doFinal(ciphertext), Charsets.UTF_8)
    }
}
