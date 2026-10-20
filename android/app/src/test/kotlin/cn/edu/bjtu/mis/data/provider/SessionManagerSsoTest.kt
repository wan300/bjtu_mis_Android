package cn.edu.bjtu.mis.data.provider

import cn.edu.bjtu.mis.data.captcha.CaptchaAnswerSolver
import cn.edu.bjtu.mis.data.network.AppCookieJar
import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import cn.edu.bjtu.mis.data.security.CredentialStore
import cn.edu.bjtu.mis.data.security.LoginCredentials
import cn.edu.bjtu.mis.data.security.SessionCookieStore
import cn.edu.bjtu.mis.model.AutoLoginStatus
import cn.edu.bjtu.mis.model.SessionState
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.bouncycastle.asn1.gm.GMNamedCurves
import org.bouncycastle.crypto.engines.SM2Engine
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.net.URLDecoder
import java.util.Base64

class SessionManagerSsoTest {
    private val curve = GMNamedCurves.getByName("sm2p256v1")
    private val publicKey = Base64.getEncoder().encodeToString(curve.g.getEncoded(false))

    @Test
    fun newSsoLoginSkipsCaptchaAndEncryptsPassword() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/o/authorize/?state=sample"))
            server.enqueue(MockResponse().setBody(page()))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/home/"))
            repeat(5) { server.enqueue(MockResponse().setBody("authenticated service")) }
            val stores = Stores()
            val manager = manager(server, stores)

            val result = manager.loginAuto("student", "sample-password")

