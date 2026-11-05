package cn.edu.bjtu.mis.data.thirdparty

import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class PluginNetworkProviderTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun formEncodingPreservesUnicodeSpacesPlusAndReservedCharacters() = runBlocking {
        MockWebServer().use { server ->
            val origin = testOrigin(server, "form.public.test")
            server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody("ok"))
            testProvider().execute(service(origin), request("$origin/form", method = "POST").copy(
                body = PluginNetworkBody.FormUrlEncoded(listOf("label" to "交大 +&=", "empty" to "")),
            ))
            val sent = server.takeRequest()
            assertEquals("application/x-www-form-urlencoded", sent.getHeader("Content-Type"))
            assertEquals("label=%E4%BA%A4%E5%A4%A7%20%2B%26%3D&empty=", sent.body.readUtf8())
        }
    }

    @Test
    fun cookieV2IsOptInPublisherIsolatedAndClearedOnStop() = runBlocking {
        MockWebServer().use { server ->
            val origin = testOrigin(server, "cookie.public.test")
            val base = service(origin)
            val service = base.copy(grantedCapabilities = base.grantedCapabilities + "network.request@2")
            val provider = testProvider()
            repeat(6) {
                server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody("ok").apply {
                    if (it == 0) setHeader("Set-Cookie", "session=fixture; Path=/")
                })
            }
            fun cookieRequest() = request("$origin/data").copy(capability = "network.request@2", includeCookies = true)
            provider.execute(service, cookieRequest())
            assertEquals(null, server.takeRequest().getHeader("Cookie"))
            provider.execute(service, cookieRequest())
            assertEquals("session=fixture", server.takeRequest().getHeader("Cookie"))
            provider.execute(service, request("$origin/data"))
            assertEquals(null, server.takeRequest().getHeader("Cookie"))
            provider.execute(service, cookieRequest().copy(includeCookies = false))
            assertEquals(null, server.takeRequest().getHeader("Cookie"))
            provider.execute(service.copy(publisherSubjectId = "publisher:other"), cookieRequest())
            assertEquals(null, server.takeRequest().getHeader("Cookie"))
            provider.clearSessions(service.publisherSubjectId, service.serviceId)
            provider.execute(service, cookieRequest())
            assertEquals(null, server.takeRequest().getHeader("Cookie"))
        }
    }

    @Test
    fun dnsPolicyRejectsIpv6UniqueLocalAndCarrierGradePrivateTargets() {
        assertTrue(isForbiddenPluginAddress(InetAddress.getByName("fd00::1")))
        assertTrue(isForbiddenPluginAddress(InetAddress.getByName("100.64.0.1")))
        assertTrue(isForbiddenPluginAddress(InetAddress.getByName("192.168.1.1")))
        assertFalse(isForbiddenPluginAddress(InetAddress.getByName("8.8.8.8")))
    }

    @Test
    fun rejectsForbiddenTargetsHeadersMethodsAndTimeoutBeforeConnecting() = runBlocking {
        val provider = PluginNetworkProvider(resourceStore())

        fun rejection(request: PluginNetworkRequest): PluginNetworkException =
            assertThrows(PluginNetworkException::class.java) {
                runBlocking { provider.execute(service("https://api.example.com"), request) }
            }

        assertEquals(
            "origin_denied",
            rejection(request("https://127.0.0.1/private")).code,
        )
        assertEquals(
            "origin_denied",
            rejection(request("https://mis.bjtu.edu.cn/profile")).code,
        )
        assertEquals(
            "origin_denied",
            rejection(request("http://api.example.com/plaintext")).code,
        )
        assertEquals(
            "invalid_request",
            rejection(
                request(
                    "https://api.example.com",
                    headers = mapOf("Cookie" to "session=host"),
                ),
            ).code,
        )
        assertEquals(
            "invalid_request",
            rejection(request("https://api.example.com", method = "TRACE")).code,
        )
        assertEquals(
            "invalid_request",
            rejection(request("https://api.example.com", timeoutMs = 60_001)).code,
        )
    }

    @Test
    fun followsDeclaredRedirectWithoutCookiesOrCrossOriginAuthorization() = runBlocking {
        MockWebServer().use { server ->
            val firstOrigin = testOrigin(server, "first.public.test")
            val secondOrigin = testOrigin(server, "second.public.test")
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "$secondOrigin/final"),
            )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setHeader("Set-Cookie", "remote=secret")
                    .setBody("""{"ok":true}"""),
            )
            val cookieJar = object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
                override fun loadForRequest(url: HttpUrl): List<Cookie> = listOf(
                    Cookie.Builder()
                        .name("host-session")
                        .value("secret")
                        .domain(url.host)
                        .build(),
                )
            }
            val provider = testProvider(
                OkHttpClient.Builder().cookieJar(cookieJar).build(),
            )
            val service = service(firstOrigin, secondOrigin)
            val progress = mutableListOf<PluginNetworkProgress>()

            val response = provider.execute(
                service,
                request(
                    "$firstOrigin/start",
                    headers = mapOf("Authorization" to "Bearer plugin-token"),
                ),
                progress::add,
            )

            assertEquals(200, response.status)
            assertEquals(1, response.redirects)
            assertEquals("""{"ok":true}""", response.json)
            assertFalse(response.headers.keys.any { it.equals("Set-Cookie", ignoreCase = true) })
            assertTrue(progress.any { it.phase == "response" })
            val first = server.takeRequest()
            val second = server.takeRequest()
            assertNull(first.getHeader("Cookie"))
            assertEquals("Bearer plugin-token", first.getHeader("Authorization"))
            assertNull(second.getHeader("Cookie"))
            assertNull(second.getHeader("Authorization"))
        }
    }

    @Test
    fun redirectToPrivateAddressIsBlockedBeforeSecondRequest() = runBlocking {
        MockWebServer().use { server ->
            val origin = testOrigin(server, "public.test")
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "http://127.0.0.1:${server.port}/private"),
            )
            val provider = testProvider()
            val error = assertThrows(PluginNetworkException::class.java) {
                runBlocking {
                    provider.execute(
                        service(origin, "http://127.0.0.1:${server.port}"),
                        request("$origin/start"),
                    )
                }
            }

            assertEquals("origin_denied", error.code)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun supportsTimeoutCancellationAndLargeResponseHandles() = runBlocking {
        MockWebServer().use { timeoutServer ->
            val origin = testOrigin(timeoutServer, "timeout.public.test")
            timeoutServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val provider = testProvider()
            val error = assertThrows(PluginNetworkException::class.java) {
                runBlocking {
                    provider.execute(
                        service(origin),
                        request("$origin/timeout", timeoutMs = 100),
                    )
                }
            }
            assertEquals("network_timeout", error.code)
        }

        MockWebServer().use { cancelServer ->
            val origin = testOrigin(cancelServer, "cancel.public.test")
            cancelServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val provider = testProvider()
            val service = service(origin)
            val pending = async(Dispatchers.IO) {
                runCatching {
                    provider.execute(service, request("$origin/cancel", requestId = "cancel-1"))
                }
            }
            assertNotNull(cancelServer.takeRequest(5, TimeUnit.SECONDS))
            assertTrue(provider.cancel(service, "cancel-1"))
            val error = pending.await().exceptionOrNull() as PluginNetworkException
            assertEquals("user_cancelled", error.code)
        }

        MockWebServer().use { largeServer ->
            val origin = testOrigin(largeServer, "large.public.test")
            largeServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/plain")
                    .setBody("x".repeat(PLUGIN_NETWORK_INLINE_BYTES + 1)),
            )
            val provider = testProvider()
            val response = provider.execute(
                service(origin),
                request("$origin/large", requestId = "large-1"),
            )
            assertNull(response.text)
            assertNotNull(response.resource)
            assertEquals(
                PLUGIN_NETWORK_INLINE_BYTES + 1L,
                response.resource?.size,
            )
        }
    }

    @Test
    fun imageResponsesStayNativeResourceHandlesAndPromoteWithoutBase64() = runBlocking {
        MockWebServer().use { server ->
            val origin = testOrigin(server, "images.public.test")
            val imageBytes = byteArrayOf(
                0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                1, 2, 3, 4,
            )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "image/png")
                    .setBody(okio.Buffer().write(imageBytes)),
            )
            val resources = resourceStore()
            val service = service(origin)
            val response = testProvider(resources = resources).execute(
                service,
                request("$origin/avatar.png", requestId = "image-1"),
            )

            assertNull(response.text)
            assertNull(response.json)
            val handle = requireNotNull(response.resource)
            assertEquals("image/png", handle.mediaType)
            val promoted = resources.promoteCache(
                ThirdPartyKvNamespace(service.publisherSubjectId, service.serviceId),
                handle.handle,
                "avatars/current",
            )
            assertEquals(handle.handle, promoted.handle)
            assertArrayEquals(
                imageBytes,
                resources.open(
                    ThirdPartyKvNamespace(service.publisherSubjectId, service.serviceId),
                    promoted.handle,
                ).input.use { it.readBytes() },
            )
        }
    }

    @Test
    fun requestBodiesAcceptBlobHandlesButRejectCacheHandles() = runBlocking {
        val resources = resourceStore()
        val service = service("https://api.example.com")
        val namespace = ThirdPartyKvNamespace(service.publisherSubjectId, service.serviceId)
        val cache = resources.putCache(
            namespace,
            "not-a-blob",
            ByteArrayInputStream("cached".toByteArray()),
            "text/plain",
        )
        val provider = PluginNetworkProvider.forTests(
            resourceStore = resources,
            resolver = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    listOf(InetAddress.getByName("203.0.113.10"))
            },
        )

        val ungranted = assertThrows(PluginNetworkException::class.java) {
            runBlocking {
                provider.execute(
                    service,
                    PluginNetworkRequest(
                        requestId = "cache-body",
                        method = "POST",
                        url = "https://api.example.com/upload",
                        body = PluginNetworkBody.Blob(cache.handle),
                    ),
                )
            }
        }
        val wrongKind = assertThrows(PluginNetworkException::class.java) {
            runBlocking {
                provider.execute(
                    service.copy(
                        grantedCapabilities =
                            service.grantedCapabilities + "storage.blob@1",
                    ),
                    PluginNetworkRequest(
                        requestId = "cache-body-with-blob-grant",
                        method = "POST",
                        url = "https://api.example.com/upload",
                        body = PluginNetworkBody.Blob(cache.handle),
                    ),
                )
            }
        }

        assertEquals("permission_denied", ungranted.code)
        assertEquals("invalid_request", wrongKind.code)
    }

    private fun resourceStore(): ThirdPartyResourceStore =
        FileThirdPartyResourceStore(
            temp.newFolder("network-resources-${System.nanoTime()}"),
            NetworkTestCipher,
            limits = ThirdPartyResourceLimits(safetyBytes = 0),
        )

    private fun testProvider(
        baseClient: OkHttpClient = OkHttpClient(),
        resources: ThirdPartyResourceStore = resourceStore(),
    ): PluginNetworkProvider =
        PluginNetworkProvider.forTests(
            resourceStore = resources,
            baseClient = baseClient,
            resolver = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    listOf(InetAddress.getByName("127.0.0.1"))
            },
        )

    private fun testOrigin(server: MockWebServer, host: String): String =
        "http://$host:${server.port}"

    private fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = PLUGIN_NETWORK_DEFAULT_TIMEOUT_MS,
        requestId: String = "request-${System.nanoTime()}",
    ) = PluginNetworkRequest(
        requestId = requestId,
        method = method,
        url = url,
        headers = headers,
        timeoutMs = timeoutMs,
    )

    private fun service(vararg origins: String): ThirdPartyService {
        val manifest = ThirdPartyServiceManifest(
            schemaVersion = 3,
            id = "bjtu.network",
            name = "Network",
            version = "1.0.0",
            entrypoint = "index.html",
            icon = "icon.svg",
            capabilities = ThirdPartyCapabilityDeclaration(
                required = listOf("runtime.lifecycle@1", "network.request@1"),
            ),
            origins = ThirdPartyOriginDeclaration(connect = origins.toList()),
        )
        return ThirdPartyService(
            serviceId = manifest.id,
            manifest = manifest,
            sourceUrl = "https://github.com/alice/network",
            githubOwner = "alice",
            githubRepo = "network",
            defaultBranch = "main",
            commitSha = "abcdef1234567",
            packageDigestSha256 = "a".repeat(64),
            installDir = temp.root.absolutePath,
            grantedCapabilities = manifest.requiredCapabilities.toSet(),
            allowedOrigins = manifest.remoteOrigins,
            enabled = true,
            needsReview = false,
            installedAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z",
            publisherSubjectId = "github-owner:12345",
            runtimeProfile = ThirdPartyRuntimeProfile.ContractV1.value,
            runtimeFloor = 2,
        )
    }
}

private object NetworkTestCipher : ThirdPartyKvCipher {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        plaintext.copyOf()

    override fun decrypt(payload: ByteArray, associatedData: ByteArray): ByteArray =
        payload.copyOf()
}
