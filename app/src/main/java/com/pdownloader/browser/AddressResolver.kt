package com.pdownloader.browser

import java.net.URI
import java.net.URLEncoder

/** Only web URLs are accepted; executable and local-file schemes never reach WebView. */
object AddressResolver {
    fun resolve(input: String): String? {
        val value = input.trim()
        if (value.isEmpty()) return null
        if (value.startsWith("https://", true) || value.startsWith("http://", true)) {
            val uri = runCatching { URI(value) }.getOrNull()
            return value.takeIf { uri?.rawAuthority?.isNotBlank() == true && uri.rawUserInfo == null }
        }
        val host = value.substringBefore('/').substringBefore('?').substringBefore('#')
        val hasScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(value)
        val hostAndPort = Regex("^(localhost|[\\p{L}\\p{N}.-]+\\.[\\p{L}\\p{N}-]+|\\[[0-9a-fA-F:]+])(:[0-9]{1,5})?$")
        if (!value.any(Char::isWhitespace) && hostAndPort.matches(host)) return "https://$value"
        if (hasScheme) return null
        return "https://www.google.com/search?q=" + URLEncoder.encode(value, "UTF-8")
    }

    fun isWebUrl(url: String): Boolean =
        (url.startsWith("https://", true) || url.startsWith("http://", true)) && resolve(url) != null
}
