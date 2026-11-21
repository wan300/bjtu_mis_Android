package cn.edu.bjtu.mis.data.network

import android.content.Context
import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.perf.PerfTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

data class TextResponse(
    val url: String,
    val code: Int,
    val body: String,
    val headers: Headers,
)

data class BytesResponse(
    val url: String,
    val code: Int,
    val body: ByteArray,
    val headers: Headers,
)

data class FileResponse(
    val url: String,
    val code: Int,
    val file: File,
    val headers: Headers,
    val bytesWritten: Long,
)

data class MultipartFilePart(
    val formName: String,
    val fileName: String,
    val content: ByteArray,
    val contentType: String? = null,
)

class BjtuHttpClient(
    val cookieJar: AppCookieJar,
    userAgent: String = DEFAULT_USER_AGENT,
    dns: BjtuDns = BjtuDns(),
    context: Context? = null,
) {
    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .dns(dns)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", userAgent)
                .build()
            chain.proceed(request)
        }
        .build()
        .let { if (context == null) it else it.withMisIntermediate(context) }

    private val noRedirectClient: OkHttpClient by lazy {
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    suspend fun getText(
        url: String,
        params: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        timeoutMillis: Long? = null,
    ): TextResponse = executeText(
        Request.Builder()
            .url(buildUrl(url, params))
            .headers(headers.toHeaders())
            .get()
            .build(),
        timeoutMillis = timeoutMillis,
    )

    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        params: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        timeoutMillis: Long? = null,
    ): TextResponse = postForm(url, form.toList(), params, headers, timeoutMillis)

    suspend fun postForm(
        url: String,
        form: List<Pair<String, String>>,
        params: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        timeoutMillis: Long? = null,
    ): TextResponse {
        val body = FormBody.Builder().apply {
            form.forEach { (key, value) -> add(key, value) }
        }.build()
        return executeText(
            Request.Builder()
                .url(buildUrl(url, params))
                .headers(headers.toHeaders())
                .post(body)
                .build(),
            timeoutMillis = timeoutMillis,
        )
    }

    suspend fun postMultipart(
        url: String,
        files: List<MultipartFilePart>,
        params: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        timeoutMillis: Long? = null,
    ): TextResponse {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .apply {
                files.forEach { file ->
                    addFormDataPart(
                        file.formName,
                        file.fileName,
                        file.content.toRequestBody(file.contentType?.toMediaTypeOrNull()),
                    )
                }
            }
            .build()
        return executeText(
            Request.Builder()
                .url(buildUrl(url, params))
                .headers(headers.toHeaders())
                .post(body)
                .build(),
            timeoutMillis = timeoutMillis,
        )
    }

    suspend fun postJson(
        url: String,
        json: String,
        params: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        contentType: String = "application/json; charset=utf-8",
        timeoutMillis: Long? = null,
    ): TextResponse = executeText(
        Request.Builder()
            .url(buildUrl(url, params))
            .headers(headers.toHeaders())
            .post(json.toRequestBody(contentType.toMediaType()))
            .build(),
        timeoutMillis = timeoutMillis,
    )

    suspend fun getJson(
        url: String,
        params: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        timeoutMillis: Long? = null,
    ): JsonElement = AppJson.parseToJsonElement(getText(url, params, headers, timeoutMillis).body)

    suspend fun getBytes(
        url: String,
        params: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        timeoutMillis: Long? = null,
        maxBytes: Long? = null,
        followRedirects: Boolean = true,
        onBytesRead: ((Long) -> Unit)? = null,
    ): BytesResponse = withContext(Dispatchers.IO) {
        require(maxBytes == null || maxBytes >= 0L) { "maxBytes must not be negative" }
        val request = Request.Builder()
            .url(buildUrl(url, params))
            .headers(headers.toHeaders())
            .get()
            .build()
        val response = executeCall(request, timeoutMillis, followRedirects)
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} for ${it.request.url}")
            val body = it.body ?: throw IOException("Empty response body for ${it.request.url}")
            val contentLength = body.contentLength()
            if (maxBytes != null && contentLength >= 0L && contentLength > maxBytes) {
                throw IOException("Response body exceeds ${maxBytes} bytes for ${it.request.url}")
            }
            BytesResponse(
                url = it.request.url.toString(),
                code = it.code,
                body = body.readBytes(maxBytes, onBytesRead),
                headers = it.headers,
            )
        }
    }

    suspend fun downloadToFile(
        url: String,
        target: File,
        params: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        timeoutMillis: Long? = null,
    ): FileResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(buildUrl(url, params))
            .headers(headers.toHeaders())
            .get()
            .build()
        val response = executeCall(request, timeoutMillis)
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} for ${it.request.url}")
            val body = it.body ?: throw IOException("Empty response body for ${it.request.url}")
            target.parentFile?.mkdirs()

            val partial = File(target.absolutePath + ".part")
            if (partial.exists() && !partial.delete()) {
                throw IOException("Cannot replace partial download ${partial.absolutePath}")
            }

            var completed = false
            var replacementStarted = false
            val bytesWritten = try {
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }.also {
                    if (target.exists() && !target.delete()) {
                        throw IOException("Cannot replace existing file ${target.absolutePath}")
                    }
                    replacementStarted = true
                    if (!partial.renameTo(target)) {
                        partial.copyTo(target, overwrite = true)
                        if (!partial.delete()) partial.deleteOnExit()
                    }
                    completed = true
                }
            } finally {
                if (!completed) {
                    partial.delete()
                    if (replacementStarted) target.delete()
                }
            }

            FileResponse(
                url = it.request.url.toString(),
                code = it.code,
                file = target,
                headers = it.headers,
                bytesWritten = bytesWritten,
            )
        }
    }

    private suspend fun executeText(request: Request, timeoutMillis: Long? = null): TextResponse = withContext(Dispatchers.IO) {
        val response = executeCall(request, timeoutMillis)
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} for ${it.request.url}")
            TextResponse(
                url = it.request.url.toString(),
                code = it.code,
                body = it.body?.string().orEmpty(),
                headers = it.headers,
            )
        }
    }

    private fun executeCall(request: Request, timeoutMillis: Long? = null, followRedirects: Boolean = true) =
        try {
            val startedAt = PerfTrace.nowMillis()
            val transport = if (followRedirects) client else noRedirectClient
            transport.newCall(request).also { call ->
                timeoutMillis?.takeIf { it > 0 }?.let { call.timeout().timeout(it, TimeUnit.MILLISECONDS) }
            }.execute().also { response ->
                PerfTrace.mark(
                    "http",
                    "${request.method} ${request.url.host}${request.url.encodedPath} ${response.code} ${PerfTrace.nowMillis() - startedAt}ms",
                )
            }
        } catch (error: UnknownHostException) {
            throw IOException(
                "无法解析域名 ${request.url.host}。请检查模拟器网络、系统 Private DNS，或连接可访问北交大 MIS 的网络后重试。",
                error,
            )
        } catch (error: SocketTimeoutException) {
            throw IOException("连接 ${request.url.host} 超时，请检查网络后重试。", error)
        }

    private fun buildUrl(url: String, params: Map<String, String?>): String {
        if (params.isEmpty()) return url
        val builder = url.toHttpUrl().newBuilder()
        params.forEach { (key, value) ->
            if (!value.isNullOrBlank()) builder.setQueryParameter(key, value)
        }
        return builder.build().toString()
    }

    private fun Map<String, String>.toHeaders(): Headers =
        Headers.Builder().also { builder ->
            forEach { (key, value) -> builder.add(key, value) }
        }.build()

    private fun okhttp3.ResponseBody.readBytes(maxBytes: Long?, onBytesRead: ((Long) -> Unit)?): ByteArray {
        if (maxBytes == null && onBytesRead == null) return bytes()
        val output = ByteArrayOutputStream(minOf(maxBytes ?: 64 * 1024L, 64 * 1024L).toInt())
        byteStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                // Read at most one extra byte to detect an oversized chunked body.
                val readLimit = maxBytes?.let { minOf(buffer.size.toLong(), it - total) }?.toInt()
                    ?: buffer.size
                val read = input.read(buffer, 0, if (readLimit == 0) 1 else readLimit)
                if (read < 0) break
                total += read
                onBytesRead?.invoke(read.toLong())
                if (maxBytes != null && total > maxBytes) {
                    throw IOException("Response body exceeds $maxBytes bytes")
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    companion object {
        const val LIST_REQUEST_TIMEOUT_MILLIS = 15_000L

        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Mobile Safari/537.36"
    }
}
