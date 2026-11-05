package cn.edu.bjtu.mis.data.thirdparty

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/** Only application-supplied structured data; never consult a CookieJar, account or credential store. */
internal object PluginCredentialImport {
    fun validate(packageName: String, credentials: JsonObject, hostPackage: String): JsonObject {
        require(packageName.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")))
        require(packageName != hostPackage) { "Host campus credentials cannot be imported" }
        require(credentials.isNotEmpty()) { "No structured credentials were supplied" }
        require(credentials.keys.all { it in setOf("identity", "umid", "userAgent", "cookies", "refreshToken") })
        require(credentials.toString().toByteArray(Charsets.UTF_8).size <= 262_144) { "Credential import exceeds 256 KiB" }
        (credentials["cookies"] as? JsonArray)?.forEach { value ->
            val cookie = value as? JsonObject ?: throw IllegalArgumentException("Invalid cookie")
            val domain = cookie.getValue("domain").jsonPrimitive.content.trimStart('.').lowercase(Locale.US)
            require(domain.matches(Regex("^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?$"))) { "Invalid cookie domain" }
            require(domain != "bjtu.edu.cn" && !domain.endsWith(".bjtu.edu.cn")) { "Campus cookies cannot be imported" }
        }
        return credentials
    }
}

/** A numeric-only IPv4 parser, with no hostname resolution or provider-specific JSON interpretation. */
internal fun pluginIpv4(value: String): String? {
    val text = value.trim()
    val pieces = text.split('.')
    return text.takeIf { pieces.size == 4 && pieces.all { part ->
        part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' } &&
            (part == "0" || !part.startsWith('0')) && part.toIntOrNull() in 0..255
    } }
}

internal fun pluginPublicIp(value: String): String? {
    val text = value.trim()
    if (text.length > 45) return null
    val numeric = pluginIpv4(text) ?: text.takeIf {
        ':' in it && it.matches(Regex("^[0-9a-fA-F:]+$"))
    } ?: return null
    // IPv6 syntax includes ':' and cannot trigger hostname resolution. No zone IDs or hostnames.
    val address = runCatching { java.net.InetAddress.getByName(numeric) }.getOrNull() ?: return null
    return numeric.takeUnless { isForbiddenPluginAddress(address) }
}
