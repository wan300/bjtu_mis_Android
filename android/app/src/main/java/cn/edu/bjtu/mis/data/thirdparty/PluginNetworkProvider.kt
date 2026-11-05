package cn.edu.bjtu.mis.data.thirdparty

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.net.InetAddress
import java.net.URI
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min

const val PLUGIN_NETWORK_DEFAULT_TIMEOUT_MS = 15_000L
const val PLUGIN_NETWORK_MAX_TIMEOUT_MS = 60_000L
const val PLUGIN_NETWORK_MAX_REDIRECTS = 5
const val PLUGIN_NETWORK_INLINE_BYTES = 1024 * 1024
const val PLUGIN_NETWORK_PLUGIN_CONCURRENCY = 4
const val PLUGIN_NETWORK_ORIGIN_CONCURRENCY = 2

data class PluginNetworkRequest(
    val requestId: String,
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: PluginNetworkBody? = null,
    val timeoutMs: Long = PLUGIN_NETWORK_DEFAULT_TIMEOUT_MS,
    val capability: String = "network.request@1",
    val includeCookies: Boolean = false,
)

sealed interface PluginNetworkBody {
    data class Text(
        val value: String,
        val mediaType: String = "text/plain; charset=utf-8",
    ) : PluginNetworkBody

    data class Json(val value: String) : PluginNetworkBody
    data class FormUrlEncoded(val fields: List<Pair<String, String>>) : PluginNetworkBody
    data class Multipart(val parts: List<PluginNetworkFormPart>) : PluginNetworkBody
    data class Blob(val handle: String, val mediaType: String? = null) : PluginNetworkBody
}

sealed interface PluginNetworkFormPart {
    val name: String

    data class Text(
        override val name: String,
        val value: String,
    ) : PluginNetworkFormPart

    data class Blob(
        override val name: String,
        val handle: String,
        val fileName: String,
        val mediaType: String? = null,
    ) : PluginNetworkFormPart
}

data class PluginNetworkProgress(
    val requestId: String,
    val phase: String,
    val transferredBytes: Long,
    val totalBytes: Long?,
)

data class PluginNetworkResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val finalUrl: String,
    val redirects: Int,
    val mediaType: String?,
    val text: String? = null,
    val json: String? = null,
    val resource: ThirdPartyResourceDescriptor? = null,
)

