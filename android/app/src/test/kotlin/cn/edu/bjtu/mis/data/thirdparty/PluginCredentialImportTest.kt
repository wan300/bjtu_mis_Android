package cn.edu.bjtu.mis.data.thirdparty

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class PluginCredentialImportTest {
    @Test fun onlyApplicationSuppliedWhitelistedStructureCanPass() {
        val credentials = buildJsonObject { put("identity", "fixture-user"); put("refreshToken", "fixture-token") }
        assertEquals(credentials, PluginCredentialImport.validate("com.example.app", credentials, "cn.edu.bjtu.mis"))
        assertThrows(IllegalArgumentException::class.java) {
            PluginCredentialImport.validate("cn.edu.bjtu.mis", credentials, "cn.edu.bjtu.mis")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginCredentialImport.validate("com.example.app", buildJsonObject { put("packet", "not permitted") }, "host.app")
        }
    }
    @Test fun campusCookieAndOversizedPayloadAreRejected() {
        val credentials = buildJsonObject { put("cookies", buildJsonArray { add(buildJsonObject {
            put("name", "fixture"); put("value", "fixture"); put("domain", ".mis.bjtu.edu.cn")
        }) }) }
        assertThrows(IllegalArgumentException::class.java) { PluginCredentialImport.validate("com.example.app", credentials, "host.app") }
        assertThrows(IllegalArgumentException::class.java) {
            PluginCredentialImport.validate("com.example.app", buildJsonObject { put("identity", "x".repeat(262_145)) }, "host.app")
        }
    }
    @Test fun ipv4ParsingNeverAcceptsHostnameOrArbitraryEndpointBody() {
        assertEquals("8.8.8.8", pluginIpv4(" 8.8.8.8\n"))
        listOf("256.1.1.1", "01.2.3.4", "api.example.com", "{\"ip\":\"8.8.8.8\"}", "1.2.3", "1.2.3.4\nmalicious").forEach {
            assertNull(pluginIpv4(it))
        }
        assertEquals("2001:4860:4860::8888", pluginPublicIp("2001:4860:4860::8888"))
        assertNull(pluginPublicIp("::1"))
        assertNull(pluginPublicIp("127.0.0.1"))
        assertNull(pluginPublicIp("api.example.com"))
    }
}
