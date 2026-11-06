package com.pdownloader.browser.media

import org.junit.Assert.*
import org.junit.Test

class HlsPlaylistTest {
    @Test fun masterGroupsKeepSignedUrlsAndAudioRestrictions() {
        val parsed = HlsPlaylist.parse("""
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",URI="audio.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=1280x720,CODECS="avc1.42,mp4a.40"
            720.m3u8?token=a%2Bb&x=1
            #EXT-X-STREAM-INF:BANDWIDTH=400000,AUDIO="audio"
            //cdn.example.com/360.m3u8
        """.trimIndent(), "https://example.com/v/master.m3u8")
        assertEquals(2, parsed.variants.size)
        assertEquals("https://example.com/v/720.m3u8?token=a%2Bb&x=1", parsed.variants[0].url)
        assertEquals("720p", parsed.variants[0].quality)
        assertTrue(parsed.variants[1].quality.contains("kbps"))
        assertTrue(parsed.variants[1].blockedReason.contains("独立音轨"))
    }
    @Test fun localVodPreservesInitDiscontinuityAndRanges() {
        val parsed = HlsPlaylist.parse("""
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:2
            #EXT-X-MAP:URI="init.mp4",BYTERANGE="10@0"
            #EXTINF:1.5,
            #EXT-X-BYTERANGE:20@5
            segments.mp4?token=keep
            #EXT-X-DISCONTINUITY
            #EXTINF:2,
            #EXT-X-BYTERANGE:30
            segments.mp4?token=keep
            #EXT-X-ENDLIST
        """.trimIndent(), "https://example.com/path/list.m3u8")
        assertEquals(3, parsed.parts.size)
        assertEquals(HlsPart("https://example.com/path/init.mp4", 10, 0), parsed.parts[0])
        assertEquals(25L, parsed.parts[2].offset)
        assertEquals(3500L, parsed.durationMs)
        assertTrue("#EXT-X-DISCONTINUITY" in parsed.localLines)
        assertTrue(parsed.localLines.any { it == "#EXT-X-MAP:URI=\"part-0.bin\"" })
        assertFalse(parsed.localLines.any { it.contains("https://") || it.startsWith("#EXT-X-BYTERANGE") })
    }
    @Test fun unsupportedListsAreExplicitlyRejected() {
        assertTrue(HlsPlaylist.parse("#EXTM3U\n#EXTINF:1,\na.ts", "https://example.com/list").blocked.contains("直播"))
        assertTrue(HlsPlaylist.parse("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:1,\na.ts\n#EXT-X-ENDLIST", "https://example.com/list").blocked.contains("加密"))
        assertThrows(IllegalArgumentException::class.java) { HlsPlaylist.parse("<html>not a playlist</html>", "https://example.com/list") }
        assertThrows(IllegalArgumentException::class.java) { HlsPlaylist.parse("#EXTM3U\n#EXTINF:1,\nfile:///secret\n#EXT-X-ENDLIST", "https://example.com/list") }
        assertThrows(IllegalArgumentException::class.java) { HlsPlaylist.parse("#EXTM3U\n#EXTINF:1,\n#EXT-X-BYTERANGE:20\nsegment.ts\n#EXT-X-ENDLIST", "https://example.com/list") }
    }
    @Test fun filenamesAndDedupPreserveUserResourceIdentity() {
        assertEquals("a_b_c.mp4", safeFileName("a/b:c", "mp4"))
        assertEquals("媒体.jpg", safeFileName("..", "jpg"))
        assertNotEquals(stableId("https://example.com/video?sig=1"), stableId("https://example.com/video?sig=2"))
        assertEquals("大小未知", bytes(-1))
        assertTrue(safeFileName("测".repeat(200), "mp4").toByteArray(Charsets.UTF_8).size <= 200)
        assertEquals("bin", extension(MediaVariant("https://example.com/stream", MediaKind.VIDEO)))
        assertEquals("ts", extension(MediaVariant("https://example.com/stream", MediaKind.VIDEO, mime = "video/mp2t")))
    }
}
