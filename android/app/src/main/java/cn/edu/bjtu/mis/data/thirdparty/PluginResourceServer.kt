package cn.edu.bjtu.mis.data.thirdparty

import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File

class PluginResourceServer(
    private val service: ThirdPartyService,
    private val resourceStore: ThirdPartyResourceStore,
    private val developmentRuntime: PluginDevelopmentRuntime? = null,
) {
    private val installRoot = File(service.installDir)
    private val namespace = ThirdPartyKvNamespace(service.publisherSubjectId, service.serviceId)
    private val browserResourceOrigins = (
        service.manifest.connectOrigins +
            service.manifest.mediaOrigins +
            service.manifest.frameOrigins.takeIf {
                "remote.frame@1" in service.grantedCapabilities
            }.orEmpty()
        ).toSet()

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        val resourceHandle = ThirdPartyServiceSandbox.resourceHandleFor(
            url,
            service.serviceId,
            service.publisherSubjectId,
        )
        if (resourceHandle != null) return serveResource(request, resourceHandle)
        if (
            developmentRuntime != null &&
            ThirdPartyServiceSandbox.isServiceSandboxUrl(
                url,
                service.serviceId,
                service.publisherSubjectId,
            )
        ) {
            developmentRuntime.intercept(request)?.let { return it }
        }

        return when (
            val resolution = ThirdPartyServiceSandbox.resolveLocalResource(
                url = url,
                serviceId = service.serviceId,
                publisherSubjectId = service.publisherSubjectId,
                installDir = installRoot,
                entrypoint = service.manifest.entrypoint,
            )
        ) {
            is ThirdPartySandboxResourceResolution.Found ->
                serveLocalFile(request, resolution.resource.file)
            ThirdPartySandboxResourceResolution.NotFound -> error(404, "Not Found")
            ThirdPartySandboxResourceResolution.Blocked -> error(403, "Forbidden")
            ThirdPartySandboxResourceResolution.NotSandboxUrl ->
                if (ThirdPartyWebViewAccessPolicy.origin(url) in browserResourceOrigins) {
                    null
                } else {
                    error(403, "Forbidden")
                }
        }
    }

    private fun serveResource(
        request: WebResourceRequest,
        handle: String,
    ): WebResourceResponse = runBlocking(Dispatchers.IO) {
        val method = request.method.uppercase()
        if (method !in setOf("GET", "HEAD")) return@runBlocking error(405, "Method Not Allowed")
        val descriptor = resourceStore.describe(namespace, handle)
            ?: return@runBlocking error(404, "Not Found")
        if (!canReadResource(descriptor)) {
            return@runBlocking error(403, "Forbidden")
        }
        val rangeHeader = request.requestHeaders.entries
            .firstOrNull { (name, _) -> name.equals("Range", ignoreCase = true) }
            ?.value
        if (descriptor.size == 0L) {
            if (rangeHeader != null) {
                return@runBlocking rangeNotSatisfiable(0)
            }
            val headers = PluginWebViewPolicy.securityHeaders(
                service.manifest,
                service.grantedCapabilities,
            ).toMutableMap().apply {
                put("Accept-Ranges", "bytes")
                put("Content-Length", "0")
                put("ETag", "\"${descriptor.digestSha256}\"")
            }
            return@runBlocking WebResourceResponse(
                descriptor.mediaType.substringBefore(';'),
                encodingFor(descriptor.mediaType),
                200,
                "OK",
                headers,
                ByteArrayInputStream(ByteArray(0)),
            )
        }
        val range = parseRange(rangeHeader, descriptor.size)
            ?: if (rangeHeader != null) {
                return@runBlocking rangeNotSatisfiable(descriptor.size)
            } else {
                0L..(descriptor.size - 1)
            }
        val partial = range.first != 0L || range.last != descriptor.size - 1
        val headers = PluginWebViewPolicy.securityHeaders(
            service.manifest,
            service.grantedCapabilities,
        ).toMutableMap().apply {
            put("Accept-Ranges", "bytes")
            put("Content-Length", (range.last - range.first + 1).toString())
            put("ETag", "\"${descriptor.digestSha256}\"")
            if (partial) put("Content-Range", "bytes ${range.first}-${range.last}/${descriptor.size}")
        }
        val stream = if (method == "HEAD") {
            ByteArrayInputStream(ByteArray(0))
        } else {
            resourceStore.open(namespace, handle, range.first, range.last).input
        }
        WebResourceResponse(
            descriptor.mediaType.substringBefore(';'),
            encodingFor(descriptor.mediaType),
            if (partial) 206 else 200,
            if (partial) "Partial Content" else "OK",
            headers,
            stream,
        )
    }

    private fun serveLocalFile(
        request: WebResourceRequest,
        file: File,
    ): WebResourceResponse {
        val method = request.method.uppercase()
        if (method !in setOf("GET", "HEAD")) return error(405, "Method Not Allowed")
        return runCatching {
            val mimeType = mimeTypeFor(file)
            val headers = PluginWebViewPolicy.securityHeaders(
                service.manifest,
                service.grantedCapabilities,
            ).toMutableMap().apply {
                put("Content-Length", file.length().toString())
            }
            WebResourceResponse(
                mimeType,
                encodingFor(mimeType),
                200,
                "OK",
                headers,
                if (method == "HEAD") {
                    ByteArrayInputStream(ByteArray(0))
                } else {
                    file.inputStream()
                },
            )
        }.getOrElse {
            error(404, "Not Found")
        }
    }

    private fun rangeNotSatisfiable(size: Long): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "UTF-8",
            416,
            "Range Not Satisfiable",
            PluginWebViewPolicy.securityHeaders(
                service.manifest,
                service.grantedCapabilities,
            ) +
                ("Content-Range" to "bytes */$size"),
            ByteArrayInputStream(ByteArray(0)),
        )

    private fun error(status: Int, reason: String): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "UTF-8",
            status,
            reason,
            PluginWebViewPolicy.securityHeaders(
                service.manifest,
                service.grantedCapabilities,
            ),
            ByteArrayInputStream(reason.toByteArray()),
        )

    private fun parseRange(value: String?, size: Long): LongRange? {
        if (value == null || size <= 0 || !value.startsWith("bytes=")) return null
        val specification = value.removePrefix("bytes=").trim()
        if (',' in specification) return null
        val startText = specification.substringBefore('-')
        val endText = specification.substringAfter('-', missingDelimiterValue = "")
        return when {
            startText.isNotBlank() -> {
                val start = startText.toLongOrNull() ?: return null
                val end = endText.toLongOrNull() ?: (size - 1)
                if (start < 0 || start >= size || end < start) null else start..minOf(end, size - 1)
            }
            endText.isNotBlank() -> {
                val suffix = endText.toLongOrNull() ?: return null
                if (suffix <= 0) null else maxOf(0, size - suffix)..(size - 1)
            }
            else -> null
        }
    }

    private fun mimeTypeFor(file: File): String {
        val extension = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when (extension) {
                "html", "htm" -> "text/html"
                "js", "mjs" -> "application/javascript"
                "css" -> "text/css"
                "json", "map" -> "application/json"
                "svg" -> "image/svg+xml"
                "wasm" -> "application/wasm"
                else -> "application/octet-stream"
            }
    }

    private fun encodingFor(mimeType: String): String? =
        if (
            mimeType.startsWith("text/") ||
            mimeType.startsWith("application/json") ||
            mimeType.startsWith("application/javascript") ||
            mimeType.startsWith("image/svg+xml")
        ) {
            "UTF-8"
        } else {
            null
        }

    private fun canReadResource(descriptor: ThirdPartyResourceDescriptor): Boolean =
        when (descriptor.kind) {
            ThirdPartyResourceKind.Blob ->
                service.grantedCapabilities.any {
                    it == "storage.blob@1" || it == "storage.kv@2"
                }
            ThirdPartyResourceKind.Cache ->
                service.grantedCapabilities.any {
                    it == "cache.resource@1" || it == "network.request@1" || it == "network.request@2"
                }
        }
}
