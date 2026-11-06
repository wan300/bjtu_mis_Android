package cn.edu.bjtu.mis.data.thirdparty

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

internal object PluginNetworkSessions {
    var provider: PluginNetworkProvider? = null
}

/** An in-memory session for exactly one origin. Parent/sibling domain sharing is never allowed. */
internal class PluginOriginCookieJar(private val origin: String) : CookieJar {
    private val cookies = linkedMapOf<Pair<String, String>, Cookie>()
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (url.origin() != origin) return
        this.cookies.entries.removeIf { it.value.expiresAt <= System.currentTimeMillis() }
        cookies.filter { it.domain == url.host && it.name.length <= 256 && it.value.length <= 8192 }.forEach {
            val key = it.name to it.path
            if (it.expiresAt <= System.currentTimeMillis()) this.cookies.remove(key)
            else if (this.cookies.size < 128 || key in this.cookies) this.cookies[key] = it
        }
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (url.origin() != origin) return emptyList()
        cookies.entries.removeIf { it.value.expiresAt <= System.currentTimeMillis() }
        return cookies.values.filter { it.matches(url) }
    }
    @Synchronized fun clear() { cookies.clear() }
}

private fun HttpUrl.origin() = "$scheme://$host:$port"
