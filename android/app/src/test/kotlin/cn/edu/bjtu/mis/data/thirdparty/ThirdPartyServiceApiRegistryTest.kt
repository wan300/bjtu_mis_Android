package cn.edu.bjtu.mis.data.thirdparty

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThirdPartyServiceApiRegistryTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun everyGeneratedCapabilityHasAnAndroidProvider() {
        assertEquals(
            ThirdPartyCapabilityRegistry.capabilities
                .filterNot { it.optionalOnly }
                .map { it.id }
                .toSet(),
            registry().registeredCapabilityIds(),
        )
    }

    @Test
    fun runtimeFailsClosedWhenPersistedRequiredGrantStateIsIncomplete() {
        val inconsistent = service(
            required = setOf("runtime.lifecycle@1", "identity.profile@1"),
            granted = setOf("runtime.lifecycle@1"),
        )

        assertFalse(inconsistent.canRun)
    }

    @Test
    fun handshakeUsesProtocolV2AndGeneratedCapabilities() = runBlocking {
        val service = service(
            required = setOf("runtime.lifecycle@1"),
            granted = setOf("runtime.lifecycle@1"),
        )
        val response = registry().invoke(
            service = service,
            capability = "runtime.lifecycle@1",
            method = "handshake",
            params = buildJsonObject { put("sdkVersion", "0.2.0") },
            confirmer = allowAll,
            currentPageUrl = ThirdPartyServiceSandbox.entrypointUrlFor(service),
            runtimeEnvironment = modernWebViewEnvironment(),
        )

        assertTrue(response["ok"]!!.jsonPrimitive.content.toBoolean())
        val result = response["result"]!!.jsonObject
        assertEquals(2, result["protocolVersion"]!!.jsonPrimitive.content.toInt())
        assertEquals("contract_v1", result["contractProfile"]!!.jsonPrimitive.content)
        assertEquals(4, result["runtimeFloor"]!!.jsonPrimitive.content.toInt())
        assertEquals(
            setOf(
                "protocolVersion",
                "contractProfile",
                "runtimeFloor",
                "availableCapabilities",
                "binaryTransports",
                "preferredBinaryTransport",
            ),
            result.keys,
        )
        assertEquals(
            "[\"arraybuffer\",\"base64url-chunks-v1\"]",
            result["binaryTransports"].toString(),
        )
        assertEquals(
            "arraybuffer",
            result["preferredBinaryTransport"]!!.jsonPrimitive.content,
        )
        assertTrue(
            ThirdPartyCapabilityRegistry.validateResponse(
                "runtime.lifecycle@1",
                "handshake",
                result,
            ).isEmpty(),
        )
    }

    private fun modernWebViewEnvironment() = PluginWebViewRuntimeEnvironment(
        providerPackageName = "com.android.webview",
        providerVersionName = "130.0.0.0",
        documentStartScriptSupported = true,
        webMessageListenerSupported = true,
        webMessageArrayBufferSupported = true,
    )

    @Test
    fun handshakeOffersCompatibilityModeWithoutArrayBuffer() = runBlocking {
        val service = service(
            required = setOf("runtime.lifecycle@1"),
            granted = setOf("runtime.lifecycle@1"),
        )
        val response = registry().invoke(
            service = service,
            capability = "runtime.lifecycle@1",
            method = "handshake",
            params = buildJsonObject { put("sdkVersion", "0.2.0") },
            confirmer = allowAll,
            currentPageUrl = ThirdPartyServiceSandbox.entrypointUrlFor(service),
            runtimeEnvironment = modernWebViewEnvironment().copy(
                providerPackageName = "com.huawei.webview",
                providerVersionName = "114.0.5.302",
                webMessageArrayBufferSupported = false,
            ),
        )

        val result = response["result"]!!.jsonObject
        assertEquals(
            "[\"base64url-chunks-v1\"]",
            result["binaryTransports"].toString(),
        )
        assertEquals(
            "base64url-chunks-v1",
            result["preferredBinaryTransport"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun generatedResponseValidatorRejectsDriftInCommandReceipt() {
        val invalid = buildJsonObject {
            put("id", 42)
        }
        val valid = buildJsonObject {
            put("receiptId", "receipt-1")
            put("idempotencyKey", "request-1")
            put("completedAt", "2026-07-29T00:00:00Z")
            put("result", buildJsonObject { put("id", 42) })
        }

        assertTrue(
            ThirdPartyCapabilityRegistry.validateResponse(
                "academic.userCourses.command@1",
                "save",
                invalid,
            ).isNotEmpty(),
        )
        assertTrue(
            ThirdPartyCapabilityRegistry.validateResponse(
                "academic.userCourses.command@1",
                "save",
                valid,
            ).isEmpty(),
        )
    }

    @Test
    fun generatedValidatorEnforcesEnumsLimitsAndNestedSchemas() {
        val requestErrors = ThirdPartyCapabilityRegistry.validateRequest(
            "network.request@1",
            "request",
            buildJsonObject {
                put("url", "https://api.example.com/data")
                put("method", "TRACE")
                put("timeoutMs", 60_001)
            },
        )
        val responseErrors = ThirdPartyCapabilityRegistry.validateResponse(
            "network.request@1",
            "request",
            buildJsonObject {
                put("status", 200)
                put("headers", buildJsonObject { })
                put("bodyType", "resource")
                put("finalUrl", "https://api.example.com/data")
                put("redirects", 0)
                put("resource", buildJsonObject { put("handle", "blob-deadbeef") })
            },
        )

        assertTrue(requestErrors.any { it.contains("enum values") })
        assertTrue(requestErrors.any { it.contains("at most 60000") })
        assertTrue(responseErrors.any { it.contains("Response.resource missing required field: size") })
        assertTrue(responseErrors.any { it.contains("Response.resource missing required field: url") })
    }

    @Test
    fun generatedLifecycleEventsRejectDriftAndMarkBackAsAcknowledged() {
        assertTrue(
            ThirdPartyCapabilityRegistry.validateEvent(
                "runtime.lifecycle@1",
                "theme",
                buildJsonObject {
                    put("colorScheme", "dark")
                    put("reducedMotion", false)
                    put("highContrast", false)
                },
            ).isEmpty(),
        )
        assertTrue(
            ThirdPartyCapabilityRegistry.validateEvent(
                "runtime.lifecycle@1",
                "themeChanged",
                buildJsonObject { put("theme", "dark") },
            ).isNotEmpty(),
        )
        assertTrue(
            ThirdPartyCapabilityRegistry.eventRequiresAcknowledgement(
                "runtime.lifecycle@1",
                "back",
            ),
        )
    }

    @Test
    fun generatedCapabilityDeadlineReturnsRequestTimeout() = runBlocking {
        val service = service(
            required = setOf("runtime.lifecycle@1", "network.request@1"),
            granted = setOf("runtime.lifecycle@1", "network.request@1"),
        )
        val registry = ThirdPartyServiceApiRegistry(
            moduleRepository = null,
            mailRepository = null,
            providerOverrides = listOf(
                LambdaPluginCapabilityProvider(setOf("network.request@1")) {
                    delay(100)
                    buildJsonObject {
                        put("status", 200)
                        put("headers", buildJsonObject { })
                        put("bodyType", "text")
                        put("body", "late")
                        put("finalUrl", "https://api.example.com/data")
                        put("redirects", 0)
                    }
                },
            ),
        )

        val response = registry.invoke(
            service = service,
            capability = "network.request@1",
            method = "request",
            params = buildJsonObject {
                put("url", "https://api.example.com/data")
                put("timeoutMs", 10)
            },
            confirmer = allowAll,
            currentPageUrl = ThirdPartyServiceSandbox.entrypointUrlFor(service),
        )

        assertEquals(
            "request_timeout",
            response["error"]!!.jsonObject["code"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun optionalCapabilityIsUnavailableUntilExplicitlyGranted() = runBlocking {
        val service = service(
            required = setOf("runtime.lifecycle@1"),
            optional = setOf("configuration.read@1"),
            granted = setOf("runtime.lifecycle@1"),
        )
        val response = registry(configuration = "secret").invoke(
            service = service,
            capability = "configuration.read@1",
            method = "get",
            params = buildJsonObject { put("key", "TOKEN") },
            confirmer = allowAll,
            currentPageUrl = ThirdPartyServiceSandbox.entrypointUrlFor(service),
        )

        assertFalse(response["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            "permission_denied",
            response["error"]!!.jsonObject["code"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun directNavigationAlsoRequiresTheOptionalCapabilityGrant() {
        val declared = service(
            required = setOf("runtime.lifecycle@1"),
            optional = setOf("navigation.external@1"),
            granted = setOf("runtime.lifecycle@1"),
        ).let { service ->
            service.copy(
                manifest = service.manifest.copy(
                    origins = ThirdPartyOriginDeclaration(
                        navigation = listOf("https://navigate.example.com"),
                    ),
                ),
            )
        }
        val opened = mutableListOf<String>()

        assertFalse(
            PluginNavigationController(declared, opened::add)
                .openFromCapability("https://navigate.example.com/page"),
        )
        assertTrue(
            PluginNavigationController(
                declared.copy(
                    grantedCapabilities =
                        declared.grantedCapabilities + "navigation.external@1",
                ),
                opened::add,
            ).openFromCapability("https://navigate.example.com/page"),
        )
        assertTrue(
            PluginNavigationController(
                declared.copy(
                    grantedCapabilities =
                        declared.grantedCapabilities + "navigation.external@1",
                ),
                opened::add,
            ).openFromCapability("https://navigate.example.com:443/default-port"),
        )
        assertEquals(
            listOf(
                "https://navigate.example.com/page",
                "https://navigate.example.com:443/default-port",
            ),
            opened,
        )
    }

    @Test
    fun reviewDefaultsOptionalCapabilitiesOffAndPreservesEarlierGrants() {
        val service = service(
            required = setOf("runtime.lifecycle@1"),
            optional = setOf("navigation.external@1", "cache.resource@1"),
            granted = setOf("runtime.lifecycle@1", "cache.resource@1"),
        )

        assertEquals(
            setOf("runtime.lifecycle@1", "cache.resource@1"),
            service.reviewCapabilitySelection,
        )
    }

    @Test
    fun kvSetUsesRevisionCasAndDoesNotLoseUpdates() = runBlocking {
        val kv = FileThirdPartyKvStore(temp.newFolder("kv"), PassThroughCipher)
        val service = service(
            required = setOf("runtime.lifecycle@1", "storage.kv@2"),
            granted = setOf("runtime.lifecycle@1", "storage.kv@2"),
        )
        val registry = registry(kv = kv)
        val origin = ThirdPartyServiceSandbox.entrypointUrlFor(service)

        val first = registry.invoke(
            service,
            "storage.kv@2",
            "set",
            buildJsonObject {
                put("key", "counter")
                put("value", 1)
            },
            confirmer = allowAll,
            currentPageUrl = origin,
        )
        val revision = first["result"]!!.jsonObject["revision"]!!.jsonPrimitive.content.toLong()

        val second = registry.invoke(
            service,
            "storage.kv@2",
            "set",
            buildJsonObject {
                put("key", "counter")
                put("value", 2)
                put("ifRevision", revision)
            },
            confirmer = allowAll,
            currentPageUrl = origin,
        )
        assertTrue(second["ok"]!!.jsonPrimitive.content.toBoolean())

        val conflict = registry.invoke(
            service,
            "storage.kv@2",
            "set",
            buildJsonObject {
                put("key", "counter")
                put("value", 3)
                put("ifRevision", revision)
            },
            confirmer = allowAll,
            currentPageUrl = origin,
        )
        assertEquals(
            "idempotency_conflict",
            conflict["error"]!!.jsonObject["code"]!!.jsonPrimitive.content,
        )
        val stored = kv.get(
            ThirdPartyKvNamespace(service.publisherSubjectId, service.serviceId),
            "counter",
        )
        assertEquals(JsonPrimitive(2), stored)
    }

    @Test
    fun bridgeCallsFromRemoteFramesFailClosed() = runBlocking {
        val service = service(
            required = setOf("runtime.lifecycle@1"),
            granted = setOf("runtime.lifecycle@1"),
        )
        val response = registry().invoke(
            service,
            "runtime.lifecycle@1",
            "ready",
            buildJsonObject { },
            confirmer = allowAll,
            currentPageUrl = "https://example.com/frame",
        )

        assertEquals(
            "origin_denied",
            response["error"]!!.jsonObject["code"]!!.jsonPrimitive.content,
        )
    }

    private fun registry(
        kv: ThirdPartyKvStore? = null,
        configuration: String? = null,
    ) = ThirdPartyServiceApiRegistry(
        moduleRepository = null,
        mailRepository = null,
        kvStore = kv,
        configurationReader = { _, _ -> configuration },
    )

    private fun service(
        required: Set<String>,
        optional: Set<String> = emptySet(),
        granted: Set<String>,
    ): ThirdPartyService {
        val manifest = ThirdPartyServiceManifest(
            schemaVersion = 3,
            id = "bjtu.demo",
            name = "Demo",
            version = "1.0.0",
            entrypoint = "index.html",
            icon = "icon.svg",
            capabilities = ThirdPartyCapabilityDeclaration(
                required = required.toList(),
                optional = optional.toList(),
            ),
            dataSchemaVersion = if ("storage.kv@2" in required + optional) 1 else 0,
            configuration = if ("configuration.read@1" in required + optional) {
                listOf(
                    ThirdPartyConfigurationDefinition(
                        key = "TOKEN",
                        label = "Token",
                        type = "secret",
                        required = true,
                    ),
                )
            } else {
                emptyList()
            },
        )
        return ThirdPartyService(
            serviceId = manifest.id,
            manifest = manifest,
            sourceUrl = "https://github.com/alice/demo",
            githubOwner = "alice",
            githubRepo = "demo",
            defaultBranch = "main",
            commitSha = "abcdef1234567",
            packageDigestSha256 = "a".repeat(64),
            installDir = temp.root.absolutePath,
            grantedCapabilities = granted,
            allowedOrigins = manifest.remoteOrigins,
            enabled = true,
            needsReview = false,
            installedAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z",
            publisherSubjectId = "github-owner:12345",
            dataSchemaVersion = manifest.dataSchemaVersion,
            runtimeProfile = ThirdPartyRuntimeProfile.ContractV1.value,
            runtimeFloor = 2,
            compatibilityState = ThirdPartyCompatibilityState.Compatible.value,
        )
    }

    private val allowAll = ThirdPartySensitiveActionConfirmer { _, _ -> true }
}

private object PassThroughCipher : ThirdPartyKvCipher {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        plaintext

    override fun decrypt(payload: ByteArray, associatedData: ByteArray): ByteArray =
        payload
}
