package cn.edu.bjtu.mis.data.thirdparty

import cn.edu.bjtu.mis.data.repository.MailRepository
import cn.edu.bjtu.mis.data.repository.MailUploadFile
import cn.edu.bjtu.mis.data.repository.ModuleLoadStrategy
import cn.edu.bjtu.mis.data.repository.ModuleRepository
import cn.edu.bjtu.mis.model.HomeworkUploadFile
import cn.edu.bjtu.mis.model.MailComposeRequest
import cn.edu.bjtu.mis.model.MailComposeAttachment
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.model.UserCourseDraft
import cn.edu.bjtu.mis.model.UserCourseDurationType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

fun interface ThirdPartySensitiveActionConfirmer {
    suspend fun confirm(title: String, message: String): Boolean
}

data class PluginRuntimeEvent(
    val capability: String,
    val event: String,
    val requestId: String? = null,
    val data: JsonElement = JsonObject(emptyMap()),
)

class ThirdPartyServiceApiRegistry(
    private val moduleRepository: ModuleRepository?,
    private val mailRepository: MailRepository?,
    private val kvStore: ThirdPartyKvStore? = null,
    private val campusProxy: ThirdPartyCampusProxy? = null,
    private val configurationReader: (ThirdPartyService, String) -> String? = { _, _ -> null },
    private val resourceStore: ThirdPartyResourceStore? = null,
    private val networkProvider: PluginNetworkProvider? = null,
    private val commandReceiptStore: PluginCommandReceiptStore? = null,
    private val androidSystemProvider: AndroidSystemCapabilityProvider = AndroidSystemCapabilityProvider(),
    private val androidNativeProvider: PluginCapabilityProvider = UnavailableAndroidNativeCapabilityProvider(),
    private val pluginSessionKeepAliveProvider: PluginCapabilityProvider = LambdaPluginCapabilityProvider(
        setOf("android.session.keepAlive@1"),
    ) { throw PluginRuntimeException("capability_unavailable", "Session keep-alive unavailable") },
    private val pluginBackgroundTaskProvider: PluginCapabilityProvider = LambdaPluginCapabilityProvider(
        setOf("android.background.task@1"),
    ) { throw PluginRuntimeException("capability_unavailable", "Background tasks unavailable") },
    providerOverrides: List<PluginCapabilityProvider> = emptyList(),
) {
    private val capabilityProviders = PluginCapabilityProviderRegistry(
        listOf(
            LambdaPluginCapabilityProvider(
                setOf(
                    "runtime.lifecycle@1",
                    "configuration.read@1",
                    "remote.frame@1",
                    "navigation.external@1",
                ),
                ::executeHostCapability,
            ),
            LambdaPluginCapabilityProvider(
                setOf(
                    "identity.profile@1",
                    "academic.timetable@1",
                    "academic.scores@1",
                    "academic.exams@1",
                    "academic.calendar@1",
                    "academic.progress@1",
                    "academic.homework@1",
                    "academic.resources@1",
                    "mail.read@1",
                    "campus.request@1",
                ),
                ::executeCampusCapability,
            ),
            LambdaPluginCapabilityProvider(
                setOf("network.request@1", "network.request@2"),
                ::executeNetworkCapability,
            ),
            LambdaPluginCapabilityProvider(
                setOf("storage.kv@2", "storage.blob@1", "cache.resource@1"),
                ::executeStorageCapability,
            ),
            LambdaPluginCapabilityProvider(
                setOf(
                    "academic.userCourses.command@1",
                    "academic.homework.submit@1",
                    "mail.send@1",
                ),
                ::executeCommandCapability,
            ),
            androidSystemProvider,
            androidNativeProvider,
            pluginSessionKeepAliveProvider,
            pluginBackgroundTaskProvider,
        ),
        overrides = providerOverrides,
    )

    fun cancel(service: ThirdPartyService, requestId: String): Boolean =
        networkProvider?.cancel(service, requestId) == true

    internal fun registeredCapabilityIds(): Set<String> =
        capabilityProviders.capabilityIds

    suspend fun invoke(
        service: ThirdPartyService,
        capability: String,
        method: String,
        params: JsonObject = buildJsonObject { },
        binary: PluginBinaryPayload? = null,
        confirmer: ThirdPartySensitiveActionConfirmer,
        currentPageUrl: String = "",
        openExternal: (String) -> Boolean = { false },
        closePlugin: () -> Unit = {},
        eventSink: (PluginRuntimeEvent) -> Unit = {},
        requestId: String = "",
        runtimeEnvironment: PluginWebViewRuntimeEnvironment =
            PluginWebViewPolicy.runtimeEnvironment(),
        runtimeId: String = "direct",
        backgroundRuntime: Boolean = false,
        timeoutMsOverride: Long? = null,
    ): JsonObject {
        if (!service.canRun) {
            return errorResponse("capability_unavailable", "插件尚未完成 contract_v1 授权")
        }
        val descriptor = ThirdPartyCapabilityRegistry.get(capability)
            ?: return errorResponse("capability_unavailable", "未知 capability：$capability")
        if (capability !in service.manifest.requiredCapabilities + service.manifest.optionalCapabilities) {
            return errorResponse("capability_unavailable", "插件未声明 capability：$capability")
        }
        if (capability !in service.grantedCapabilities) {
            return errorResponse("permission_denied", "插件未获得 capability：$capability")
        }
        val route = ThirdPartyCapabilityRegistry.route(capability, method)
            ?: return errorResponse("invalid_request", "未知 capability method：$capability#$method")
        val validationErrors = ThirdPartyCapabilityRegistry.validateRequest(capability, method, params)
        if (validationErrors.isNotEmpty()) {
            return errorResponse(
                "invalid_request",
                validationErrors.joinToString("; "),
                details = buildJsonArray {
                    validationErrors.forEach { add(JsonPrimitive(it)) }
                },
            )
        }
        if (
            !ThirdPartyServiceSandbox.isServiceSandboxUrl(
                currentPageUrl,
                service.serviceId,
                service.publisherSubjectId,
            )
        ) {
            return errorResponse("origin_denied", "Bridge calls require the stable local main-frame origin")
        }

        return try {
            val call = PluginCapabilityCall(
                service = service,
                capability = descriptor.id,
                method = method,
                params = params,
                binary = binary,
                confirmer = confirmer,
                currentPageUrl = currentPageUrl,
                openExternal = openExternal,
                closePlugin = closePlugin,
                eventSink = eventSink,
                requestId = requestId,
                runtimeEnvironment = runtimeEnvironment,
                runtimeId = runtimeId,
                backgroundRuntime = backgroundRuntime,
            )
            val declaredTimeoutMs = params["timeoutMs"]
                ?.jsonPrimitive
                ?.longOrNull
                ?: descriptor.timeoutMs
            if (timeoutMsOverride != null && timeoutMsOverride <= 0L) {
                return errorResponse(
                    "request_timeout",
                    "Capability invocation exceeded its generated deadline",
                    retryable = true,
                )
            }
            val effectiveTimeoutMs = listOfNotNull(
                declaredTimeoutMs.takeIf { it > 0L },
                timeoutMsOverride?.takeIf { it > 0L },
            ).minOrNull() ?: 0L
            val invokeProvider: suspend () -> JsonElement = {
                if (capability == "android.session.keepAlive@1") {
                    capabilityProviders.invoke(call) // Lease, budget and receipt use one durable transaction.
                } else if (descriptor.idempotencyRequired && "idempotencyKey" in route.requiredFields) {
                    if (capability in PERSISTENT_ANDROID_COMMAND_CAPABILITIES) {
                        executeIdempotentWithoutConfirmation(call)
                    } else {
                        executeCommandOnce(call)
                    }
                } else {
                    capabilityProviders.invoke(call)
                }
            }
            val result = if (effectiveTimeoutMs > 0) {
                withTimeout(effectiveTimeoutMs) { invokeProvider() }
            } else {
                invokeProvider()
            }
            val responseValidationErrors =
                ThirdPartyCapabilityRegistry.validateResponse(capability, method, result)
            if (responseValidationErrors.isNotEmpty()) {
                throw PluginRuntimeException(
                    code = "capability_unavailable",
                    message = "Host response violates the generated Capability contract",
                    details = buildJsonArray {
                        responseValidationErrors.forEach { add(JsonPrimitive(it)) }
                    },
                )
            }
            successResponse(result)
        } catch (error: PluginRuntimeException) {
            errorResponse(
                error.code,
                error.message,
                error.retryable,
                error.httpStatus,
                error.details,
            )
        } catch (_: TimeoutCancellationException) {
            errorResponse(
                "request_timeout",
                "Capability invocation exceeded its generated deadline",
                retryable = true,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: PluginNetworkException) {
            errorResponse(error.code, error.message, error.retryable, error.httpStatus)
        } catch (error: ThirdPartyCampusProxyException) {
            errorResponse(error.code, error.message, error.retryable, error.httpStatus)
        } catch (error: ThirdPartyKvRevisionConflict) {
            errorResponse(
                "idempotency_conflict",
                error.message.orEmpty(),
                details = buildJsonObject {
                    put("expectedRevision", error.expectedRevision)
                    put("actualRevision", error.actualRevision)
                },
            )
        } catch (_: PluginIdempotencyConflict) {
            errorResponse("idempotency_conflict", "Idempotency key conflicts with an earlier request")
        } catch (error: IllegalArgumentException) {
            errorResponse("invalid_request", error.message ?: "Invalid request")
        } catch (error: Exception) {
            errorResponse("capability_unavailable", error.message ?: "Capability invocation failed")
        }
    }

    private suspend fun executeHostCapability(call: PluginCapabilityCall): JsonElement =
        when (call.capability) {
        "runtime.lifecycle@1" -> executeRuntime(call)
        "configuration.read@1" -> executeConfiguration(call.service, call.params)
        "remote.frame@1" -> invalidMethod(call.capability, call.method)
        "navigation.external@1" ->
            executeNavigation(call.service, call.params, call.confirmer, call.openExternal)
        else -> invalidMethod(call.capability, call.method)
    }

    private suspend fun executeCampusCapability(call: PluginCapabilityCall): JsonElement =
        when (call.capability) {
        "identity.profile@1" -> campusRead(
            moduleRepository().profile(strategy = readStrategy(call.params)),
            call.params.boolean("forceRefresh"),
        )
        "academic.timetable@1" -> campusRead(
            moduleRepository().timetable(strategy = readStrategy(call.params)),
            call.params.boolean("forceRefresh"),
        )
        "academic.scores@1" -> when (call.method) {
            "getScores" -> campusRead(
                moduleRepository().scores(
                    term = call.params.string("term"),
                    ctype = call.params.string("courseType"),
                    strategy = readStrategy(call.params),
                ),
                call.params.boolean("forceRefresh"),
            )
            "getHistoryScores" -> campusRead(
                moduleRepository().historyScores(
                    term = call.params.string("term"),
                    strategy = readStrategy(call.params),
                ),
                call.params.boolean("forceRefresh"),
            )
            else -> invalidMethod(call.capability, call.method)
        }
        "academic.exams@1" -> campusRead(
            moduleRepository().exams(
                term = call.params.string("term"),
                strategy = readStrategy(call.params),
            ),
            call.params.boolean("forceRefresh"),
        )
        "academic.calendar@1" -> campusRead(
            moduleRepository().calendar(
                month = call.params.string("month"),
                strategy = readStrategy(call.params),
            ),
            call.params.boolean("forceRefresh"),
        )
        "academic.progress@1" -> campusRead(
            moduleRepository().academicProgress(strategy = readStrategy(call.params)),
            call.params.boolean("forceRefresh"),
        )
        "academic.homework@1" -> campusRead(
            moduleRepository().homework(
                status = call.params.string("status") ?: "all",
                strategy = readStrategy(call.params),
            ),
            call.params.boolean("forceRefresh"),
        )
        "academic.resources@1" -> campusRead(
            moduleRepository().courseResources(
                term = call.params.string("term"),
                courseId = call.params.string("courseId"),
                folderId = call.params.string("folderId") ?: "0",
                search = call.params.string("search"),
                categoryKey = call.params.string("categoryKey"),
                strategy = readStrategy(call.params),
            ),
            call.params.boolean("forceRefresh"),
        )
        "mail.read@1" -> executeMailRead(call.method, call.params)
        "campus.request@1" -> buildJsonObject {
            put(
                "data",
                campusProxy?.request(call.service, call.params)
                    ?: throw PluginRuntimeException(
                        "capability_unavailable",
                        "campus.request@1 provider is unavailable",
                    ),
            )
            put("meta", directNetworkMeta())
        }
        else -> invalidMethod(call.capability, call.method)
    }

    private suspend fun executeNetworkCapability(call: PluginCapabilityCall): JsonElement =
        executeNetwork(call.service, call.params, call.eventSink, call.requestId, call.capability)

    private suspend fun executeStorageCapability(call: PluginCapabilityCall): JsonElement =
        when (call.capability) {
            "storage.kv@2" -> executeKv(call.service, call.method, call.params)
            "storage.blob@1" ->
                executeBlob(call.service, call.method, call.params, call.binary)
            "cache.resource@1" ->
                executeCache(call.service, call.method, call.params, call.binary)
            else -> invalidMethod(call.capability, call.method)
        }

    private suspend fun executeCommandCapability(call: PluginCapabilityCall): JsonElement =
        executeCommand(call.capability, call.method, call.params, call.service)

    private fun executeRuntime(call: PluginCapabilityCall): JsonElement = when (call.method) {
        "handshake" -> buildJsonObject {
            put("protocolVersion", THIRD_PARTY_BRIDGE_PROTOCOL_VERSION)
            put("contractProfile", THIRD_PARTY_CONTRACT_PROFILE)
            put("runtimeFloor", THIRD_PARTY_RUNTIME_VERSION)
            put("availableCapabilities", buildJsonArray {
                call.service.grantedCapabilities
                    .filter { capability ->
                        PluginWebViewPolicy.isCapabilityAvailable(
                            capability,
                            call.runtimeEnvironment,
                        )
                    }
                    .sorted()
                    .forEach { add(JsonPrimitive(it)) }
            })
            put("binaryTransports", buildJsonArray {
                call.runtimeEnvironment.binaryTransports.forEach { transport ->
                    add(JsonPrimitive(transport.wireName))
                }
            })
            call.runtimeEnvironment.preferredBinaryTransport?.let { transport ->
                put("preferredBinaryTransport", transport.wireName)
            }
        }
        "ready" -> buildJsonObject { put("ready", true) }
        "close" -> {
            call.closePlugin()
            buildJsonObject { put("closed", true) }
        }
        else -> invalidMethod("runtime.lifecycle@1", call.method)
    }

    private fun executeConfiguration(
        service: ThirdPartyService,
        params: JsonObject,
    ): JsonElement {
        val key = params.requiredString("key")
        if (service.manifest.configuration.none { it.key == key }) {
            throw PluginRuntimeException("invalid_request", "Plugin did not declare configuration key: $key")
        }
        return buildJsonObject {
            configurationReader(service, key)?.let { put("value", it) } ?: put("value", JsonNull)
        }
    }

    private suspend fun executeNavigation(
        service: ThirdPartyService,
        params: JsonObject,
        confirmer: ThirdPartySensitiveActionConfirmer,
        openExternal: (String) -> Boolean,
    ): JsonElement {
        val url = params.requiredString("url")
        val origin = ThirdPartyManifestValidator.normalizeOrigin(
            value = url.toOrigin(),
            fieldName = "navigation URL",
            blockCampusHosts = false,
        )
        if (origin !in service.manifest.navigationOrigins) {
            throw PluginRuntimeException("origin_denied", "Navigation origin is not declared")
        }
        if (!confirmer.confirm("打开外部链接", "${service.manifest.name} 请求打开：$url")) {
            throw PluginRuntimeException("user_cancelled", "User cancelled external navigation")
        }
        return buildJsonObject { put("opened", openExternal(url)) }
    }

    private suspend fun executeMailRead(method: String, params: JsonObject): JsonElement =
        when (method) {
            "listFolders" -> campusRead(
                mailRepository().folders(strategy = readStrategy(params)),
                params.boolean("forceRefresh"),
            )
            "listMessages" -> campusRead(
                mailRepository().messages(
                    folderId = params.string("folderId") ?: "1",
                    start = params.int("start") ?: 0,
                    limit = (params.int("limit") ?: 20).coerceIn(1, 100),
                    strategy = readStrategy(params),
                ),
                params.boolean("forceRefresh"),
            )
            "getMessage" -> campusRead(
                mailRepository().detail(
                    messageId = params.requiredString("messageId"),
                    mboxa = params.string("mailbox").orEmpty(),
                ),
                forceRefresh = true,
            )
            else -> invalidMethod("mail.read@1", method)
        }

    private suspend fun executeNetwork(
        service: ThirdPartyService,
        params: JsonObject,
        eventSink: (PluginRuntimeEvent) -> Unit,
        requestId: String,
        capability: String = "network.request@1",
    ): JsonElement {
        val provider = networkProvider
            ?: throw PluginRuntimeException(
                "capability_unavailable",
                "network.request@1 provider is unavailable",
            )
        val request = PluginNetworkRequest(
            requestId = requestId.ifBlank { "network-${System.nanoTime()}" },
            method = params.string("method") ?: "GET",
            url = params.requiredString("url"),
            headers = params.objectOrNull("headers")
                ?.mapValues { (_, value) -> value.jsonPrimitive.content }
                .orEmpty(),
            body = parseNetworkBody(params),
            timeoutMs = params.long("timeoutMs") ?: PLUGIN_NETWORK_DEFAULT_TIMEOUT_MS,
            capability = capability,
            includeCookies = params.string("credentials") == "include",
        )
        val response = provider.execute(service, request) { progress ->
            eventSink(
                PluginRuntimeEvent(
                    capability = capability,
                    event = "progress",
                    requestId = request.requestId,
                    data = buildJsonObject {
                        put("loaded", progress.transferredBytes)
                        progress.totalBytes?.let { put("total", it) }
                        put("phase", progress.phase)
                    },
                ),
            )
        }
        return buildJsonObject {
            put("status", response.status)
            put("finalUrl", response.finalUrl)
            put("redirects", response.redirects)
            response.mediaType?.let { put("contentType", it) }
            put("headers", buildJsonObject {
                response.headers.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (name, values) ->
                    put(name, buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
                }
            })
            when {
                response.resource != null -> {
                    put("bodyType", "resource")
                    put("resource", resourceJson(service, response.resource))
                }
                response.json != null -> {
                    put("bodyType", "json")
                    val parsed = runCatching {
                        PluginProtocolJson.parseToJsonElement(response.json)
                    }.getOrNull()
                    if (parsed != null) put("body", parsed) else put("body", response.json)
                }
                else -> {
                    put("bodyType", "text")
                    put("body", response.text.orEmpty())
                }
            }
        }
    }

    private suspend fun executeKv(
        service: ThirdPartyService,
        method: String,
        params: JsonObject,
    ): JsonElement {
        val store = kvStore
            ?: throw PluginRuntimeException("capability_unavailable", "storage.kv@2 is unavailable")
        val namespace = namespace(service)
        return when (method) {
            "get" -> {
                val entry = store.getEntry(namespace, params.requiredString("key"))
                buildJsonObject {
                    put("value", entry.value ?: JsonNull)
                    put("revision", entry.revision)
                }
            }
            "set" -> {
                val mutation = ThirdPartyKvMutation.Set(
                    params.requiredString("key"),
                    params["value"] ?: throw IllegalArgumentException("Missing value"),
                )
                kvTransactionJson(
                    store.transact(namespace, params.long("ifRevision"), listOf(mutation)),
                )
            }
            "remove" -> {
                val key = params.requiredString("key")
                val transaction = store.transact(
                    namespace,
                    params.long("ifRevision"),
                    listOf(ThirdPartyKvMutation.Remove(key)),
                )
                buildJsonObject {
                    put("removed", key in transaction.changedKeys)
                    kvTransactionJson(transaction).forEach { (name, value) -> put(name, value) }
                }
            }
            "keys" -> buildJsonObject {
                put("keys", buildJsonArray {
                    store.keys(namespace).forEach { add(JsonPrimitive(it)) }
                })
                put("revision", store.revision(namespace))
            }
            "usage" -> kvUsageJson(store.usage(namespace), store.revision(namespace))
            "batch" -> kvTransactionJson(
                store.transact(
                    namespace,
                    expectedRevision = null,
                    mutations = parseKvMutations(params.requiredArray("operations")),
                ),
            )
            "transaction" -> kvTransactionJson(
                store.transact(
                    namespace,
                    expectedRevision = params.requiredLong("ifRevision"),
                    mutations = parseKvMutations(params.requiredArray("operations")),
                ),
            )
            "export" -> {
                val exported = store.export(namespace)
                val document = buildJsonObject {
                    put("revision", exported.revision)
                    put("values", JsonObject(exported.values))
                }
                val resource = resources().putBlob(
                    namespace,
                    ByteArrayInputStream(document.toString().toByteArray(Charsets.UTF_8)),
                    "application/json",
                )
                resourceJson(service, resource)
            }
            "import" -> {
                val content = openBlob(namespace, params.requiredString("handle"))
                if (content.contentLength > THIRD_PARTY_KV_TOTAL_BYTES) {
                    throw PluginRuntimeException("resource_too_large", "KV import exceeds 10 MiB")
                }
                val imported = content.input.bufferedReader(Charsets.UTF_8).use { reader ->
                    PluginProtocolJson.parseToJsonElement(reader.readText()).jsonObject
                }
                val values = imported["values"]?.jsonObject
                    ?: throw IllegalArgumentException("KV import is missing values")
                val mutations = buildList {
                    add(ThirdPartyKvMutation.Clear)
                    values.toSortedMap().forEach { (key, value) ->
                        add(ThirdPartyKvMutation.Set(key, value))
                    }
                }
                kvTransactionJson(
                    store.transact(namespace, params.long("ifRevision"), mutations),
                )
            }
            else -> invalidMethod("storage.kv@2", method)
        }
    }

    private suspend fun executeBlob(
        service: ThirdPartyService,
        method: String,
        params: JsonObject,
        binary: PluginBinaryPayload?,
    ): JsonElement {
        val store = resources()
        val namespace = namespace(service)
        return when (method) {
            "put" -> {
                val payload = binary
                    ?: throw PluginRuntimeException(
                        "invalid_request",
                        "storage.blob.put requires a negotiated binary payload",
                    )
                val declaredSize = params.requiredLong("size")
                if (declaredSize != payload.size) {
                    throw PluginRuntimeException("invalid_request", "Binary payload size mismatch")
                }
                resourceJson(
                    service,
                    store.putBlob(
                        namespace,
                        payload.openInputStream(),
                        params.requiredString("contentType"),
                    ),
                )
            }
            "getInfo" -> {
                val descriptor = store.describe(namespace, params.requiredString("handle"))
                    ?: throw PluginRuntimeException("invalid_request", "Unknown blob handle")
                if (descriptor.kind != ThirdPartyResourceKind.Blob) {
                    throw PluginRuntimeException("invalid_request", "Handle is not a blob")
                }
                resourceJson(service, descriptor)
            }
            "delete" -> buildJsonObject {
                val handle = params.requiredString("handle")
                val descriptor = store.describe(namespace, handle)
                if (descriptor != null && descriptor.kind != ThirdPartyResourceKind.Blob) {
                    throw PluginRuntimeException("invalid_request", "Handle is not a blob")
                }
                put("deleted", descriptor != null && store.remove(namespace, handle))
            }
            else -> invalidMethod("storage.blob@1", method)
        }
    }

    private suspend fun executeCache(
        service: ThirdPartyService,
        method: String,
        params: JsonObject,
        binary: PluginBinaryPayload?,
    ): JsonElement {
        val store = resources()
        val namespace = namespace(service)
        return when (method) {
            "put" -> {
                val payload = binary
                    ?: throw PluginRuntimeException(
                        "invalid_request",
                        "cache.resource.put requires a negotiated binary payload",
                    )
                if (params.requiredLong("size") != payload.size) {
                    throw PluginRuntimeException("invalid_request", "Binary payload size mismatch")
                }
                resourceJson(
                    service,
                    store.putCache(
                        namespace = namespace,
                        cacheKey = params.requiredString("key"),
                        input = payload.openInputStream(),
                        mediaType = params.requiredString("contentType"),
                        pinned = params.boolean("pin") ?: false,
                    ),
                )
            }
            "promote" -> resourceJson(
                service,
                store.promoteCache(
                    namespace = namespace,
                    handle = params.requiredString("handle"),
                    cacheKey = params.requiredString("key"),
                    pinned = params.boolean("pinned"),
                ),
            )
            "deleteHandle" -> buildJsonObject {
                val handle = params.requiredString("handle")
                val descriptor = store.describe(namespace, handle)
                if (descriptor != null && descriptor.kind != ThirdPartyResourceKind.Cache) {
                    throw PluginRuntimeException(
                        "invalid_request",
                        "Handle is not a cache resource",
                    )
                }
                put("deleted", descriptor != null && store.remove(namespace, handle))
            }
            "match" -> store.matchCache(namespace, params.requiredString("key"))
                ?.let { resourceJson(service, it) }
                ?: JsonNull
            "delete" -> buildJsonObject {
                put("deleted", store.removeCache(namespace, params.requiredString("key")))
            }
            "pin" -> {
                store.pinCache(
                    namespace,
                    params.requiredString("key"),
                    params.boolean("pinned") ?: false,
                )
                buildJsonObject { put("pinned", params.boolean("pinned") ?: false) }
            }
            "usage" -> buildJsonObject {
                put("bytesUsed", store.usage(namespace, ThirdPartyResourceKind.Cache))
                put("byteLimit", THIRD_PARTY_CACHE_PLUGIN_BYTES)
                put("globalByteLimit", THIRD_PARTY_CACHE_GLOBAL_BYTES)
            }
            else -> invalidMethod("cache.resource@1", method)
        }
    }

    private suspend fun executeCommandOnce(call: PluginCapabilityCall): JsonElement {
        val idempotencyKey = call.params.requiredString("idempotencyKey")
        val receiptStore = commandReceiptStore
            ?: throw PluginRuntimeException(
                "capability_unavailable",
                "Command receipt store is unavailable",
            )
        return receiptStore.executeOnce(
            namespace(call.service),
            idempotencyKey,
            pluginCommandRequestDigest(call.capability, call.method, call.params),
        ) {
            val confirmation = commandConfirmation(
                call.service,
                call.capability,
                call.method,
                call.params,
                call.currentPageUrl,
            )
            if (!call.confirmer.confirm(confirmation.first, confirmation.second)) {
                throw PluginRuntimeException("user_cancelled", "User cancelled the command")
            }
            val result = capabilityProviders.invoke(call)
            buildJsonObject {
                put("receiptId", UUID.randomUUID().toString())
                put("idempotencyKey", idempotencyKey)
                put("completedAt", nowIso())
                put("result", result)
            }
        }
    }

    private suspend fun executeIdempotentWithoutConfirmation(call: PluginCapabilityCall): JsonElement {
        val idempotencyKey = call.params.requiredString("idempotencyKey")
        val receiptStore = commandReceiptStore
            ?: throw PluginRuntimeException(
                "capability_unavailable",
                "Command receipt store is unavailable",
            )
        return receiptStore.executeOnce(
            namespace(call.service),
            idempotencyKey,
            pluginCommandRequestDigest(call.capability, call.method, call.params),
        ) {
            val result = capabilityProviders.invoke(call)
            buildJsonObject {
                put("receiptId", UUID.randomUUID().toString())
                put("idempotencyKey", idempotencyKey)
                put("completedAt", nowIso())
                put("result", result)
            }
        }
    }

    private suspend fun executeCommand(
        capability: String,
        method: String,
        params: JsonObject,
        service: ThirdPartyService,
    ): JsonElement = when (capability) {
        "academic.userCourses.command@1" -> when (method) {
            "save" -> buildJsonObject {
                put(
                    "id",
                    moduleRepository().saveUserCourse(
                        params.requiredObject("course").toUserCourseDraft(),
                    ),
                )
            }
            "delete" -> {
                moduleRepository().deleteUserCourse(params.requiredLong("id"))
                buildJsonObject { put("deleted", true) }
            }
            else -> invalidMethod(capability, method)
        }
        "academic.homework.submit@1" -> {
            val attachmentHandles = params.stringList("attachmentHandles")
            requireBlobCapabilityForAttachments(service, attachmentHandles)
            val files = attachmentHandles.map { handle ->
                val resource = openBlob(namespace(service), handle)
                if (resource.contentLength > THIRD_PARTY_BLOB_ITEM_BYTES) {
                    throw PluginRuntimeException(
                        "resource_too_large",
                        "Homework attachment exceeds 64 MiB",
                    )
                }
                HomeworkUploadFile(
                    filename = "$handle.bin",
                    content = resource.input.use(InputStreamReadAll::read),
                    contentType = resource.descriptor.mediaType,
                )
            }
            json(
                moduleRepository().submitHomework(
                    homeworkId = params.requiredInt("homeworkId"),
                    courseId = params.requiredInt("courseId"),
                    content = params.string("content").orEmpty(),
                    files = files,
                ),
            )
        }
        "mail.send@1" -> executeMailSend(params, service)
        else -> invalidMethod(capability, method)
    }

    private suspend fun executeMailSend(
        params: JsonObject,
        service: ThirdPartyService,
    ): JsonElement {
        var composeId: String? = null
        val attachmentHandles = params.stringList("attachmentHandles")
        requireBlobCapabilityForAttachments(service, attachmentHandles)
        val attachments = attachmentHandles.map { handle ->
            val resource = openBlob(namespace(service), handle)
            if (resource.contentLength > THIRD_PARTY_BLOB_ITEM_BYTES) {
                throw PluginRuntimeException("resource_too_large", "Mail attachment exceeds 64 MiB")
            }
            val upload = mailRepository().uploadAttachment(
                composeId,
                MailUploadFile(
                    filename = "$handle.bin",
                    content = resource.input.use(InputStreamReadAll::read),
                    contentType = resource.descriptor.mediaType,
                ),
            )
            composeId = upload.composeId
            MailComposeAttachment(
                attachmentId = upload.attachment.attachmentId,
                filename = upload.attachment.filename,
                size = upload.attachment.size,
                contentType = upload.attachment.contentType,
            )
        }
        return json(
            mailRepository().send(
                MailComposeRequest(
                    composeId = composeId,
                    to = params.stringList("to"),
                    cc = params.stringList("cc"),
                    bcc = params.stringList("bcc"),
                    subject = params.requiredString("subject"),
                    content = params.string("text"),
                    htmlContent = params.string("html"),
                    isHtml = params.string("html") != null,
                    attachments = attachments,
                ),
            ),
        )
    }

    private fun commandConfirmation(
        service: ThirdPartyService,
        capability: String,
        method: String,
        params: JsonObject,
        currentPageUrl: String,
    ): Pair<String, String> = when (capability) {
        "academic.userCourses.command@1" ->
            "确认修改自定义课程" to
                "${service.manifest.name} 请求从 ${currentPageUrl.ifBlank { "当前页面" }} 执行 $method"
        "academic.homework.submit@1" ->
            "确认提交作业" to
                "${service.manifest.name} 请求提交作业 ${params.string("homeworkId").orEmpty()}"
        "mail.send@1" ->
            "确认发送邮件" to
                "${service.manifest.name} 请求发送邮件：${params.string("subject").orEmpty()}"
        else -> "确认插件操作" to "${service.manifest.name} 请求执行 $capability#$method"
    }

    private suspend fun openBlob(
        namespace: ThirdPartyKvNamespace,
        handle: String,
    ): ThirdPartyResourceContent {
        val descriptor = resources().describe(namespace, handle)
            ?: throw PluginRuntimeException("invalid_request", "Unknown blob handle")
        if (descriptor.kind != ThirdPartyResourceKind.Blob) {
            throw PluginRuntimeException("invalid_request", "Handle is not a blob")
        }
        return resources().open(namespace, handle)
    }

    private fun requireBlobCapabilityForAttachments(
        service: ThirdPartyService,
        handles: List<String>,
    ) {
        if (handles.isNotEmpty() && "storage.blob@1" !in service.grantedCapabilities) {
            throw PluginRuntimeException(
                "permission_denied",
                "Attachment handles require storage.blob@1",
            )
        }
    }

    private fun parseNetworkBody(params: JsonObject): PluginNetworkBody? {
        val body = params["body"] ?: return null
        return when (params.string("bodyType") ?: "json") {
            "json" -> PluginNetworkBody.Json(body.toString())
            "text" -> PluginNetworkBody.Text(body.jsonPrimitive.content)
            "formUrlEncoded" -> {
                val fields = body as? JsonObject
                    ?: throw IllegalArgumentException("formUrlEncoded body must be an object of strings")
                PluginNetworkBody.FormUrlEncoded(fields.map { (name, value) ->
                    val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: throw IllegalArgumentException("formUrlEncoded fields must be strings")
                    name to text
                })
            }
            "blob" -> {
                val handle = when (body) {
                    is JsonPrimitive -> body.content
                    is JsonObject -> body.requiredString("handle")
                    else -> throw IllegalArgumentException("blob body must contain a handle")
                }
                PluginNetworkBody.Blob(handle)
            }
            "formData" -> {
                val form = body as? JsonObject
                    ?: throw IllegalArgumentException("formData body must be an object")
                PluginNetworkBody.Multipart(
                    form.map { (name, value) ->
                        if (value is JsonObject && "handle" in value) {
                            PluginNetworkFormPart.Blob(
                                name = name,
                                handle = value.requiredString("handle"),
                                fileName = value.string("fileName") ?: "$name.bin",
                                mediaType = value.string("contentType"),
                            )
                        } else {
                            PluginNetworkFormPart.Text(name, value.jsonPrimitive.content)
                        }
                    },
                )
            }
            else -> throw IllegalArgumentException("Unknown network bodyType")
        }
    }

    private fun parseKvMutations(operations: JsonArray): List<ThirdPartyKvMutation> =
        operations.map { element ->
            val operation = element.jsonObject
            when (operation.requiredString("op")) {
                "set" -> ThirdPartyKvMutation.Set(
                    operation.requiredString("key"),
                    operation["value"] ?: throw IllegalArgumentException("set operation missing value"),
                )
                "remove" -> ThirdPartyKvMutation.Remove(operation.requiredString("key"))
                "clear" -> ThirdPartyKvMutation.Clear
                else -> throw IllegalArgumentException("Unknown KV operation")
            }
        }

    private inline fun <reified T> campusRead(
        envelope: ModuleEnvelope<T>,
        forceRefresh: Boolean?,
    ): JsonElement = buildJsonObject {
        put("data", json(envelope.data))
        put("meta", buildJsonObject {
            put("syncedAt", envelope.syncedAt ?: nowIso())
            put("source", if (forceRefresh == true) "network" else "cache")
            put("coverage", envelope.coverage.name.lowercase())
            put("fromCache", forceRefresh != true)
        })
    }

    private fun directNetworkMeta(): JsonObject = buildJsonObject {
        put("syncedAt", nowIso())
        put("source", "network")
        put("coverage", "complete")
        put("fromCache", false)
    }

    private fun resourceJson(
        service: ThirdPartyService,
        resource: ThirdPartyResourceDescriptor,
    ): JsonObject = buildJsonObject {
        put("handle", resource.handle)
        put("size", resource.size)
        put("contentType", resource.mediaType)
        put("url", ThirdPartyServiceSandbox.resourceUrlFor(service, resource.handle))
        put("etag", resource.digestSha256)
        put("pinned", resource.pinned)
    }

    private fun kvTransactionJson(result: ThirdPartyKvTransactionResult): JsonObject =
        buildJsonObject {
            put("revision", result.revision)
            put("usage", kvUsageJson(result.usage, result.revision))
            put("changedKeys", buildJsonArray {
                result.changedKeys.sorted().forEach { add(JsonPrimitive(it)) }
            })
        }

    private fun kvUsageJson(usage: ThirdPartyKvUsage, revision: Long): JsonObject =
        buildJsonObject {
            put("bytesUsed", usage.bytesUsed)
            put("byteLimit", usage.byteLimit)
            put("keyCount", usage.keyCount)
            put("keyLimit", usage.keyLimit)
            put("revision", revision)
        }

    private fun moduleRepository(): ModuleRepository =
        moduleRepository ?: throw PluginRuntimeException(
            "capability_unavailable",
            "Academic provider is unavailable",
        )

    private fun mailRepository(): MailRepository =
        mailRepository ?: throw PluginRuntimeException(
            "capability_unavailable",
            "Mail provider is unavailable",
        )

    private fun resources(): ThirdPartyResourceStore =
        resourceStore ?: throw PluginRuntimeException(
            "capability_unavailable",
            "Resource store is unavailable",
        )

    private fun namespace(service: ThirdPartyService): ThirdPartyKvNamespace =
        ThirdPartyKvNamespace(service.publisherSubjectId, service.serviceId)

    private fun readStrategy(params: JsonObject): ModuleLoadStrategy =
        if (params.boolean("forceRefresh") == true) {
            ModuleLoadStrategy.NetworkFirst
        } else {
            ModuleLoadStrategy.CacheFirst
        }

    private inline fun <reified T> json(value: T): JsonElement =
        PluginProtocolJson.parseToJsonElement(PluginProtocolJson.encodeToString(value))

    private fun invalidMethod(capability: String, method: String): Nothing =
        throw PluginRuntimeException("invalid_request", "Unknown method: $capability#$method")

}

private val PERSISTENT_ANDROID_COMMAND_CAPABILITIES = setOf(
    "android.background.task@1",
    "android.session.keepAlive@1",
    "android.accessibility.actions@1",
    "android.files.save@1",
    "android.notifications.post@1",
    "android.calendar.write@1",
    "android.audio.record@1",
)

private fun successResponse(result: JsonElement): JsonObject = buildJsonObject {
    put("ok", true)
    put("result", result)
}

private fun errorResponse(
    code: String,
    message: String,
    retryable: Boolean = false,
    httpStatus: Int? = null,
    details: JsonElement? = null,
): JsonObject = buildJsonObject {
    put("ok", false)
    put("error", buildJsonObject {
        put("code", code)
        put("message", message)
        put("retryable", retryable)
        httpStatus?.let { put("httpStatus", it) }
        details?.let { put("details", it) }
    })
}

private fun JsonObject.toUserCourseDraft(): UserCourseDraft =
    UserCourseDraft(
        id = long("id"),
        courseName = requiredString("courseName"),
        weekday = requiredString("weekday"),
        weekdayIndex = requiredInt("weekdayIndex"),
        period = requiredString("period"),
        periodNumber = requiredInt("periodNumber"),
        timeRange = string("timeRange"),
        startWeek = requiredInt("startWeek"),
        endWeek = requiredInt("endWeek"),
        weeksText = string("weeksText"),
        durationType = runCatching {
            UserCourseDurationType.valueOf(
                string("durationType") ?: UserCourseDurationType.Temporary.name,
            )
        }.getOrDefault(UserCourseDurationType.Temporary),
        teacher = string("teacher"),
        locationText = string("locationText"),
        remark = string("remark"),
        colorIndex = int("colorIndex") ?: 0,
    )

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

private fun JsonObject.requiredString(name: String): String =
    string(name) ?: throw IllegalArgumentException("Missing string parameter: $name")

private fun JsonObject.int(name: String): Int? =
    this[name]?.jsonPrimitive?.intOrNull

private fun JsonObject.requiredInt(name: String): Int =
    int(name) ?: throw IllegalArgumentException("Missing integer parameter: $name")

private fun JsonObject.long(name: String): Long? =
    this[name]?.jsonPrimitive?.longOrNull

private fun JsonObject.requiredLong(name: String): Long =
    long(name) ?: throw IllegalArgumentException("Missing integer parameter: $name")

private fun JsonObject.boolean(name: String): Boolean? =
    this[name]?.jsonPrimitive?.booleanOrNull

private fun JsonObject.stringList(name: String): List<String> =
    (this[name] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
        .orEmpty()

private fun JsonObject.objectOrNull(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.requiredObject(name: String): JsonObject =
    objectOrNull(name) ?: throw IllegalArgumentException("Missing object parameter: $name")

private fun JsonObject.requiredArray(name: String): JsonArray =
    this[name] as? JsonArray ?: throw IllegalArgumentException("Missing array parameter: $name")

private fun String.toOrigin(): String {
    val uri = java.net.URI(this)
    val scheme = uri.scheme?.lowercase().orEmpty()
    val host = uri.host?.lowercase().orEmpty()
    val defaultPort = if (scheme == "https") 443 else 80
    val port = uri.port
        .takeIf { it != -1 && it != defaultPort }
        ?.let { ":$it" }
        .orEmpty()
    return "$scheme://$host$port"
}

private object InputStreamReadAll {
    fun read(input: java.io.InputStream): ByteArray = input.readBytes()
}

private fun nowIso(): String = OffsetDateTime.now(ZoneOffset.UTC).toString()

private val PluginProtocolJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
    encodeDefaults = true
}
