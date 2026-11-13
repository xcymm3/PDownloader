package com.pdownloader.browser.media

import java.math.BigInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class Aes128HlsTest {
    private val base = "https://example.com/video/list.m3u8"
    private fun playlist(body: String) = HlsPlaylist.parse("#EXTM3U\n$body\n#EXT-X-ENDLIST", base)
    private fun segment(name: String) = "#EXTINF:1,\n$name"
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun ordinaryAes128UsesTheSequenceAndPreservesSignedKeyUrls() {
        val parsed = playlist("#EXT-X-MEDIA-SEQUENCE:7\n#EXT-X-KEY:METHOD=AES-128,URI=\"../key?sig=a%2Bb\"\n${segment("a.ts")}\n${segment("b.ts")}")
        assertEquals("", parsed.blocked)
        assertEquals("https://example.com/key?sig=a%2Bb", parsed.parts[0].encryption!!.keyUrl)
        assertEquals("00000000000000000000000000000007", parsed.parts[0].encryption!!.ivHex)
        assertEquals("00000000000000000000000000000008", parsed.parts[1].encryption!!.ivHex)
        assertEquals(parsed.parts[0].encryption!!.keyIndex, parsed.parts[1].encryption!!.keyIndex)
        assertFalse(parsed.localLines.any { it.contains("EXT-X-KEY") || it.contains("sig=") })
    }
    @Test fun explicitIvIsPaddedAndKeyChangesApplyOnlyToFollowingSegments() {
        val parsed = playlist("#EXT-X-KEY:METHOD=AES-128,URI=\"one\",IV=0XfF\n${segment("a.ts")}\n#EXT-X-KEY:METHOD=AES-128,URI=\"two\",IV=0x10\n${segment("b.ts")}")
        assertEquals("000000000000000000000000000000ff", parsed.parts[0].encryption!!.ivHex)
        assertEquals("https://example.com/video/two", parsed.parts[1].encryption!!.keyUrl)
        assertNotEquals(parsed.parts[0].encryption!!.keyIndex, parsed.parts[1].encryption!!.keyIndex)
    }
    @Test fun noneDisablesEncryptionAndSequencesStillAdvance() {
        val parsed = playlist("#EXT-X-MEDIA-SEQUENCE:5\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n${segment("a.ts")}\n#EXT-X-KEY:METHOD=NONE\n${segment("b.ts")}\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n${segment("c.ts")}")
        assertNull(parsed.parts[1].encryption)
        assertEquals("00000000000000000000000000000007", parsed.parts[2].encryption!!.ivHex)
        assertNotEquals(parsed.parts[0].encryption!!.keyIndex, parsed.parts[2].encryption!!.keyIndex)
    }
    @Test fun unencryptedInitDoesNotConsumeAMediaSequenceNumber() {
        val parsed = playlist("#EXT-X-MEDIA-SEQUENCE:11\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n${segment("a.m4s")}")
        assertNull(parsed.parts[0].encryption)
        assertEquals("0000000000000000000000000000000b", parsed.parts[1].encryption!!.ivHex)
    }
    @Test fun encryptedInitRequiresAnExplicitIvAndPreservesByteRanges() {
        val body = "#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXT-X-MAP:URI=\"bundle\",BYTERANGE=\"32@0\"\n#EXTINF:1,\n#EXT-X-BYTERANGE:48@32\nbundle"
        assertEquals("暂不支持加密 HLS 或 DRM 媒体", playlist(body).blocked)
        val parsed = playlist(body.replace("URI=\"key\"", "URI=\"key\",IV=0x1"))
        assertEquals("", parsed.blocked)
        assertEquals(32L, parsed.parts[1].offset)
        assertEquals(48L, parsed.parts[1].length)
        assertEquals("00000000000000000000000000000001", parsed.parts[0].encryption!!.ivHex)
        assertEquals("#EXT-X-MAP:URI=\"part-0.bin\"", parsed.localLines.first { it.startsWith("#EXT-X-MAP:") })
    }
    @Test fun unsupportedProtectionKeepsTheExactExistingMessage() {
        listOf(
            "METHOD=SAMPLE-AES,URI=\"key\"",
            "METHOD=SAMPLE-AES-CTR,URI=\"key\"",
            "METHOD=AES-256,URI=\"key\"",
            "METHOD=AES-128,URI=\"key\",KEYFORMAT=\"com.apple.streamingkeydelivery\"",
            "METHOD=AES-128,URI=\"key\",KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"",
            "METHOD=AES-128,URI=\"key\",KEYFORMATVERSIONS=\"2\"",
            "METHOD=AES-128,URI=\"file:///secret\"",
            "METHOD=AES-128",
            "METHOD=AES-128,URI=\"key\",IV=0x${"f".repeat(33)}",
            "METHOD=AES-128,URI=\"key\",IV=garbage"
        ).forEach { attrs ->
            val parsed = playlist("#EXT-X-KEY:$attrs\n${segment("a.ts")}")
            assertEquals(attrs, "暂不支持加密 HLS 或 DRM 媒体", parsed.blocked)
            assertTrue(parsed.parts.isEmpty())
        }
    }
    @Test fun masterSessionKeysAllowOnlyOrdinaryAes128() {
        val master = "#EXTM3U\n#EXT-X-SESSION-KEY:METHOD=AES-128,URI=\"key\"\n#EXT-X-STREAM-INF:BANDWIDTH=100000\nchild.m3u8"
        assertEquals(1, HlsPlaylist.parse(master, base).variants.size)
        assertEquals("暂不支持加密 HLS 或 DRM 媒体", HlsPlaylist.parse(master.replace("AES-128", "SAMPLE-AES"), base).blocked)
    }
    @Test fun sequenceIvSupportsTheFullUnsigned64BitRange() {
        assertEquals("0000000000000000ffffffffffffffff", Aes128Hls.sequenceIv(BigInteger("18446744073709551615")))
        assertThrows(IllegalArgumentException::class.java) { Aes128Hls.sequenceIv(BigInteger.ONE.shiftLeft(64)) }
    }
    @Test fun decryptsStandardCbcAcrossArbitraryReadBoundaries() {
        val key = hex("2b7e151628aed2a6abf7158809cf4f3c")
        val iv = "000102030405060708090a0b0c0d0e0f"
        val plain = hex("6bc1bee22e409f96e93d7e117393172a")
        val encryptor = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(hex(iv))) }
        val encrypted = encryptor.doFinal(plain)
        assertArrayEquals(hex("7649abac8119b246cee98e9b12e9197d"), encrypted.copyOf(16))
        val decoder = Aes128Hls.decryptor(key, iv)
        val output = java.io.ByteArrayOutputStream()
        encrypted.toList().chunked(3).forEach { chunk -> decoder.update(chunk.toByteArray())?.let(output::write) }
        output.write(decoder.doFinal())
        assertArrayEquals(plain, output.toByteArray())
    }
    @Test fun rejectsInvalidKeysTruncatedCiphertextAndInvalidPadding() {
        assertThrows(IllegalArgumentException::class.java) { Aes128Hls.decryptor(ByteArray(15), "0".repeat(32)) }
        assertThrows(javax.crypto.IllegalBlockSizeException::class.java) { Aes128Hls.decryptor(ByteArray(16), "0".repeat(32)).doFinal(ByteArray(17)) }
        val badPadding = Cipher.getInstance("AES/CBC/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(16), "AES"), IvParameterSpec(ByteArray(16)))
        }.doFinal(ByteArray(16))
        assertThrows(javax.crypto.BadPaddingException::class.java) { Aes128Hls.decryptor(ByteArray(16), "0".repeat(32)).doFinal(badPadding) }
    }
}
