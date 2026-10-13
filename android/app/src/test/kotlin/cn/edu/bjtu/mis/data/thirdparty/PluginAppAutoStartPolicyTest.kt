package cn.edu.bjtu.mis.data.thirdparty

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginAppAutoStartPolicyTest {
    private fun service() = ThirdPartyService(
        serviceId = "test.plugin", manifest = ThirdPartyServiceManifest(
            capabilities = ThirdPartyCapabilityDeclaration(
                required = listOf("runtime.lifecycle@1"),
                optional = listOf("android.app.autoStart@1"),
            ),
        ), sourceUrl = "", githubOwner = "", githubRepo = "", defaultBranch = "",
        commitSha = "", packageDigestSha256 = "", installDir = "",
        grantedCapabilities = setOf("runtime.lifecycle@1", "android.app.autoStart@1"),
        allowedOrigins = emptyList(), enabled = true, needsReview = false,
        installedAt = "", updatedAt = "", publisherSubjectId = "publisher:1",
        runtimeProfile = "contract_v1", runtimeFloor = 4,
    )

    @Test fun autoStartRequiresAnExplicitOptionalGrantAndRunnableIdentity() {
        val plugin = service()
        assertTrue(PluginAppAutoStartPolicy.isEligible(plugin))
        assertFalse(PluginAppAutoStartPolicy.isEligible(plugin.copy(grantedCapabilities = setOf("runtime.lifecycle@1"))))
        assertFalse(PluginAppAutoStartPolicy.isEligible(plugin.copy(publisherSubjectId = "")))
        assertFalse(PluginAppAutoStartPolicy.isEligible(plugin.copy(enabled = false)))
        assertFalse(PluginAppAutoStartPolicy.isEligible(plugin.copy(needsReview = true)))
        assertFalse(PluginAppAutoStartPolicy.isEligible(plugin.copy(runtimeProfile = "legacy_v3_p0a")))
        assertFalse(PluginAppAutoStartPolicy.isEligible(plugin.copy(runtimeFloor = Int.MAX_VALUE)))
        assertFalse(PluginAppAutoStartPolicy.isEligible(plugin.copy(manifest = plugin.manifest.copy(
            capabilities = ThirdPartyCapabilityDeclaration(required = listOf("android.app.autoStart@1")),
        ))))
    }
}
