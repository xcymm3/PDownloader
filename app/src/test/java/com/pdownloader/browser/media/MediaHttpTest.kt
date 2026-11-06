package com.pdownloader.browser.media

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket

class MediaHttpTest {
    @Test fun redirectsRecomputeCookiesAndStripCrossOriginAuthorization() {
        val server = ServerSocket(0)
        val port = server.localPort
        var cookie = ""; var auth = ""
        val worker = Thread {
            repeat(2) { index -> server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader()
                input.readLine()
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                }
                val response = if (index == 0) "HTTP/1.1 302 Found\r\nLocation: http://localhost:$port/final\r\nContent-Length: 0\r\nConnection: close\r\n\r\n" else {
                    cookie = headers["cookie"].orEmpty(); auth = headers["authorization"].orEmpty()
                    "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                }
                socket.getOutputStream().write(response.toByteArray())
            } }
        }.apply { isDaemon = true; start() }
        try {
            val url = "http://127.0.0.1:$port/first"
            val http = MediaHttp({ target -> if (target.contains("127.0.0.1")) "private=1" else "scoped=2" }, true)
            http.open(url, RequestContext(url, "Test", credentialOrigin = origin(url), authorization = "Bearer secret")).use {
                assertEquals("ok", it.body.bufferedReader().readText())
            }
            assertEquals("scoped=2", cookie)
            assertEquals("", auth)
        } finally { server.close(); worker.join(2000) }
    }
    @Test fun productionTransportRejectsPrivateTargetsAndNonWebSchemes() {
        val http = MediaHttp()
        listOf("http://127.0.0.1/secret", "http://10.0.2.2/secret", "http://192.168.0.1/", "file:///private", "https://user:password@example.com/file").forEach { target ->
            assertThrows(target, IllegalArgumentException::class.java) { http.validateTarget(target) }
        }
    }
}
