package com.pdownloader.browser.media

import java.math.BigInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Standard HLS AES-128 CBC; this does not handle sample encryption or DRM licences. */
internal object Aes128Hls {
    fun validIv(value: String) = value.matches(Regex("0[xX][0-9a-fA-F]{1,32}"))
    fun normalizeIv(value: String): String {
        require(validIv(value))
        return value.substring(2).lowercase().padStart(32, '0')
    }
    fun sequenceIv(sequence: BigInteger): String {
        require(sequence.signum() >= 0 && sequence.bitLength() <= 64)
        return sequence.toString(16).padStart(32, '0')
    }
    fun decryptor(key: ByteArray, ivHex: String): Cipher {
        require(key.size == 16 && ivHex.matches(Regex("[0-9a-f]{32}")))
        val iv = ByteArray(16) { ivHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        // PKCS5Padding is the JCE name for the block-size-aware padding used with AES.
        return Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
    }
}
