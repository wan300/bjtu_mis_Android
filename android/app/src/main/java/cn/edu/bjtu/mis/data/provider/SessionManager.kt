package cn.edu.bjtu.mis.data.provider

import cn.edu.bjtu.mis.data.captcha.CaptchaAnswerSolver
import cn.edu.bjtu.mis.data.captcha.CaptchaSolveException
import cn.edu.bjtu.mis.data.network.AppCookieJar
import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import cn.edu.bjtu.mis.data.parser.normalizeSpace
import cn.edu.bjtu.mis.data.parser.CampusLoginPage
import cn.edu.bjtu.mis.data.parser.hasCampusLoginForm
import cn.edu.bjtu.mis.data.parser.parseCampusLoginPage
import cn.edu.bjtu.mis.data.perf.PerfTrace
import cn.edu.bjtu.mis.data.security.CredentialStore
import cn.edu.bjtu.mis.data.security.LoginCredentials
import cn.edu.bjtu.mis.data.security.SessionCookieStore
import cn.edu.bjtu.mis.data.security.encryptSsoPassword
import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.model.AutoLoginResult
import cn.edu.bjtu.mis.model.AutoLoginStatus
import cn.edu.bjtu.mis.model.SessionCaptcha
import cn.edu.bjtu.mis.model.SessionState
import cn.edu.bjtu.mis.model.SessionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.TimeUnit

enum class SessionValidationPolicy {
    Fresh,
    UseRecentOrValidate,
}

data class SessionEndpoints(
    val misHomeUrl: String = ProviderConstants.MIS_HOME_URL,
    val misAaBridgeUrl: String = ProviderConstants.MIS_AA_BRIDGE_URL,
    val aaTimetableUrl: String = ProviderConstants.AA_TIMETABLE_URL,
    val bksyVeBridgeUrl: String = ProviderConstants.BKSY_VE_BRIDGE_URL,
    val casLoginPath: String = "/auth/login",
    val casOrigin: String = "https://cas.bjtu.edu.cn",
    val misReferer: String = "https://mis.bjtu.edu.cn/",
    val bksyReferer: String = "https://bksy.bjtu.edu.cn/",
)

internal data class SessionValidationRecord(
    val status: SessionStatus,
    val savedAtMillis: Long,
)

internal class SessionValidationCache(
    private val ttlMillis: Long,
    private val nowMillis: () -> Long,
) {
    private var record: SessionValidationRecord? = null

    @Synchronized
    fun getFresh(): SessionStatus? {
        val current = record ?: return null
        if (nowMillis() - current.savedAtMillis > ttlMillis) return null
        return current.status
    }

    @Synchronized
    fun remember(status: SessionStatus) {
        if (status.state == SessionState.Ready) {
            record = SessionValidationRecord(status, nowMillis())
        } else {
            clear()
        }
    }

    @Synchronized
    fun clear() {
        record = null
    }
}

