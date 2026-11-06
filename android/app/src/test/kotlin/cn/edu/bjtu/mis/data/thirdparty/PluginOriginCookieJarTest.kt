package cn.edu.bjtu.mis.data.thirdparty

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class PluginOriginCookieJarTest {
    private val url = "https://api.example.com/login".toHttpUrl()
    @Test fun exactOriginIncludesSchemePortAndRejectsParentDomain() {
        val jar = PluginOriginCookieJar("https://api.example.com:443")
        jar.saveFromResponse(url, listOf(
            Cookie.parse(url, "session=fixture; Secure; Path=/")!!,
            Cookie.parse(url, "parent=fixture; Domain=example.com; Secure; Path=/")!!,
        ))
        assertEquals(listOf("session"), jar.loadForRequest(url).map { it.name })
        assertTrue(jar.loadForRequest("https://other.example.com/".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("http://api.example.com/".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://api.example.com:444/".toHttpUrl()).isEmpty())
        jar.clear()
        assertTrue(jar.loadForRequest(url).isEmpty())
    }
    @Test fun expiryDeletionPathAndQuotaAreEnforced() {
        val jar = PluginOriginCookieJar("https://api.example.com:443")
        jar.saveFromResponse(url, listOf(Cookie.parse(url, "session=fixture; Path=/private")!!))
        assertTrue(jar.loadForRequest(url).isEmpty())
        assertEquals(1, jar.loadForRequest("https://api.example.com/private".toHttpUrl()).size)
        jar.saveFromResponse(url, listOf(Cookie.parse(url, "session=; Max-Age=0; Path=/private")!!))
        assertTrue(jar.loadForRequest("https://api.example.com/private".toHttpUrl()).isEmpty())
        jar.saveFromResponse(url, (0..140).map { Cookie.parse(url, "c$it=fixture; Path=/")!! })
        assertEquals(128, jar.loadForRequest(url).size)
    }
}
