package com.fangyi.classpp.data.xmu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Keystore 密文信封的纯字节往返（Android Keystore 本身由真机验收） */
class XmuKeystoreEnvelopeTest {

    @Test
    fun `pack and unpack round trip`() {
        val iv = ByteArray(12) { it.toByte() }
        val ciphertext = byteArrayOf(9, 8, 7, 6)

        val parts = XmuKeystoreEnvelope.unpack(XmuKeystoreEnvelope.pack(iv, ciphertext))!!

        assertArrayEquals(iv, parts.iv)
        assertArrayEquals(ciphertext, parts.ciphertext)
    }

    @Test
    fun `rejects malformed payloads`() {
        assertNull(XmuKeystoreEnvelope.unpack(ByteArray(0)))
        assertNull(XmuKeystoreEnvelope.unpack(byteArrayOf(1, 0)))
        assertNull(XmuKeystoreEnvelope.unpack(byteArrayOf(2, 1, 5, 6)))
        assertNull(XmuKeystoreEnvelope.unpack(byteArrayOf(1, 5, 1, 2)))
    }
}