class SessionManager(
    private val cookieStore: SessionCookieStore,
    private val credentialStore: CredentialStore,
    private val cookieJar: AppCookieJar,
    private val httpClient: BjtuHttpClient,
    private val captchaSolver: CaptchaAnswerSolver,
    private val endpoints: SessionEndpoints = SessionEndpoints(),
) {
    private val mutex = Mutex()
    private val validationMutex = Mutex()
    private val reauthMutex = Mutex()
    private val validationCache = SessionValidationCache(
        ttlMillis = TimeUnit.MINUTES.toMillis(5),
        nowMillis = { PerfTrace.nowMillis() },
    )
    private var inlineLoginState: InlineLoginState? = null

    suspend fun fetchInlineLoginCaptcha(): SessionCaptcha = fetchInlineLoginCaptcha(reusePreparedCasPage = false)

    private suspend fun fetchInlineLoginCaptcha(reusePreparedCasPage: Boolean): SessionCaptcha = mutex.withLock {
        val previous = inlineLoginState
        inlineLoginState = null
        val state = previous?.takeIf { it.page.isSso || reusePreparedCasPage } ?: loadInlineLoginPage()
        cookieJar.restoreFromJson(state.cookiesJson)
        val dataUrl = if (state.page.isSso) {
            val endpoint = URI(state.referer).resolve("/api/captcha/getCaptcha").toString()
            val response = httpClient.postForm(endpoint, emptyMap(), headers = mapOf("Referer" to state.referer))
            val payload = runCatching { AppJson.parseToJsonElement(response.body) as? JsonObject }.getOrNull()
            val data = payload?.get("data") as? JsonObject
            val id = data?.get("captchaId")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: throw IOException("统一认证验证码加载失败，请刷新后重试。")
            val image = data?.get("captchaImg")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val imageDataUrl = if (image.startsWith("data:image/")) image else "data:image/png;base64,$image"
            validateCaptchaData(imageDataUrl)
            inlineLoginState = state.copy(captchaId = id, cookiesJson = cookieJar.encodeSnapshot())
            imageDataUrl
        } else {
            val captcha = httpClient.getBytes(
                requireNotNull(state.page.captchaUrl),
                headers = mapOf("Referer" to state.referer),
                maxBytes = 1024 * 1024L,
            )
            val mimeType = captcha.headers["Content-Type"]?.substringBefore(";")?.trim().orEmpty()
                .ifBlank { "image/jpeg" }
            if (!mimeType.startsWith("image/") || captcha.body.isEmpty()) {
                throw IOException("验证码接口未返回有效图片，请刷新后重试。")
            }
            val encoded = Base64.getEncoder().encodeToString(captcha.body)
            inlineLoginState = state.copy(cookiesJson = cookieJar.encodeSnapshot(), captchaImageBase64 = encoded)
            "data:$mimeType;base64,$encoded"
        }
        SessionCaptcha(imageDataUrl = dataUrl, fetchedAt = nowIso())
    }

    private fun validateCaptchaData(dataUrl: String) {
        val encoded = dataUrl.substringAfter("base64,", "")
        if (encoded.isBlank() || encoded.length > 1_400_000 ||
            runCatching { Base64.getDecoder().decode(encoded).isNotEmpty() }.getOrDefault(false).not()
        ) {
            throw IOException("统一认证验证码图片无效，请刷新后重试。")
        }
    }

    private suspend fun loadInlineLoginPage(): InlineLoginState {
        inlineLoginState = null
        validationCache.clear()
        VeSessionContextCache.clear()
        cookieJar.clear()
        val loginPage = httpClient.getText(endpoints.misHomeUrl)
        if (!hasCampusLoginForm(loginPage.body)) {
            throw SessionExpiredException("未获取到统一认证登录页，请检查网络后重试。")
        }
        requireTrustedLoginOrigin(loginPage.url)
        return InlineLoginState(
            page = parseCampusLoginPage(loginPage.url, loginPage.body),
            referer = loginPage.url,
            cookiesJson = cookieJar.encodeSnapshot(),
        )
    }

    private fun requireTrustedLoginOrigin(url: String) {
        val uri = URI(url)
        val configured = URI(endpoints.casOrigin)
        if (!(uri.scheme == configured.scheme && uri.host == configured.host && uri.port == configured.port) &&
            !(uri.scheme == "https" && uri.host in setOf("cas.bjtu.edu.cn", "sso.bjtu.edu.cn") && uri.port in listOf(-1, 443))
        ) {
            throw IOException("统一认证页面来源不受信任，已停止登录。")
        }
    }

    suspend fun loginInline(
        loginName: String,
        password: String,
        captcha: String,
        persistCredentials: Boolean = true,
    ): SessionStatus = mutex.withLock {
        val state = inlineLoginState ?: throw SessionExpiredException("验证码已失效，请刷新后重试。")
        inlineLoginState = null
        VeSessionContextCache.clear()
        cookieJar.restoreFromJson(state.cookiesJson)
        val form = state.page.fields.toMutableMap()
        if (state.page.isSso) {
            if (state.page.captchaRequired && (state.captchaId == null || captcha.isBlank())) {
                inlineLoginState = state
                throw SessionExpiredException("请输入验证码后登录。")
            }
            form["username"] = loginName.trim()
            form["password"] = encryptSsoPassword(password, requireNotNull(state.page.sm2PublicKey))
            form["loginType"] = "username_password"
            form["captcha"] = state.captchaId?.let { id ->
                buildJsonObject { put("captchaId", id); put("captchaValue", captcha.trim()) }.toString()
            }.orEmpty()
        } else {
            form["loginname"] = loginName.trim()
            form["password"] = password
            form["captcha_1"] = captcha.trim()
        }
        val origin = URI(state.referer).let { "${it.scheme}://${it.rawAuthority}" }
        val response = httpClient.postForm(
            state.page.loginUrl,
            form = form,
            headers = mapOf(
                "Referer" to state.referer,
                "Origin" to origin,
            ),
        )

        if (hasCampusLoginForm(response.body)) {
            requireTrustedLoginOrigin(response.url)
            val page = parseCampusLoginPage(response.url, response.body)
            if (page.isSso) {
                inlineLoginState = InlineLoginState(page, response.url, cookieJar.encodeSnapshot())
            }
            throw SessionExpiredException(page.errorMessage ?: extractInlineLoginError(response.body) ?: "登录失败，请检查账号、密码和验证码。")
        }
        if (isCasLoginUrl(response.url)) {
            throw SessionExpiredException(extractInlineLoginError(response.body) ?: "登录失败，请检查账号、密码和验证码。")
        }

        val home = if (response.url == endpoints.misHomeUrl) response else httpClient.getText(endpoints.misHomeUrl)
        if (isCasLoginUrl(home.url) || hasCampusLoginForm(home.body)) {
            throw SessionExpiredException("统一认证尚未建立 MIS 会话，请重新登录。")
        }

        val aaReady = ensureAaSessionReady()
        if (!aaReady.first) {
            inlineLoginState = null
            throw SessionExpiredException(aaReady.second ?: "教学支撑平台会话未准备好。")
        }

        bootstrapVeSessionBestEffort()
        cookieStore.save(cookieJar.encodeSnapshot())
        val status = validateSession()
        if (status.state != SessionState.Ready) {
            throw SessionExpiredException(status.detail ?: "统一认证会话校验失败，请重新登录。")
        }
        if (persistCredentials) {
            credentialStore.save(LoginCredentials(loginName.trim(), password))
        }
        inlineLoginState = null
        status
    }

    suspend fun loginAuto(loginName: String? = null, password: String? = null): AutoLoginResult {
        val explicitCredentials = !loginName.isNullOrBlank() && !password.isNullOrBlank()
        val credentials = if (explicitCredentials) {
            LoginCredentials(loginName = loginName!!.trim(), password = password!!)
        } else {
            credentialStore.load()
        } ?: return AutoLoginResult(
            status = AutoLoginStatus.AutoFailed,
            message = "未保存登录凭据，请手动输入学号和密码。",
        )

        val maxAttempts = if (explicitCredentials) 2 else 3
        var lastMessage = ""
        repeat(maxAttempts) { index ->
            var isSsoAttempt = false
            try {
                val page = mutex.withLock { loadInlineLoginPage().also { inlineLoginState = it }.page }
                isSsoAttempt = page.isSso
                val solved = if (page.isSso) {
                    if (page.captchaRequired) {
                        if (!explicitCredentials) {
                            return AutoLoginResult(AutoLoginStatus.AutoFailed, "统一认证需要验证码，请手动登录。", index + 1)
                        }
                        return AutoLoginResult(
                            AutoLoginStatus.ManualRequired, "统一认证需要验证码，请输入后登录。", index + 1,
                            captcha = fetchInlineLoginCaptcha(),
                        )
                    }
                    null
                } else {
                    fetchInlineLoginCaptcha(reusePreparedCasPage = true)
                    val captchaBytes = currentCaptchaBytes()
                    withContext(Dispatchers.Default) { captchaSolver.solve(captchaBytes) }
                }
                val status = loginInline(
                    credentials.loginName,
                    credentials.password,
                    solved?.answer.orEmpty(),
                    persistCredentials = true,
                )
                return AutoLoginResult(
                    status = AutoLoginStatus.Ready,
                    message = if (solved == null) "统一认证登录成功。" else "已自动识别验证码并完成登录。",
                    attempts = index + 1,
                    session = status,
                )
            } catch (error: CaptchaSolveException) {
                lastMessage = error.message.orEmpty()
                inlineLoginState = null
                if (!error.retryable) {
                    val attempts = index + 1
                    if (explicitCredentials) {
                        val captcha = runCatching { fetchInlineLoginCaptcha() }.getOrNull()
                        return AutoLoginResult(
                            status = AutoLoginStatus.ManualRequired,
                            message = "自动识别验证码不可用，请手动输入验证码。$lastMessage",
                            attempts = attempts,
                            captcha = captcha,
                        )
                    }
                    return AutoLoginResult(
                        status = AutoLoginStatus.AutoFailed,
                        message = "自动识别验证码不可用：${lastMessage.ifBlank { "未知错误" }}",
                        attempts = attempts,
                    )
                }
            } catch (error: SessionExpiredException) {
                lastMessage = error.message.orEmpty()
                if (isSsoAttempt) {
                    if (explicitCredentials && inlineLoginState?.page?.captchaRequired == true) {
                        val captcha = try {
                            fetchInlineLoginCaptcha()
                        } catch (loadError: IOException) {
                            return AutoLoginResult(AutoLoginStatus.AutoFailed, loadError.message, index + 1)
                        }
                        return AutoLoginResult(AutoLoginStatus.ManualRequired, lastMessage, index + 1, captcha = captcha)
                    }
                    inlineLoginState = null
                    return AutoLoginResult(AutoLoginStatus.AutoFailed, lastMessage, index + 1)
                }
            } catch (error: IOException) {
                lastMessage = error.message.orEmpty()
                inlineLoginState = null
                return AutoLoginResult(
                    status = AutoLoginStatus.AutoFailed,
                    message = lastMessage.ifBlank { "网络连接失败，请检查网络后重试。" },
                    attempts = index + 1,
                )
            }
        }

        if (explicitCredentials) {
            val captcha = runCatching { fetchInlineLoginCaptcha() }.getOrNull()
            return AutoLoginResult(
                status = AutoLoginStatus.ManualRequired,
                message = "自动识别连续失败，请手动输入验证码。$lastMessage",
                attempts = maxAttempts,
                captcha = captcha,
            )
        }

        return AutoLoginResult(
            status = AutoLoginStatus.AutoFailed,
            message = "自动登录连续失败 $maxAttempts 次：${lastMessage.ifBlank { "未知错误" }}",
            attempts = maxAttempts,
        )
    }

    private fun currentCaptchaBytes(): ByteArray {
        val encoded = inlineLoginState?.captchaImageBase64
            ?: throw CaptchaSolveException("验证码图片上下文缺失。")
        return runCatching { Base64.getDecoder().decode(encoded) }
            .getOrElse { throw CaptchaSolveException("验证码图片上下文损坏。", it) }
    }

    fun cachedSessionStatus(): SessionStatus {
        val payload = cookieStore.load()
            ?: return SessionStatus(SessionState.WaitingForLogin, "No saved local session.")
        cookieJar.restoreFromJson(payload)
        return SessionStatus(SessionState.Ready, "Using cached local session while validating in background.")
    }

    suspend fun validateSession(
        policy: SessionValidationPolicy = SessionValidationPolicy.Fresh,
    ): SessionStatus {
        if (policy == SessionValidationPolicy.UseRecentOrValidate) {
            validationCache.getFresh()?.let {
                PerfTrace.mark("Session.validate", "cache_hit")
                return it
            }
        }

        return validationMutex.withLock {
            if (policy == SessionValidationPolicy.UseRecentOrValidate) {
                validationCache.getFresh()?.let {
                    PerfTrace.mark("Session.validate", "cache_hit_after_lock")
                    return@withLock it
                }
            }
            PerfTrace.measureSuspend("Session.validate") {
                validateSessionFresh().also { validationCache.remember(it) }
            }
        }
    }

    private suspend fun validateSessionFresh(): SessionStatus {
        val payload = cookieStore.load()
            ?: return SessionStatus(SessionState.WaitingForLogin, "未找到可用会话，请先登录。")
        cookieJar.restoreFromJson(payload)
        return runCatching {
            val response = httpClient.getText(endpoints.misHomeUrl)
            if (isCasLoginUrl(response.url) || hasCampusLoginForm(response.body)) {
                VeSessionContextCache.clear()
                SessionStatus(SessionState.Expired, "会话已过期，请重新登录。")
            } else {
                val aaReady = ensureAaSessionReady()
                when {
                    !aaReady.first -> SessionStatus(SessionState.Expired, aaReady.second)
                    aaReady.second != null -> {
                        cookieStore.save(cookieJar.encodeSnapshot())
                        SessionStatus(SessionState.Ready, "会话可用：${aaReady.second}")
                    }
                    else -> {
                        cookieStore.save(cookieJar.encodeSnapshot())
                        SessionStatus(SessionState.Ready, "会话可用。")
                    }
                }
            }
        }.getOrElse { error ->
            SessionStatus(SessionState.Expired, "会话校验失败：${error.message}")
        }
    }

    suspend fun recoverSession(
        policy: SessionValidationPolicy = SessionValidationPolicy.UseRecentOrValidate,
    ): AutoLoginResult = PerfTrace.measureSuspend("Session.recover") {
        val current = validateSession(policy)
        if (current.state == SessionState.Ready) {
            return@measureSuspend AutoLoginResult(
                status = AutoLoginStatus.Ready,
                message = current.detail,
                attempts = 0,
                session = current,
            )
        }

        return@measureSuspend reauthMutex.withLock {
            if (policy == SessionValidationPolicy.UseRecentOrValidate) {
                validationCache.getFresh()?.let { afterLock ->
                    return@withLock AutoLoginResult(
                        status = AutoLoginStatus.Ready,
                        message = afterLock.detail,
                        attempts = 0,
                        session = afterLock,
                    )
                }
            }
            validationCache.clear()
            loginAuto()
        }
    }

    suspend fun <T> withAuthenticatedClient(
        policy: SessionValidationPolicy = SessionValidationPolicy.UseRecentOrValidate,
        block: suspend (BjtuHttpClient) -> T,
    ): T {
        val recovered = recoverSession(policy)
        if (recovered.status != AutoLoginStatus.Ready) {
            throw SessionExpiredException(recovered.message ?: "会话未准备好，自动重新登录失败。")
        }

        return try {
            block(httpClient).also {
                cookieStore.save(cookieJar.encodeSnapshot())
            }
        } catch (error: SessionExpiredException) {
            validationCache.clear()
            VeSessionContextCache.clear()
            val retried = recoverSession(SessionValidationPolicy.Fresh)
            if (retried.status != AutoLoginStatus.Ready) {
                throw SessionExpiredException(retried.message ?: error.message ?: "会话已过期，自动重新登录失败。")
            }
            block(httpClient).also {
                cookieStore.save(cookieJar.encodeSnapshot())
            }
        }
    }

    suspend fun <T> withCampusServiceClient(
        serviceId: String,
        block: suspend (BjtuHttpClient) -> T,
    ): T = withAuthenticatedClient { client ->
        when (serviceId.lowercase()) {
            "mis" -> Unit
            "aa" -> {
                val ready = ensureAaSessionReady()
                if (!ready.first) {
                    throw SessionExpiredException(ready.second ?: "教学支撑平台会话初始化失败。")
                }
            }
            "ve" -> if (!bootstrapVeSessionBestEffort()) {
                throw SessionExpiredException("虚拟教学环境会话初始化失败。")
            }
            else -> throw IllegalArgumentException("未注册的校园服务：$serviceId")
        }
        block(client)
    }

    fun logout() {
        inlineLoginState = null
        validationCache.clear()
        VeSessionContextCache.clear()
        cookieJar.clear()
        cookieStore.clear()
        credentialStore.clear()
    }

    private suspend fun ensureAaSessionReady(): Pair<Boolean, String?> {
        val state = checkAaSessionState()
        if (state.first == "ready" || state.first == "service_unavailable") return true to state.second
        if (state.first == "error") return false to state.second
        if (state.first == "login_required") {
            val bootstrapped = bootstrapAaSession()
            if (!bootstrapped) return false to "教学支撑平台单点登录未完成。"
            val after = checkAaSessionState()
            if (after.first == "ready" || after.first == "service_unavailable") return true to after.second
            return false to (after.second ?: "教学支撑平台仍要求登录。")
        }
        return false to state.second
    }

    private suspend fun checkAaSessionState(): Pair<String, String?> = runCatching {
        val response = httpClient.getText(endpoints.aaTimetableUrl)
        val head = response.body.take(4096)
        when {
            isCasLoginUrl(response.url) || hasCampusLoginForm(response.body) || isAaLoginPage(response.url, head) -> "login_required" to null
            else -> "ready" to null
        }
    }.getOrElse { "error" to "教学支撑平台会话校验失败：${it.message}" }

    private suspend fun bootstrapAaSession(): Boolean = runCatching {
        val bridge = httpClient.getText(
            endpoints.misAaBridgeUrl,
            headers = mapOf("Referer" to endpoints.misHomeUrl),
        )
        val loginUrl = extractAaClientLoginUrl(bridge.body) ?: return false
        val response = httpClient.getText(loginUrl, headers = mapOf("Referer" to endpoints.misReferer))
        !isCasLoginUrl(response.url) && !hasCampusLoginForm(response.body)
    }.getOrDefault(false)

    private suspend fun bootstrapVeSessionBestEffort(): Boolean = runCatching {
        val response = httpClient.getText(
            endpoints.bksyVeBridgeUrl,
            headers = mapOf("Referer" to endpoints.bksyReferer),
        )
        !isCasLoginUrl(response.url) && !hasCampusLoginForm(response.body)
    }.getOrDefault(false)

    private fun extractInlineLoginError(html: String): String? {
        val document = Jsoup.parse(html)
        val tip = normalizeSpace(document.selectFirst(".tishi")?.text())
        if (tip.isNotBlank()) return tip
        val text = normalizeSpace(document.text())
        return listOf("验证码", "用户名或密码", "登录失败", "密码错误", "账号")
            .firstOrNull { text.contains(it) }
            ?.let { "登录失败：$it 校验未通过。" }
    }

    private fun isCasLoginUrl(url: String): Boolean {
        if (isBjtuCasLoginUrl(url)) return true
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        return path == endpoints.casLoginPath || path.startsWith("${endpoints.casLoginPath}/")
    }

    private fun isAaLoginPage(url: String, bodyHead: String): Boolean =
        url.contains("/client/login/") || (bodyHead.contains("用户登录") && bodyHead.contains("教学支撑平台"))

    private fun extractAaClientLoginUrl(html: String): String? =
        Regex("https?://[^\\s\"'<]+/client/login/[^\\s\"'<]+").find(html)?.value

    private fun nowIso(): String =
        OffsetDateTime.now(ZoneOffset.UTC).withNano(0).toString()

    private data class InlineLoginState(
        val page: CampusLoginPage,
        val referer: String,
        val cookiesJson: String,
        val captchaImageBase64: String = "",
        val captchaId: String? = null,
    )
}