class PluginNetworkException(
    val code: String,
    override val message: String,
    val httpStatus: Int? = null,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : ThirdPartyServiceException(message, cause)

class PluginNetworkProvider private constructor(
    private val resourceStore: ThirdPartyResourceStore,
    baseClient: OkHttpClient,
    private val resolver: Dns,
    private val addressForbidden: (InetAddress) -> Boolean,
    private val allowCleartextForTests: Boolean,
) {
    constructor(
        resourceStore: ThirdPartyResourceStore,
        baseClient: OkHttpClient = OkHttpClient(),
    ) : this(
        resourceStore,
        baseClient,
        Dns.SYSTEM,
        ::isForbiddenPluginAddress,
        false,
    )

    private val validatingDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            validateHostName(hostname)
            val addresses = resolver.lookup(hostname)
            if (addresses.isEmpty() || addresses.any(addressForbidden)) {
                throw java.net.UnknownHostException("origin_denied")
            }
            return addresses
        }
    }
    private val client = baseClient.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(validatingDns)
        .build()
    private val pluginSemaphores = ConcurrentHashMap<String, Semaphore>()
    private val originSemaphores = ConcurrentHashMap<String, Semaphore>()
    private val activeCalls = ConcurrentHashMap<String, Call>()
    private val cookieLock = Any()
    private val cookieJars = mutableMapOf<ThirdPartyKvNamespace, MutableMap<String, PluginOriginCookieJar>>()

    fun clearSessions(publisher: String, plugin: String) = synchronized(cookieLock) {
        cookieJars.remove(ThirdPartyKvNamespace(publisher, plugin))?.values?.forEach { it.clear() }
        activeCalls.filterKeys { it.startsWith("$publisher\u0000$plugin\u0000") }.values.forEach(Call::cancel)
        Unit
    }

    fun clearAllSessions() = synchronized(cookieLock) {
        cookieJars.values.flatMap { it.values }.forEach { it.clear() }
        cookieJars.clear()
        activeCalls.values.forEach(Call::cancel)
    }

    private fun cookieJar(service: ThirdPartyService, url: String): CookieJar = synchronized(cookieLock) {
        val namespace = namespace(service)
        val target = url.toHttpUrl()
        val origin = "${target.scheme}://${target.host}:${target.port}"
        val jars = cookieJars.getOrPut(namespace) { mutableMapOf() }
        jars[origin] ?: run {
            if (jars.size >= 32) throw PluginNetworkException("quota_exceeded", "Too many Cookie origins")
            PluginOriginCookieJar(origin).also { jars[origin] = it }
        }
    }

    suspend fun execute(
        service: ThirdPartyService,
        request: PluginNetworkRequest,
        onProgress: (PluginNetworkProgress) -> Unit = {},
    ): PluginNetworkResponse {
        if (request.capability !in setOf("network.request@1", "network.request@2") || request.capability !in service.grantedCapabilities) {
            throw PluginNetworkException("permission_denied", "Network capability is not granted")
        }
        if (request.includeCookies && request.capability != "network.request@2") {
            throw PluginNetworkException("permission_denied", "Cookie sessions require network.request@2")
        }
        validateRequest(request)
        val pluginKey = "${service.publisherSubjectId}\u0000${service.serviceId}"
        return pluginSemaphores
            .getOrPut(pluginKey) { Semaphore(PLUGIN_NETWORK_PLUGIN_CONCURRENCY) }
            .withPermit {
                executeRedirectChain(service, request, onProgress)
            }
    }

    fun cancel(service: ThirdPartyService, requestId: String): Boolean =
        activeCalls.remove(callKey(service, requestId))?.also(Call::cancel) != null

    private suspend fun executeRedirectChain(
        service: ThirdPartyService,
        initial: PluginNetworkRequest,
        onProgress: (PluginNetworkProgress) -> Unit,
    ): PluginNetworkResponse {
        var currentUrl = initial.url
        var currentMethod = initial.method.uppercase(Locale.US)
        var currentBody = initial.body
        var currentHeaders = initial.headers
        var redirects = 0
        val initialOrigin = validateTarget(service, currentUrl)
        while (true) {
            val origin = validateTarget(service, currentUrl)
            val originKey = "${service.publisherSubjectId}\u0000${service.serviceId}\u0000$origin"
            val response = originSemaphores
                .getOrPut(originKey) { Semaphore(PLUGIN_NETWORK_ORIGIN_CONCURRENCY) }
                .withPermit {
                    executeOnce(
                        service,
                        initial.copy(
                            method = currentMethod,
                            url = currentUrl,
                            headers = currentHeaders,
                            body = currentBody,
                        ),
                        redirects,
                        onProgress,
                    )
                }
            val location = response.header("Location")
            if (response.code !in REDIRECT_CODES || location.isNullOrBlank()) {
                return try {
                    response.use {
                        readResponse(service, initial.requestId, it, redirects, onProgress)
                    }
                } finally {
                    activeCalls.remove(callKey(service, initial.requestId))
                }
            }
            response.close()
            activeCalls.remove(callKey(service, initial.requestId))
            if (redirects >= PLUGIN_NETWORK_MAX_REDIRECTS) {
                throw PluginNetworkException("http_error", "Too many redirects")
            }
            val nextUrl = URI(currentUrl).resolve(location).toString()
            val nextOrigin = validateTarget(service, nextUrl)
            redirects += 1
            if (response.code == 303 || (response.code in setOf(301, 302) && currentMethod == "POST")) {
                currentMethod = "GET"
                currentBody = null
            }
            if (nextOrigin != initialOrigin) {
                currentHeaders = currentHeaders.filterKeys {
                    !it.equals("Authorization", ignoreCase = true)
                }
            }
            currentUrl = nextUrl
        }
    }

    private suspend fun executeOnce(
        service: ThirdPartyService,
        request: PluginNetworkRequest,
        redirectCount: Int,
        onProgress: (PluginNetworkProgress) -> Unit,
    ): Response = withContext(Dispatchers.IO) {
        val timeout = request.timeoutMs.coerceIn(1, PLUGIN_NETWORK_MAX_TIMEOUT_MS)
        val callClient = client.newBuilder()
            .cookieJar(if (request.includeCookies) cookieJar(service, request.url) else CookieJar.NO_COOKIES)
            .callTimeout(timeout, TimeUnit.MILLISECONDS)
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .writeTimeout(timeout, TimeUnit.MILLISECONDS)
            .build()
        val requestBody = buildRequestBody(service, request.body, request.requestId, onProgress)
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) ->
            validateHeader(name, value)
            builder.addHeader(name, value)
        }
        builder.method(request.method.uppercase(Locale.US), requestBody)
        val call = callClient.newCall(builder.build())
        val activeCallKey = callKey(service, request.requestId)
        val replaced = activeCalls.putIfAbsent(activeCallKey, call)
        if (replaced != null) {
            throw PluginNetworkException("invalid_request", "Duplicate active requestId")
        }
        var responseReturned = false
        try {
            onProgress(
                PluginNetworkProgress(
                    request.requestId,
                    "request",
                    if (requestBody == null) 0 else requestBody.contentLength().coerceAtLeast(0),
                    requestBody?.contentLength()?.takeIf { it >= 0 },
                ),
            )
            call.execute().also { responseReturned = true }
        } catch (error: java.io.InterruptedIOException) {
            throw PluginNetworkException(
                "network_timeout",
                "Network request timed out",
                retryable = true,
                cause = error,
            )
        } catch (error: java.io.IOException) {
            if (call.isCanceled()) {
                throw PluginNetworkException("user_cancelled", "Network request was cancelled", cause = error)
            }
            if (error.message == "origin_denied" || error.cause?.message == "origin_denied") {
                throw PluginNetworkException("origin_denied", "Resolved address is not public", cause = error)
            }
            throw PluginNetworkException(
                "http_error",
                error.message ?: "Network request failed",
                retryable = true,
                cause = error,
            )
        } finally {
            if (!responseReturned) activeCalls.remove(activeCallKey, call)
        }
    }

    private suspend fun readResponse(
        service: ThirdPartyService,
        requestId: String,
        response: Response,
        redirects: Int,
        onProgress: (PluginNetworkProgress) -> Unit,
    ): PluginNetworkResponse {
        val body = response.body
            ?: return PluginNetworkResponse(
                response.code,
                safeResponseHeaders(response),
                response.request.url.toString(),
                redirects,
                null,
                text = "",
            )
        val mediaType = body.contentType()?.toString()
        val total = body.contentLength().takeIf { it >= 0 }
        val source = ProgressInputStream(body.byteStream()) { count ->
            onProgress(PluginNetworkProgress(requestId, "response", count, total))
        }
        val textual = isTextual(mediaType)
        if (textual) {
            val prefix = readAtMost(source, PLUGIN_NETWORK_INLINE_BYTES + 1)
            if (prefix.size <= PLUGIN_NETWORK_INLINE_BYTES && source.exhausted) {
                val text = prefix.toString(Charsets.UTF_8)
                return PluginNetworkResponse(
                    status = response.code,
                    headers = safeResponseHeaders(response),
                    finalUrl = response.request.url.toString(),
                    redirects = redirects,
                    mediaType = mediaType,
                    text = if (mediaType?.substringBefore(';') == "application/json") null else text,
                    json = if (mediaType?.substringBefore(';') == "application/json") text else null,
                )
            }
            val combined = SequenceInputStream(
                Collections.enumeration(listOf(ByteArrayInputStream(prefix), source)),
            )
            val resource = resourceStore.putCache(
                namespace = namespace(service),
                cacheKey = "network:$requestId:${System.nanoTime()}",
                input = combined,
                mediaType = mediaType ?: "application/octet-stream",
            )
            return PluginNetworkResponse(
                response.code,
                safeResponseHeaders(response),
                response.request.url.toString(),
                redirects,
                mediaType,
                resource = resource,
            )
        }
        val resource = resourceStore.putCache(
            namespace = namespace(service),
            cacheKey = "network:$requestId:${System.nanoTime()}",
            input = source,
            mediaType = mediaType ?: "application/octet-stream",
        )
        return PluginNetworkResponse(
            response.code,
            safeResponseHeaders(response),
            response.request.url.toString(),
            redirects,
            mediaType,
            resource = resource,
        )
    }

    private suspend fun buildRequestBody(
        service: ThirdPartyService,
        body: PluginNetworkBody?,
        requestId: String,
        onProgress: (PluginNetworkProgress) -> Unit,
    ): RequestBody? = when (body) {
        null -> null
        is PluginNetworkBody.Text ->
            body.value.toRequestBody(body.mediaType.toMediaTypeOrNull())
        is PluginNetworkBody.Json ->
            body.value.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        is PluginNetworkBody.FormUrlEncoded -> FormBody.Builder().apply {
            body.fields.forEach { (name, value) -> add(name, value) }
        }.build()
        is PluginNetworkBody.Blob -> {
            val content = openBlob(service, body.handle)
            StreamingRequestBody(
                input = content.input,
                length = content.contentLength,
                mediaType = (body.mediaType ?: content.descriptor.mediaType).toMediaTypeOrNull(),
                onProgress = { count ->
                    onProgress(
                        PluginNetworkProgress(requestId, "upload", count, content.contentLength),
                    )
                },
            )
        }
        is PluginNetworkBody.Multipart -> MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .apply {
                body.parts.forEach { part ->
                    when (part) {
                        is PluginNetworkFormPart.Text ->
                            addFormDataPart(part.name, part.value)
                        is PluginNetworkFormPart.Blob -> {
                            val content = openBlob(service, part.handle)
                            addFormDataPart(
                                part.name,
                                part.fileName,
                                StreamingRequestBody(
                                    content.input,
                                    content.contentLength,
                                    (part.mediaType ?: content.descriptor.mediaType).toMediaTypeOrNull(),
                                    onProgress = { count ->
                                        onProgress(
                                            PluginNetworkProgress(
                                                requestId,
                                                "upload",
                                                count,
                                                content.contentLength,
                                            ),
                                        )
                                    },
                                ),
                            )
                        }
                    }
                }
            }
            .build()
    }

    private suspend fun openBlob(
        service: ThirdPartyService,
        handle: String,
    ): ThirdPartyResourceContent {
        if ("storage.blob@1" !in service.grantedCapabilities) {
            throw PluginNetworkException(
                "permission_denied",
                "Blob request bodies require storage.blob@1",
            )
        }
        val namespace = namespace(service)
        val descriptor = resourceStore.describe(namespace, handle)
            ?: throw PluginNetworkException("invalid_request", "Unknown blob handle")
        if (descriptor.kind != ThirdPartyResourceKind.Blob) {
            throw PluginNetworkException("invalid_request", "Handle is not a blob")
        }
        return resourceStore.open(namespace, handle)
    }

    private fun validateRequest(request: PluginNetworkRequest) {
        if (request.requestId.isBlank() || request.requestId.length > 128) {
            throw PluginNetworkException("invalid_request", "requestId must be 1-128 characters")
        }
        if (request.method.uppercase(Locale.US) !in METHODS) {
            throw PluginNetworkException("invalid_request", "Unsupported HTTP method")
        }
        if (
            request.method.uppercase(Locale.US) in setOf("GET", "HEAD") &&
            request.body != null
        ) {
            throw PluginNetworkException("invalid_request", "GET and HEAD cannot contain a body")
        }
        if (request.timeoutMs !in 1..PLUGIN_NETWORK_MAX_TIMEOUT_MS) {
            throw PluginNetworkException("invalid_request", "timeoutMs must be 1-60000")
        }
        request.headers.forEach { (name, value) -> validateHeader(name, value) }
    }

    private fun validateTarget(service: ThirdPartyService, url: String): String {
        val uri = runCatching { URI(url) }.getOrElse {
            throw PluginNetworkException("invalid_request", "Invalid request URL", cause = it)
        }
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (
            (scheme != "https" && !(allowCleartextForTests && scheme == "http")) ||
            uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null
        ) {
            throw PluginNetworkException("origin_denied", "Only public HTTPS URLs are allowed")
        }
        try {
            validateHostName(uri.host)
        } catch (error: java.net.UnknownHostException) {
            throw PluginNetworkException(
                "origin_denied",
                "Campus, private, loopback and link-local targets are forbidden",
                cause = error,
            )
        }
        val origin = originOf(url)
        if (origin !in service.manifest.connectOrigins) {
            throw PluginNetworkException("origin_denied", "Origin is not declared: $origin")
        }
        return origin
    }

    private fun validateHostName(host: String) {
        val normalized = host.lowercase(Locale.US)
        if (
            normalized.endsWith(".bjtu.edu.cn") ||
            normalized in CAMPUS_HOSTS ||
            ThirdPartyManifestValidator.isPrivateOrLocalHost(normalized)
        ) {
            throw java.net.UnknownHostException("origin_denied")
        }
    }

    private fun validateHeader(name: String, value: String) {
        val normalized = name.trim().lowercase(Locale.US)
        if (
            normalized.isBlank() ||
            normalized in FORBIDDEN_HEADERS ||
            normalized.startsWith("proxy-") ||
            normalized.startsWith("sec-") ||
            '\r' in value ||
            '\n' in value
        ) {
            throw PluginNetworkException("invalid_request", "Forbidden transport header: $name")
        }
    }

    private fun originOf(url: String): String {
        val uri = URI(url)
        val scheme = uri.scheme.lowercase(Locale.US)
        val defaultPort = if (scheme == "https") 443 else 80
        val port = uri.port.takeIf { it != -1 && it != defaultPort }?.let { ":$it" }.orEmpty()
        return "$scheme://${uri.host.lowercase(Locale.US)}$port"
    }

    private fun namespace(service: ThirdPartyService): ThirdPartyKvNamespace =
        ThirdPartyKvNamespace(service.publisherSubjectId, service.serviceId)

    private fun callKey(service: ThirdPartyService, requestId: String): String =
        "${service.publisherSubjectId}\u0000${service.serviceId}\u0000$requestId"

    private fun isTextual(mediaType: String?): Boolean {
        val type = mediaType?.substringBefore(';')?.trim()?.lowercase(Locale.US) ?: return false
        return type.startsWith("text/") ||
            type == "application/json" ||
            type.endsWith("+json") ||
            type == "application/xml" ||
            type.endsWith("+xml")
    }

    private fun safeResponseHeaders(response: Response): Map<String, List<String>> =
        response.headers.toMultimap().filterKeys { name ->
            !name.equals("Set-Cookie", ignoreCase = true) &&
                !name.equals("Set-Cookie2", ignoreCase = true)
        }

    private fun readAtMost(input: ProgressInputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(min(limit, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        while (output.size() < limit) {
            val read = input.read(buffer, 0, min(buffer.size, limit - output.size()))
            if (read < 0) {
                input.exhausted = true
                break
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    companion object {
        private val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val FORBIDDEN_HEADERS = setOf(
            "host",
            "cookie",
            "cookie2",
            "content-length",
            "connection",
            "keep-alive",
            "transfer-encoding",
            "te",
            "trailer",
            "upgrade",
        )
        private val CAMPUS_HOSTS = setOf(
            "123.121.147.7",
            "cas.bjtu.edu.cn",
            "mis.bjtu.edu.cn",
            "aa.bjtu.edu.cn",
            "mail.bjtu.edu.cn",
            "zhixing.bjtu.edu.cn",
            "job.bjtu.edu.cn",
            "bksycenter.bjtu.edu.cn",
        )

        @JvmStatic
        internal fun forTests(
            resourceStore: ThirdPartyResourceStore,
            baseClient: OkHttpClient = OkHttpClient(),
            resolver: Dns,
            addressForbidden: (InetAddress) -> Boolean = { false },
            allowCleartext: Boolean = true,
        ): PluginNetworkProvider = PluginNetworkProvider(
            resourceStore,
            baseClient,
            resolver,
            addressForbidden,
            allowCleartext,
        )
    }
}

internal fun isForbiddenPluginAddress(address: InetAddress): Boolean {
    val bytes = address.address
    val carrierGradeNat =
        bytes.size == 4 &&
            (bytes[0].toInt() and 0xff) == 100 &&
            (bytes[1].toInt() and 0xc0) == 64
    val uniqueLocalIpv6 =
        bytes.size == 16 &&
            (bytes[0].toInt() and 0xfe) == 0xfc
    return address.isAnyLocalAddress ||
        address.isLoopbackAddress ||
        address.isLinkLocalAddress ||
        address.isSiteLocalAddress ||
        address.isMulticastAddress ||
        carrierGradeNat ||
        uniqueLocalIpv6
}

private class StreamingRequestBody(
    private val input: InputStream,
    private val length: Long,
    private val mediaType: okhttp3.MediaType?,
    private val onProgress: (Long) -> Unit,
) : RequestBody() {
    override fun contentType(): okhttp3.MediaType? = mediaType
    override fun contentLength(): Long = length

    override fun writeTo(sink: okio.BufferedSink) {
        input.use { source ->
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                sink.write(buffer, 0, read)
                total += read
                onProgress(total)
            }
        }
    }
}

private class ProgressInputStream(
    private val delegate: InputStream,
    private val onProgress: (Long) -> Unit,
) : InputStream() {
    var exhausted: Boolean = false
    private var total = 0L

    override fun read(): Int {
        val value = delegate.read()
        if (value < 0) {
            exhausted = true
        } else {
            total += 1
            onProgress(total)
        }
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = delegate.read(buffer, offset, length)
        if (read < 0) {
            exhausted = true
        } else if (read > 0) {
            total += read
            onProgress(total)
        }
        return read
    }

    override fun close() = delegate.close()
}
