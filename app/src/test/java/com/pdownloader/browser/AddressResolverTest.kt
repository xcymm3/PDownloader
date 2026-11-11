package com.pdownloader.browser

import org.junit.Assert.*
import org.junit.Test

class AddressResolverTest {
    @Test fun supportsWebAddressesAndLocalServers() {
        assertEquals("https://example.com/path?q=1", AddressResolver.resolve(" example.com/path?q=1 "))
        assertEquals("http://127.0.0.1:8080/video", AddressResolver.resolve("http://127.0.0.1:8080/video"))
        assertEquals("https://localhost:8080", AddressResolver.resolve("localhost:8080"))
        assertEquals("https://例子.中国", AddressResolver.resolve("例子.中国"))
        assertEquals("https://[::1]:8080", AddressResolver.resolve("[::1]:8080"))
    }
    @Test fun encodesSearchQueriesWithoutChangingTheirMeaning() {
        assertEquals("https://www.google.com.hk/search?q=Kotlin+%E6%B5%8F%E8%A7%88%E5%99%A8", AddressResolver.resolve("Kotlin 浏览器"))
        assertEquals("https://www.google.com.hk/search?q=a%26b+%2B+c%23", AddressResolver.resolve("a&b + c#"))
        assertEquals("https://www.google.com.hk/search?q=hello", AddressResolver.resolve("hello"))
    }
    @Test fun rejectsExecutableLocalAndMalformedUrls() {
        listOf("javascript:alert(1)", "data:text/html,test", "file:///sdcard/test.html", "content://private", "intent://app", "https://", "https://example.com/a b", "https://user:password@example.com").forEach {
            assertNull(it, AddressResolver.resolve(it))
            assertFalse(it, AddressResolver.isWebUrl(it))
        }
        assertNull(AddressResolver.resolve("  "))
        assertFalse(AddressResolver.isWebUrl("example.com"))
    }
}
