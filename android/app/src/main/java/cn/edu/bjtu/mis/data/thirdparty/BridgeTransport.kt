package cn.edu.bjtu.mis.data.thirdparty

import android.net.Uri
import android.os.SystemClock
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import cn.edu.bjtu.mis.data.AppJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val MAX_BRIDGE_JSON_BYTES = 512 * 1024

class BridgeTransport(
    private val service: ThirdPartyService,
    private val apiRegistry: ThirdPartyServiceApiRegistry,
    private val confirmer: ThirdPartySensitiveActionConfirmer,
    private val scope: CoroutineScope,
    private val navigationController: PluginNavigationController,
    private val closePlugin: () -> Unit,
    private val diagnostics: PluginDiagnostics,
    private val binaryDirectory: File,
    private val runtimeEnvironment: PluginWebViewRuntimeEnvironment =
        PluginWebViewPolicy.runtimeEnvironment(),
    private val backgroundRuntime: Boolean = false,
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    internal val runtimeId = UUID.randomUUID().toString()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val binaryTimeoutJobs = ConcurrentHashMap<String, Job>()
    private val binaryStaging = PluginBinaryStagingManager<PluginBridgeRequest>(binaryDirectory)
    private val eventAcknowledgements =
        ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val pendingEvents = ArrayDeque<PluginRuntimeEvent>()
    private val pendingEventsLock = Any()
    @Volatile private var awaitingChunk: PendingChunkHeader? = null
    @Volatile private var eventReplyProxy: JavaScriptReplyProxy? = null
    @Volatile private var negotiatedBinaryTransport: PluginBinaryTransport? = null

    fun onMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
    ) {
        val localOrigin = ThirdPartyServiceSandbox.originFor(
            service.serviceId,
            service.publisherSubjectId,
        )
        if (
            !ThirdPartyWebViewAccessPolicy.isTrustedBridgeMessage(
                isMainFrame,
                sourceOrigin.toString(),
                localOrigin,
            )
        ) {
            diagnostics.record("warning", "bridge_rejected", code = "origin_denied")
            return
        }
        eventReplyProxy = replyProxy
        flushPendingEvents()
        when (message.type) {
            WebMessageCompat.TYPE_STRING ->
                handleString(message.data.orEmpty(), sourceOrigin.toString(), replyProxy)
            WebMessageCompat.TYPE_ARRAY_BUFFER ->
                handleArrayBuffer(message.arrayBuffer ?: ByteArray(0), replyProxy)
            else -> diagnostics.record("warning", "bridge_rejected", code = "invalid_request")
        }
    }

    fun sendEvent(event: PluginRuntimeEvent) {
        // A delayed tick is stale work: never queue timers while a document is still loading.
        if (event.capability == PLUGIN_BACKGROUND_TASK_CAPABILITY && eventReplyProxy == null) return
        if (eventReplyProxy == null) {
            synchronized(pendingEventsLock) {
                if (eventReplyProxy == null) {
                    if (pendingEvents.size == MAX_PENDING_EVENTS) pendingEvents.removeFirst()
                    pendingEvents.addLast(event)
                    return
                }
            }
        }
        postEvent(event, requiresAcknowledgement = false)
    }

    private fun flushPendingEvents() {
        val queued = synchronized(pendingEventsLock) {
            buildList {
                while (pendingEvents.isNotEmpty()) add(pendingEvents.removeFirst())
            }
        }
        queued.forEach { event -> postEvent(event, requiresAcknowledgement = false) }
    }

    suspend fun sendEventAwaitingAcknowledgement(
        event: PluginRuntimeEvent,
        timeoutMs: Long = 150L,
    ): Boolean {
        val requestId = UUID.randomUUID().toString()
        val acknowledgement = CompletableDeferred<Boolean>()
        eventAcknowledgements[requestId] = acknowledgement
        if (!postEvent(event.copy(requestId = requestId), requiresAcknowledgement = true)) {
            eventAcknowledgements.remove(requestId)
            return false
        }
        return try {
            withTimeoutOrNull(timeoutMs) { acknowledgement.await() } ?: false
        } finally {
            eventAcknowledgements.remove(requestId, acknowledgement)
        }
    }

    private fun postEvent(
        event: PluginRuntimeEvent,
        requiresAcknowledgement: Boolean,
    ): Boolean {
        val proxy = eventReplyProxy ?: return false
        val validationErrors = ThirdPartyCapabilityRegistry.validateEvent(
            event.capability,
            event.event,
            event.data,
        )
        if (validationErrors.isNotEmpty()) {
            diagnostics.record(
                "warning",
                "event_rejected",
                capability = event.capability,
                code = "invalid_request",
            )
            return false
        }
        val envelope = buildJsonObject {
            put("protocolVersion", THIRD_PARTY_BRIDGE_PROTOCOL_VERSION)
            put("eventId", UUID.randomUUID().toString())
            put("capability", event.capability)
            put("event", event.event)
            event.requestId?.let { put("requestId", it) }
            put("data", event.data)
            if (requiresAcknowledgement) put("requiresAcknowledgement", true)
        }
        scope.launch(Dispatchers.Main.immediate) {
            proxy.postMessage(envelope.toString())
        }
        return true
    }

    fun close() {
        AndroidAccessibilityController.detachRuntime(runtimeId)
        AndroidNativeEventController.detachRuntime(runtimeId)
        PluginBackgroundTasks.detach(runtimeId)
        PluginSessionKeepAlive.detach(runtimeId)
        jobs.values.forEach(Job::cancel)
        jobs.clear()
        binaryTimeoutJobs.values.forEach(Job::cancel)
        binaryTimeoutJobs.clear()
        binaryStaging.close()
        eventAcknowledgements.values.forEach { it.complete(false) }
        eventAcknowledgements.clear()
        awaitingChunk = null
        eventReplyProxy = null
        synchronized(pendingEventsLock) { pendingEvents.clear() }
        scope.cancel()
    }

    private fun handleString(
        json: String,
        callerUrl: String,
        replyProxy: JavaScriptReplyProxy,
    ) {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_BRIDGE_JSON_BYTES) {
            postError(replyProxy, "", "invalid_request", "Bridge request exceeds 512 KiB")
            return
        }
        val envelope = runCatching { AppJson.parseToJsonElement(json).jsonObject }
            .getOrElse {
                postError(replyProxy, "", "invalid_request", "Bridge request is not valid JSON")
                return
            }
        val protocolVersion = envelope.strictInt("protocolVersion")
        val kind = envelope.strictString("kind")
        val requestId = envelope.strictString("requestId").orEmpty()
        if (protocolVersion != THIRD_PARTY_BRIDGE_PROTOCOL_VERSION || !validRequestId(requestId)) {
            if (
                kind == "binaryChunk" &&
                validRequestId(requestId) &&
                binaryStaging.transportFor(requestId) != null
            ) {
                failBinaryRequest(
                    requestId,
                    replyProxy,
                    "invalid_request",
                    "Invalid binary chunk envelope",
                )
            } else {
                postError(replyProxy, requestId, "invalid_request", "Invalid bridge envelope")
            }
            return
        }
        when (kind) {
            "cancel" -> {
                cancelRequest(requestId)
                return
            }
            "eventAck" -> {
                val handled = envelope.strictBoolean("handled")
                val acknowledgement = eventAcknowledgements[requestId]
                if (handled == null || acknowledgement == null) {
                    diagnostics.record(
                        "warning",
                        "event_ack_rejected",
                        requestId = requestId,
                        code = "invalid_request",
                    )
                    return
                }
                if (eventAcknowledgements.remove(requestId, acknowledgement)) {
                    acknowledgement.complete(handled)
                }
                return
            }
            "binaryChunk" -> {
                handleBinaryChunkEnvelope(envelope, requestId, replyProxy)
                return
            }
        }
        val capability = envelope.strictString("capability").orEmpty()
        val method = envelope.strictString("method").orEmpty()
        val params = envelope["params"] as? JsonObject
        if (
            capability.isBlank() ||
            method.isBlank() ||
            capability.length > 128 ||
            method.length > 128 ||
            params == null
        ) {
            postError(replyProxy, requestId, "invalid_request", "Invalid capability request")
            return
        }
        if (!PluginWebViewPolicy.isCapabilityAvailable(capability, runtimeEnvironment)) {
            postError(
                replyProxy,
                requestId,
                "capability_unavailable",
                "Required WebView feature is unavailable",
            )
            return
        }
        if (jobs.containsKey(requestId) || binaryStaging.transportFor(requestId) != null) {
            postError(replyProxy, requestId, "invalid_request", "Duplicate active request ID")
            return
        }
        val request = PluginBridgeRequest(
            requestId,
            capability,
            method,
            params,
            callerUrl,
            replyProxy,
            deadlineAtMillis = null,
        )
        val binary = envelope["binary"] as? JsonObject
        if (binary == null) {
            execute(request, null)
            return
        }
        val transportName = binary.strictString("transport")
        val transport = PluginBinaryTransport.fromWireName(transportName)
        val sha256 = binary.strictString("sha256")
        if (transport == null || sha256 == null) {
            postError(
                replyProxy,
                requestId,
                "capability_unavailable",
                "Legacy binary declarations are unsupported; upgrade @bjtu-mis/plugin-sdk to 0.2.0",
            )
            return
        }
        val negotiated = negotiatedBinaryTransport
        if (negotiated == null) {
            postError(
                replyProxy,
                requestId,
                "capability_unavailable",
                "Binary transport is not negotiated; call runtime.lifecycle@1#handshake first",
            )
            return
        }
        if (transport != negotiated || transport !in runtimeEnvironment.binaryTransports) {
            postError(
                replyProxy,
                requestId,
                "invalid_request",
                "Binary declaration does not match the negotiated transport",
            )
            return
        }
        if (
            method != "put" ||
            capability !in setOf("storage.blob@1", "cache.resource@1")
        ) {
            postError(replyProxy, requestId, "invalid_request", "Binary payload is not allowed for this route")
            return
        }
        if (capability !in service.manifest.requiredCapabilities + service.manifest.optionalCapabilities) {
            postError(replyProxy, requestId, "capability_unavailable", "Plugin did not declare capability")
            return
        }
        if (capability !in service.grantedCapabilities) {
            postError(replyProxy, requestId, "permission_denied", "Plugin capability is not granted")
            return
        }
        val size = binary.strictLong("size")
        val chunks = binary.strictInt("chunks")
        val maxBytes = when (capability) {
            "storage.blob@1" -> THIRD_PARTY_BLOB_ITEM_BYTES
            else -> THIRD_PARTY_CACHE_ITEM_BYTES
        }
        if (
            size == null ||
            chunks == null ||
            params.strictLong("size") != size ||
            ThirdPartyCapabilityRegistry.validateRequest(capability, method, params).isNotEmpty()
        ) {
            postError(replyProxy, requestId, "invalid_request", "Invalid binary declaration")
            return
        }
        val binaryRequest = request.copy(deadlineAtMillis = capabilityDeadlineAt(capability))
        val result = try {
            binaryStaging.begin(
                requestId = requestId,
                owner = binaryRequest,
                declaration = PluginBinaryDeclaration(
                    transport = transport,
                    size = size,
                    chunks = chunks,
                    sha256 = sha256,
                ),
                itemLimitBytes = maxBytes,
            )
        } catch (error: PluginBinaryStagingException) {
            postError(replyProxy, requestId, error.code, error.message)
            return
        }
        when (result) {
            is PluginBinaryStagingResult.Pending -> scheduleBinaryTimeout(result.owner)
            is PluginBinaryStagingResult.Complete -> execute(result.owner, result.payload)
        }
    }

    private fun handleBinaryChunkEnvelope(
        envelope: JsonObject,
        requestId: String,
        replyProxy: JavaScriptReplyProxy,
    ) {
        val index = envelope.strictInt("index")
        val last = envelope.strictBoolean("last")
        val transport = binaryStaging.transportFor(requestId)
        if (index == null || last == null || transport == null) {
            failBinaryRequest(
                requestId,
                replyProxy,
                "invalid_request",
                "Invalid or unknown binary chunk header",
            )
            return
        }
        if (transport == PluginBinaryTransport.ArrayBuffer) {
            if (envelope["payload"] != null || awaitingChunk != null) {
                failBinaryRequest(
                    requestId,
                    replyProxy,
                    "invalid_request",
                    "Invalid ArrayBuffer chunk sequence",
                )
                return
            }
            awaitingChunk = PendingChunkHeader(requestId, index, last)
            return
        }
        val payload = envelope.strictString("payload")
        if (payload == null) {
            failBinaryRequest(
                requestId,
                replyProxy,
                "invalid_request",
                "Base64URL compatibility chunks require a string payload",
            )
            return
        }
        val result = try {
            binaryStaging.receiveBase64Url(requestId, index, last, payload)
        } catch (error: PluginBinaryStagingException) {
            failBinaryRequest(requestId, replyProxy, error.code, error.message)
            return
        }
        handleBinaryStagingResult(requestId, result)
    }

    private fun handleArrayBuffer(
        bytes: ByteArray,
        replyProxy: JavaScriptReplyProxy,
    ) {
        val header = awaitingChunk.also { awaitingChunk = null } ?: run {
            diagnostics.record("warning", "binary_rejected", code = "invalid_request")
            return
        }
        val result = try {
            binaryStaging.receiveArrayBuffer(
                header.requestId,
                header.index,
                header.last,
                bytes,
            )
        } catch (error: PluginBinaryStagingException) {
            failBinaryRequest(header.requestId, replyProxy, error.code, error.message)
            return
        }
        handleBinaryStagingResult(header.requestId, result)
    }

    private fun handleBinaryStagingResult(
        requestId: String,
        result: PluginBinaryStagingResult<PluginBridgeRequest>,
    ) {
        if (result.acknowledgeChunk) {
            postChunkAcknowledgement(
                result.owner.replyProxy,
                requestId,
                requireNotNull(result.acceptedChunkIndex),
            )
        }
        if (result is PluginBinaryStagingResult.Complete) {
            execute(result.owner, result.payload)
        }
    }

    private fun scheduleBinaryTimeout(request: PluginBridgeRequest) {
        val deadline = request.deadlineAtMillis ?: return
        val timeoutJob = scope.launch {
            delay((deadline - nowMillis()).coerceAtLeast(1L))
            if (binaryStaging.cancel(request.requestId)) {
                if (awaitingChunk?.requestId == request.requestId) awaitingChunk = null
                postError(
                    request.replyProxy,
                    request.requestId,
                    "request_timeout",
                    "Binary upload exceeded its generated capability deadline",
                )
            }
        }
        binaryTimeoutJobs.put(request.requestId, timeoutJob)?.cancel()
        timeoutJob.invokeOnCompletion {
            binaryTimeoutJobs.remove(request.requestId, timeoutJob)
        }
    }

    private fun failBinaryRequest(
        requestId: String,
        replyProxy: JavaScriptReplyProxy,
        code: String,
        message: String,
    ) {
        binaryStaging.cancel(requestId)
        binaryTimeoutJobs.remove(requestId)?.cancel()
        if (awaitingChunk?.requestId == requestId) awaitingChunk = null
        postError(replyProxy, requestId, code, message)
    }

    private fun postChunkAcknowledgement(
        replyProxy: JavaScriptReplyProxy,
        requestId: String,
        index: Int,
    ) {
        scope.launch(Dispatchers.Main.immediate) {
            replyProxy.postMessage(
                buildJsonObject {
                    put("protocolVersion", THIRD_PARTY_BRIDGE_PROTOCOL_VERSION)
                    put("kind", "binaryChunkAck")
                    put("requestId", requestId)
                    put("index", index)
                }.toString(),
            )
        }
    }

    private fun capabilityDeadlineAt(capability: String): Long? {
        val timeoutMs = ThirdPartyCapabilityRegistry.get(capability)?.timeoutMs ?: return null
        if (timeoutMs <= 0L) return null
        val now = nowMillis()
        return if (timeoutMs > Long.MAX_VALUE - now) Long.MAX_VALUE else now + timeoutMs
    }

    private fun execute(request: PluginBridgeRequest, binary: PluginBinaryPayload?) {
        binaryTimeoutJobs.remove(request.requestId)?.cancel()
        val startedAt = SystemClock.elapsedRealtime()
        val job = scope.launch {
            try {
                val remainingTimeoutMs = request.deadlineAtMillis?.let { deadline ->
                    (deadline - nowMillis()).coerceAtLeast(0L)
                }
                val response = apiRegistry.invoke(
                    service = service,
                    capability = request.capability,
                    method = request.method,
                    params = request.params,
                    binary = binary,
                    confirmer = confirmer,
                    currentPageUrl = request.callerUrl,
                    openExternal = navigationController::openFromCapability,
                    closePlugin = closePlugin,
                    eventSink = ::sendEvent,
                    requestId = request.requestId,
                    runtimeEnvironment = runtimeEnvironment,
                    runtimeId = runtimeId,
                    backgroundRuntime = backgroundRuntime,
                    timeoutMsOverride = remainingTimeoutMs,
                )
                val ok = response["ok"]?.jsonPrimitive?.contentOrNull == "true"
                if (
                    request.capability == "runtime.lifecycle@1" &&
                    request.method == "handshake"
                ) {
                    negotiatedBinaryTransport = if (ok) {
                        runtimeEnvironment.preferredBinaryTransport
                    } else {
                        null
                    }
                }
                diagnostics.record(
                    if (ok) "info" else "warning",
                    "capability_invoke",
                    requestId = request.requestId,
                    capability = request.capability,
                    method = request.method,
                    code = (response["error"] as? JsonObject)
                        ?.get("code")
                        ?.jsonPrimitive
                        ?.contentOrNull,
                    durationMs = SystemClock.elapsedRealtime() - startedAt,
                )
                withContext(Dispatchers.Main.immediate) {
                    request.replyProxy.postMessage(
                        normalizeResponse(request.requestId, response).toString(),
                    )
                }
            } finally {
                binary?.close()
            }
        }
        jobs[request.requestId] = job
        job.invokeOnCompletion {
            jobs.remove(request.requestId, job)
            binary?.close()
        }
    }

    private fun cancelRequest(requestId: String) {
        binaryStaging.cancel(requestId)
        binaryTimeoutJobs.remove(requestId)?.cancel()
        if (awaitingChunk?.requestId == requestId) awaitingChunk = null
        jobs.remove(requestId)?.cancel()
        apiRegistry.cancel(service, requestId)
        diagnostics.record(
            "info",
            "request_cancelled",
            requestId = requestId,
            code = "user_cancelled",
        )
    }

    private fun postError(
        replyProxy: JavaScriptReplyProxy,
        requestId: String,
        code: String,
        message: String,
    ) {
        diagnostics.record("warning", "bridge_error", requestId = requestId, code = code)
        scope.launch(Dispatchers.Main.immediate) {
            replyProxy.postMessage(
                buildJsonObject {
                    put("protocolVersion", THIRD_PARTY_BRIDGE_PROTOCOL_VERSION)
                    put("requestId", requestId)
                    put("ok", false)
                    put("error", buildJsonObject {
                        put("code", code)
                        put("message", message)
                        put("retryable", false)
                    })
                }.toString(),
            )
        }
    }

    private fun normalizeResponse(requestId: String, response: JsonObject): JsonObject =
        buildJsonObject {
            put("protocolVersion", THIRD_PARTY_BRIDGE_PROTOCOL_VERSION)
            put("requestId", requestId)
            val ok = response["ok"]?.jsonPrimitive?.contentOrNull == "true"
            put("ok", ok)
            if (ok) {
                put("result", response["result"] ?: JsonObject(emptyMap()))
            } else {
                put(
                    "error",
                    response["error"] ?: buildJsonObject {
                        put("code", "capability_unavailable")
                        put("message", "Capability invocation failed")
                        put("retryable", false)
                    },
                )
            }
        }

    private fun validRequestId(value: String): Boolean =
        value.isNotBlank() && value.length <= 128 && value.none { it.code < 0x20 }

    companion object {
        const val OBJECT_NAME = "BjtuPluginNativeV2"
        private const val MAX_PENDING_EVENTS = 128

        fun documentStartScript(binarySupported: Boolean): String = """
            (function () {
              if (window.__BJTU_MANAGED_STORAGE_ONLY__ !== true) return;
              if (window.__BJTU_PLUGIN_BRIDGE_V2__) return;
              var nativeBridge = window.$OBJECT_NAME;
              var listeners = new Set();
              var parse = function (value) {
                if (typeof value !== 'string') return value;
                try { return JSON.parse(value); } catch (_) { return null; }
              };
              var bridge = Object.freeze({
                // Retained only so SDK 0.1.x can keep non-binary calls usable
                // and fail its own binary calls on compatibility WebViews.
                binarySupported: ${if (binarySupported) "true" else "false"},
                postMessage: function (message) {
                  if (!nativeBridge || typeof nativeBridge.postMessage !== 'function') {
                    throw new Error('BJTU plugin transport unavailable');
                  }
                  if (
                    message &&
                    message.kind === 'binaryChunk' &&
                    message.payload instanceof ArrayBuffer
                  ) {
                    var metadata = {
                      protocolVersion: message.protocolVersion,
                      kind: message.kind,
                      requestId: message.requestId,
                      index: message.index,
                      last: message.last
                    };
                    nativeBridge.postMessage(JSON.stringify(metadata));
                    nativeBridge.postMessage(message.payload);
                    return;
                  }
                  nativeBridge.postMessage(JSON.stringify(message));
                },
                addEventListener: function (listener) {
                  listeners.add(listener);
                  return function () { listeners.delete(listener); };
                }
              });
              Object.defineProperty(window, '__BJTU_PLUGIN_BRIDGE_V2__', {
                value: bridge,
                configurable: false,
                enumerable: false,
                writable: false
              });
              if (nativeBridge) {
                nativeBridge.onmessage = function (event) {
                  var message = parse(event.data);
                  if (!message) return;
                  listeners.forEach(function (listener) {
                    try { listener(message); } catch (_) {}
                  });
                };
              }
            })();
        """.trimIndent()
    }

    private data class PluginBridgeRequest(
        val requestId: String,
        val capability: String,
        val method: String,
        val params: JsonObject,
        val callerUrl: String,
        val replyProxy: JavaScriptReplyProxy,
        val deadlineAtMillis: Long?,
    )

    private data class PendingChunkHeader(
        val requestId: String,
        val index: Int,
        val last: Boolean,
    )
}

private fun JsonObject.strictString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

private fun JsonObject.strictInt(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull

private fun JsonObject.strictLong(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull

private fun JsonObject.strictBoolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull
