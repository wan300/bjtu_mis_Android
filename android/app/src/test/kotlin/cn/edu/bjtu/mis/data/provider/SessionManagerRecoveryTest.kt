package cn.edu.bjtu.mis.data.provider

import cn.edu.bjtu.mis.data.captcha.CaptchaAnswerSolver
import cn.edu.bjtu.mis.data.captcha.CaptchaSolveException
import cn.edu.bjtu.mis.data.captcha.CaptchaSolveResult
import cn.edu.bjtu.mis.data.network.AppCookieJar
import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import cn.edu.bjtu.mis.data.security.CredentialStore
import cn.edu.bjtu.mis.data.security.LoginCredentials
import cn.edu.bjtu.mis.data.security.SessionCookieStore
import cn.edu.bjtu.mis.model.AutoLoginStatus
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerRecoveryTest {
    @Test fun logoutRunsHostCleanupAndClearsCredentialsEvenWhenCleanupFails() {
        val cookies = MemoryCookieStore("[]")
        val credentials = MemoryCredentialStore(LoginCredentials("fixture-user", "fixture-password"))
        val jar = AppCookieJar()
        var invoked = false
        val manager = SessionManager(cookies, credentials, jar, BjtuHttpClient(jar),
            FakeCaptchaSolver { CaptchaSolveResult(expression = "1+2=", answer = "3") },
            onLogout = { invoked = true; throw IllegalStateException("Synthetic cleanup failure") })
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { manager.logout() }
        assertTrue(invoked)
        assertEquals(null, cookies.load())
        assertEquals(null, credentials.load())
    }

    @Test
    fun recoverSessionUsesSavedCredentialsAndCaptchaWhenCookieExpired() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            enqueueExpiredHome(server)
            enqueueSuccessfulAutoLogin(server)
            val cookieStore = MemoryCookieStore(payload = "[]")
            val credentialStore = MemoryCredentialStore(LoginCredentials("student", "secret"))
            val solver = FakeCaptchaSolver { CaptchaSolveResult(expression = "1+2=", answer = "3") }
            val manager = manager(server, cookieStore, credentialStore, solver)

            val result = manager.recoverSession()

            assertEquals(AutoLoginStatus.Ready, result.status)
            assertEquals(1, result.attempts)
            assertTrue(cookieStore.payload.orEmpty().contains("MISSESSION"))
            assertEquals(1, solver.calls)
            val requests = server.drainRequests()
            val loginSubmit = requests.single { it.method == "POST" && it.path == "/auth/login" }
            val body = loginSubmit.body.readUtf8()
            assertTrue(body.contains("loginname=student"))
            assertTrue(body.contains("password=secret"))
            assertTrue(body.contains("captcha_1=3"))
        }
    }

    @Test
    fun withAuthenticatedClientRecoversBeforeRunningBlock() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            enqueueExpiredHome(server)
            enqueueSuccessfulAutoLogin(server)
            val manager = manager(
                server = server,
                cookieStore = MemoryCookieStore(payload = "[]"),
                credentialStore = MemoryCredentialStore(LoginCredentials("student", "secret")),
                captchaSolver = FakeCaptchaSolver { CaptchaSolveResult(expression = "4+5=", answer = "9") },
            )
            var blockRan = false

            val value = manager.withAuthenticatedClient {
                blockRan = true
                "ok"
            }

            assertEquals("ok", value)
            assertTrue(blockRan)
        }
    }

    @Test
    fun withAuthenticatedClientRetriesOnceWhenBlockReportsExpiredSession() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            enqueueReadyValidation(server)
            enqueueExpiredHome(server)
            enqueueSuccessfulAutoLogin(server)
            val manager = manager(
                server = server,
                cookieStore = MemoryCookieStore(payload = "[]"),
                credentialStore = MemoryCredentialStore(LoginCredentials("student", "secret")),
                captchaSolver = FakeCaptchaSolver { CaptchaSolveResult(expression = "6+1=", answer = "7") },
            )
            var invocations = 0

            val value = manager.withAuthenticatedClient {
                invocations += 1
                if (invocations == 1) throw SessionExpiredException("expired inside request")
                "ok"
            }

            assertEquals("ok", value)
            assertEquals(2, invocations)
        }
    }

    @Test
    fun recoverSessionWithoutSavedCredentialsDoesNotFetchCaptcha() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val manager = manager(
                server = server,
                cookieStore = MemoryCookieStore(payload = null),
                credentialStore = MemoryCredentialStore(credentials = null),
                captchaSolver = FakeCaptchaSolver { CaptchaSolveResult(expression = "1+1=", answer = "2") },
            )

            val result = manager.recoverSession()

            assertEquals(AutoLoginStatus.AutoFailed, result.status)
            assertEquals(0, result.attempts)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun recoverSessionStopsAfterCaptchaFailures() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            enqueueExpiredHome(server)
            repeat(3) {
                enqueueExpiredHome(server)
                server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("png-$it"))
            }
            val solver = FakeCaptchaSolver { throw CaptchaSolveException("bad captcha") }
            val manager = manager(
                server = server,
                cookieStore = MemoryCookieStore(payload = "[]"),
                credentialStore = MemoryCredentialStore(LoginCredentials("student", "secret")),
                captchaSolver = solver,
            )

            val result = manager.recoverSession()

            assertEquals(AutoLoginStatus.AutoFailed, result.status)
            assertEquals(3, result.attempts)
            assertEquals(3, solver.calls)
            assertTrue(result.message.orEmpty().contains("bad captcha"))
        }
    }

    @Test
    fun recoverSessionStopsImmediatelyForNonRetryableCaptchaRuntimeFailure() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            enqueueExpiredHome(server)
            enqueueExpiredHome(server)
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("png"))
            val solver = FakeCaptchaSolver {
                throw CaptchaSolveException("runtime unavailable", retryable = false)
            }
            val manager = manager(
                server = server,
                cookieStore = MemoryCookieStore(payload = "[]"),
                credentialStore = MemoryCredentialStore(LoginCredentials("student", "secret")),
                captchaSolver = solver,
            )

            val result = manager.recoverSession()

            assertEquals(AutoLoginStatus.AutoFailed, result.status)
            assertEquals(1, result.attempts)
            assertEquals(1, solver.calls)
            assertTrue(result.message.orEmpty().contains("runtime unavailable"))
        }
    }

    @Test
    fun explicitAutoLoginFallsBackToManualCaptchaForNonRetryableRuntimeFailure() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            enqueueExpiredHome(server)
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("png"))
            enqueueExpiredHome(server)
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("manual-png"))
            val solver = FakeCaptchaSolver {
                throw CaptchaSolveException("runtime unavailable", retryable = false)
            }
            val manager = manager(
                server = server,
                cookieStore = MemoryCookieStore(payload = null),
                credentialStore = MemoryCredentialStore(credentials = null),
                captchaSolver = solver,
            )

            val result = manager.loginAuto("student", "secret")

            assertEquals(AutoLoginStatus.ManualRequired, result.status)
            assertEquals(1, result.attempts)
            assertEquals(1, solver.calls)
            assertNotNull(result.captcha)
            assertTrue(result.message.orEmpty().contains("runtime unavailable"))
        }
    }

    @Test
    fun explicitAutoLoginReportsNetworkFailureWithoutManualCaptchaFallback() = runBlocking {
        val server = MockWebServer()
        server.start()
        val manager = manager(
            server = server,
            cookieStore = MemoryCookieStore(payload = null),
            credentialStore = MemoryCredentialStore(credentials = null),
            captchaSolver = FakeCaptchaSolver { CaptchaSolveResult(expression = "1+1=", answer = "2") },
        )
        server.shutdown()

        val result = manager.loginAuto("student", "secret")

        assertEquals(AutoLoginStatus.AutoFailed, result.status)
        assertEquals(1, result.attempts)
        assertEquals(0, server.requestCount)
    }

    private fun manager(
        server: MockWebServer,
        cookieStore: MemoryCookieStore,
        credentialStore: MemoryCredentialStore,
        captchaSolver: CaptchaAnswerSolver,
    ): SessionManager {
        val cookieJar = AppCookieJar()
        return SessionManager(
            cookieStore = cookieStore,
            credentialStore = credentialStore,
            cookieJar = cookieJar,
            httpClient = BjtuHttpClient(cookieJar),
            captchaSolver = captchaSolver,
            endpoints = endpoints(server),
        )
    }

    private fun endpoints(server: MockWebServer): SessionEndpoints {
        val base = server.url("/").toString().trimEnd('/')
        return SessionEndpoints(
            misHomeUrl = "$base/home/",
            misAaBridgeUrl = "$base/module/module/10/",
            aaTimetableUrl = "$base/aa/timetable/",
            bksyVeBridgeUrl = "$base/ve/bridge/",
            casOrigin = base,
            misReferer = "$base/",
            bksyReferer = "$base/",
        )
    }

    private fun enqueueExpiredHome(server: MockWebServer) {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "/auth/login?next=/home/"),
        )
        server.enqueue(MockResponse().setBody(loginPage()))
    }

    private fun enqueueReadyValidation(server: MockWebServer) {
        server.enqueue(MockResponse().setBody("home ready"))
        server.enqueue(MockResponse().setBody("aa ready"))
    }

    private fun enqueueSuccessfulAutoLogin(server: MockWebServer) {
        enqueueExpiredHome(server)
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("png"))
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "/home/")
                .setHeader("Set-Cookie", "MISSESSION=new; Path=/"),
        )
        server.enqueue(MockResponse().setBody("home ready after login"))
        server.enqueue(MockResponse().setBody("aa ready after login"))
        server.enqueue(MockResponse().setBody("ve ready"))
        enqueueReadyValidation(server)
    }

    private fun loginPage(): String = """
        <html>
          <body>
            <form id="login" method="post" action="/auth/login">
              <input type="hidden" name="next" value="/home/" />
              <input type="hidden" name="csrfmiddlewaretoken" value="csrf-token" />
              <input type="text" name="loginname" />
              <input type="password" name="password" />
              <input type="hidden" name="captcha_0" value="captcha-key" />
              <img class="captcha" src="/captcha/image/1.png" />
            </form>
          </body>
        </html>
    """.trimIndent()

    private fun MockWebServer.drainRequests() =
        List(requestCount) { takeRequest() }

    private class MemoryCookieStore(
        var payload: String? = null,
    ) : SessionCookieStore {
        override fun save(plainText: String) {
            payload = plainText
        }

        override fun load(): String? = payload

        override fun clear() {
            payload = null
        }
    }

    private class MemoryCredentialStore(
        var credentials: LoginCredentials? = null,
    ) : CredentialStore {
        override fun save(credentials: LoginCredentials) {
            this.credentials = credentials
        }

        override fun load(): LoginCredentials? = credentials

        override fun clear() {
            credentials = null
        }
    }

    private class FakeCaptchaSolver(
        private val solveBlock: (ByteArray) -> CaptchaSolveResult,
    ) : CaptchaAnswerSolver {
        var calls = 0
            private set

        override fun solve(imageBytes: ByteArray): CaptchaSolveResult {
            calls += 1
            return solveBlock(imageBytes)
        }
    }
}