            assertEquals(AutoLoginStatus.Ready, result.status)
            assertEquals(SessionState.Ready, result.session?.state)
            assertEquals(LoginCredentials("student", "sample-password"), stores.credentials)
            val requests = List(server.requestCount) { server.takeRequest() }
            assertFalse(requests.any { it.path.orEmpty().contains("captcha") })
            val submit = requests.single { it.method == "POST" }
            assertEquals("/sso/login", submit.path)
            assertEquals(server.url("/").toString().trimEnd('/'), submit.getHeader("Origin"))
            val fields = decodeForm(submit.body.readUtf8())
            assertEquals("test-flow", fields["flowId"])
            assertEquals("test-state", fields["state"])
            assertEquals("student", fields["username"])
            assertEquals("username_password", fields["loginType"])
            assertEquals("", fields["captcha"])
            assertNotEquals("sample-password", fields["password"])
            val cipher = byteArrayOf(4) + Base64.getDecoder().decode(fields.getValue("password"))
            val engine = SM2Engine(SM2Engine.Mode.C1C3C2)
            engine.init(false, ECPrivateKeyParameters(BigInteger.ONE, ECDomainParameters(curve.curve, curve.g, curve.n, curve.h)))
            assertEquals("sample-password", engine.processBlock(cipher, 0, cipher.size).toString(Charsets.UTF_8))
        }
    }

    @Test
    fun ssoLoginHtmlAtHomeIsExpiredInsteadOfReady() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(page()))
            val status = manager(server, Stores(cookies = "[]")).validateSession()
            assertEquals(SessionState.Expired, status.state)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun badPasswordStopsAfterOneSubmissionAndDoesNotPersist() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(page()))
            server.enqueue(MockResponse().setBody(page(error = "{\"code\":40400}")))
            val stores = Stores()
            val result = manager(server, stores).loginAuto("student", "sample-password")
            assertEquals(AutoLoginStatus.AutoFailed, result.status)
            assertTrue(result.message.orEmpty().contains("用户名或密码错误"))
            assertEquals(2, server.requestCount)
            assertNull(stores.credentials)
            assertNull(stores.cookies)
        }
    }

    @Test
    fun failedFinalSessionValidationCannotReportLoginSuccess() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(page()))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/home/"))
            repeat(3) { server.enqueue(MockResponse().setBody("authenticated service")) }
            server.enqueue(MockResponse().setBody(page()))
            val stores = Stores()
            val result = manager(server, stores).loginAuto("student", "sample-password")
            assertEquals(AutoLoginStatus.AutoFailed, result.status)
            assertNull(stores.credentials)
            assertEquals(6, server.requestCount)
        }
    }

    @Test
    fun requiredCaptchaUsesJsonApiAndRefreshKeepsSsoFlow() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(page(error = "{\"code\":40700,\"data\":{\"captchaType\":\"penggle.image\"}}")))
            server.enqueue(captcha("challenge-one"))
            server.enqueue(captcha("challenge-two"))
            server.enqueue(MockResponse().setBody(page(error = "{\"code\":40400}")))
            val manager = manager(server)

            val result = manager.loginAuto("student", "sample-password")
            assertEquals(AutoLoginStatus.ManualRequired, result.status)
            assertTrue(result.captcha?.imageDataUrl.orEmpty().startsWith("data:image/png;base64,"))
            manager.fetchInlineLoginCaptcha()
            assertThrows(SessionExpiredException::class.java) {
                runBlocking { manager.loginInline("student", "sample-password", "abcde") }
            }
            val requests = List(server.requestCount) { server.takeRequest() }
            assertEquals(listOf("/home/", "/api/captcha/getCaptcha", "/api/captcha/getCaptcha", "/sso/login"), requests.map { it.path })
            val submitted = decodeForm(requests.last().body.readUtf8())
            assertEquals("test-flow", submitted["flowId"])
            assertTrue(submitted["captcha"].orEmpty().contains("challenge-two"))
            assertFalse(submitted["captcha"].orEmpty().contains("challenge-one"))
        }
    }

    @Test
    fun captchaRequiredAfterPasswordFailureKeepsReturnedFlow() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(page()))
            server.enqueue(MockResponse().setBody(page(error = "{\"code\":40700,\"data\":{\"captchaType\":\"penggle.image\"}}").replace("test-flow", "next-flow")))
            server.enqueue(captcha("challenge-new"))
            val result = manager(server).loginAuto("student", "sample-password")
            assertEquals(AutoLoginStatus.ManualRequired, result.status)
            assertEquals(3, server.requestCount)
            assertFalse(result.message.orEmpty().contains("似乎已登录"))
        }
    }

    @Test
    fun failedCaptchaRefreshInvalidatesOldChallenge() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(page()))
            server.enqueue(captcha("challenge-old"))
            server.enqueue(MockResponse().setResponseCode(503))
            val manager = manager(server)
            manager.fetchInlineLoginCaptcha()
            assertThrows(java.io.IOException::class.java) { runBlocking { manager.fetchInlineLoginCaptcha() } }
            assertThrows(SessionExpiredException::class.java) { runBlocking { manager.loginInline("student", "sample-password", "abcde") } }
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun authenticationHtmlCannotBeAcceptedAsCaptchaImage() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(page()))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>login required</html>"))
            assertThrows(Exception::class.java) { runBlocking { manager(server).fetchInlineLoginCaptcha() } }
        }
    }

    private fun manager(server: MockWebServer, stores: Stores = Stores()): SessionManager {
        val base = server.url("/").toString().trimEnd('/')
        val jar = AppCookieJar()
        return SessionManager(
            cookieStore = stores, credentialStore = stores.credentialStore,
            cookieJar = jar, httpClient = BjtuHttpClient(jar),
            captchaSolver = CaptchaAnswerSolver { error("SSO must not call legacy math OCR") },
            endpoints = SessionEndpoints(
                misHomeUrl = "$base/home/", misAaBridgeUrl = "$base/aa/bridge/",
                aaTimetableUrl = "$base/aa/timetable/", bksyVeBridgeUrl = "$base/ve/bridge/",
                casOrigin = base, misReferer = "$base/", bksyReferer = "$base/",
            ),
        )
    }

    private fun page(error: String = "null"): String = javaClass.classLoader!!
        .getResource("fixtures/sso_login.html")!!.readText()
        .replace("TEST_SM2_PUBLIC_KEY", publicKey)
        .replace("var error = null;", "var error = $error;")

    private fun captcha(id: String) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody("""{"code":20000,"data":{"captchaId":"$id","captchaImg":"aW1hZ2U="}}""")

    private fun decodeForm(body: String) = body.split('&').associate {
        val parts = it.split('=', limit = 2)
        URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
    }

    private class Stores(var cookies: String? = null) : SessionCookieStore {
        var credentials: LoginCredentials? = null
        override fun save(plainText: String) { cookies = plainText }
        override fun load(): String? = cookies
        override fun clear() { cookies = null }
        val credentialStore = object : CredentialStore {
            override fun save(credentials: LoginCredentials) { this@Stores.credentials = credentials }
            override fun load(): LoginCredentials? = credentials
            override fun clear() { credentials = null }
        }
    }
}
