package cn.edu.bjtu.mis.data.thirdparty

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import cn.edu.bjtu.mis.BjtuMisApplication
import cn.edu.bjtu.mis.MainActivity
import cn.edu.bjtu.mis.R

internal data class PluginAutomationIdentity(
    val publisherSubjectId: String,
    val serviceId: String,
)

internal class FixedWindowRateLimiter(
    private val limit: Int,
    private val windowMs: Long,
) {
    private var windowStartedAt = Long.MIN_VALUE
    private var count = 0

    @Synchronized
    fun tryAcquire(nowMs: Long): Boolean {
        if (windowStartedAt == Long.MIN_VALUE || nowMs - windowStartedAt >= windowMs || nowMs < windowStartedAt) {
            windowStartedAt = nowMs
            count = 0
        }
        if (count >= limit) return false
        count += 1
        return true
    }
}

internal object AndroidAutomationPolicy {
    private val settingsAction = Regex("^android\\.settings\\.[A-Z0-9_]+$")
    private val packageName = Regex("^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+$")

    fun requireSettingsAction(value: String): String = value.also {
        require(settingsAction.matches(it)) { "Only android.settings.* actions are allowed" }
    }

    fun requirePackageName(value: String): String = value.also {
        require(packageName.matches(it)) { "Invalid packageName" }
    }

    fun isSensitiveInput(password: Boolean, editable: Boolean, inputType: Int): Boolean {
        if (password) return true
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                (editable && variation in setOf(
                    InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                    InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                    InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
                    InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS,
                ))
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            InputType.TYPE_CLASS_PHONE -> editable
            else -> false
        }
    }
}

/** Process-local bridge owned by the system AccessibilityService. */
object AndroidAccessibilityController {
    private data class RuntimeSink(
        val runtimeId: String,
        val background: Boolean,
        val attachedOrder: Long,
        val send: (PluginRuntimeEvent) -> Unit,
    )

    private data class Subscription(
        val id: String,
        val identity: PluginAutomationIdentity,
        val serviceId: String,
        val eventTypes: Set<String>,
        val packageNames: Set<String>,
        val persistent: Boolean,
        val includeSource: Boolean,
        val sinks: ConcurrentHashMap<String, RuntimeSink> = ConcurrentHashMap(),
        val rateLimiter: FixedWindowRateLimiter = FixedWindowRateLimiter(60, 1_000L),
    )

    private data class CachedNode(
        val node: AccessibilityNodeInfo,
        val identity: PluginAutomationIdentity,
        val expiresAt: Long,
    )

    private data class TraversalBudget(var remaining: Int)

    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    private val nodes = LinkedHashMap<String, CachedNode>()
    private val nodeLock = Any()
    private val sinkOrder = AtomicLong()
    private val actionRateLimiters = ConcurrentHashMap<PluginAutomationIdentity, FixedWindowRateLimiter>()
    @Volatile private var service: PluginAccessibilityService? = null
    @Volatile private var automationStore: PluginAutomationStore? = null
    @Volatile private var supervisor: PluginAutomationSupervisor? = null

    fun configure(store: PluginAutomationStore, runtimeSupervisor: PluginAutomationSupervisor) {
        configureStore(store)
        supervisor = runtimeSupervisor
    }

    fun configureStore(store: PluginAutomationStore) {
        automationStore = store
    }

    fun attach(next: PluginAccessibilityService) {
        service = next
        refreshAutomationForeground()
        supervisor?.onAccessibilityConnected()
    }

    fun detach(current: PluginAccessibilityService) {
        if (service === current) service = null
        supervisor?.onAccessibilityDisconnected()
        clearNodes()
        subscriptions.values.forEach { subscription ->
            subscription.sinks.entries.removeIf { it.value.background }
            if (!subscription.persistent && subscription.sinks.isEmpty()) subscriptions.remove(subscription.id)
        }
    }

    fun isConnected(): Boolean = service != null

    fun hasPersistentSubscriptions(publisherSubjectId: String, serviceId: String): Boolean =
        automationStore?.list()?.any {
            it.publisherSubjectId == publisherSubjectId &&
                it.serviceId == serviceId &&
                it.capability == "android.accessibility.events@1"
        } == true

    /** Rebinds durable subscriptions after a foreground or supervised WebView runtime starts. */
    fun attachRuntime(
        publisherSubjectId: String,
        serviceId: String,
        runtimeId: String,
        backgroundRuntime: Boolean,
        eventSink: (PluginRuntimeEvent) -> Unit,
        grantedCapabilities: Set<String> = setOf("android.accessibility.events@1"),
    ) {
        if ("android.accessibility.events@1" !in grantedCapabilities) return
        val identity = PluginAutomationIdentity(publisherSubjectId, serviceId)
        automationStore?.list()
            ?.asSequence()
            ?.filter {
                it.persistent &&
                    it.publisherSubjectId == publisherSubjectId &&
                    it.serviceId == serviceId &&
                    it.capability == "android.accessibility.events@1"
            }
            ?.forEach { record ->
                val subscription = subscriptions[record.subscriptionId] ?: Subscription(
                    id = record.subscriptionId,
                    identity = identity,
                    serviceId = serviceId,
                    eventTypes = record.eventTypes,
                    packageNames = record.packageNames,
                    persistent = true,
                    includeSource = record.includeSource,
                ).also { subscriptions[record.subscriptionId] = it }
                subscription.sinks[runtimeId] = RuntimeSink(
                    runtimeId = runtimeId,
                    background = backgroundRuntime,
                    attachedOrder = sinkOrder.incrementAndGet(),
                    send = eventSink,
                )
            }
    }

    fun detachRuntime(runtimeId: String) {
        subscriptions.values.forEach { subscription ->
            subscription.sinks.remove(runtimeId)
            if (!subscription.persistent && subscription.sinks.isEmpty()) {
                subscriptions.remove(subscription.id, subscription)
            }
        }
    }

    fun revokeService(publisherSubjectId: String, serviceId: String) {
        val identity = PluginAutomationIdentity(publisherSubjectId, serviceId)
        subscriptions.entries.removeIf { it.value.identity == identity }
        removeNodes(identity)
        actionRateLimiters.remove(identity)
        automationStore?.removeService(publisherSubjectId, serviceId)
        supervisor?.stopService(publisherSubjectId, serviceId)
        refreshAutomationForeground()
    }

    fun revokeAccessibility(publisherSubjectId: String, serviceId: String) {
        val identity = PluginAutomationIdentity(publisherSubjectId, serviceId)
        subscriptions.entries.removeIf { it.value.identity == identity }
        removeNodes(identity)
        actionRateLimiters.remove(identity)
        automationStore?.removeCapability(publisherSubjectId, serviceId, "android.accessibility.events@1")
        if (automationStore?.list()?.none {
                it.publisherSubjectId == publisherSubjectId && it.serviceId == serviceId
            } != false) {
            supervisor?.releaseKeepAliveReason(publisherSubjectId, serviceId)
        }
        refreshAutomationForeground()
    }

    fun stopServiceRuntime(publisherSubjectId: String, serviceId: String) {
        // Remove the durable keep-alive reason before stopping its runtime.
        // Otherwise the next reconciliation would start it again.
        PluginSessionKeepAlive.revoke(publisherSubjectId, serviceId)
        val identity = PluginAutomationIdentity(publisherSubjectId, serviceId)
        subscriptions.entries.removeIf { it.value.identity == identity }
        removeNodes(identity)
        automationStore?.removeService(publisherSubjectId, serviceId)
        supervisor?.stopService(publisherSubjectId, serviceId)
        refreshAutomationForeground()
    }

    fun stopAllAutomation() {
        supervisor?.stopAll()
        subscriptions.clear()
        actionRateLimiters.clear()
        clearNodes()
        automationStore?.clear()
        refreshAutomationForeground()
    }

    suspend fun invoke(call: PluginCapabilityCall): JsonElement = when (call.method) {
        "getStatus" -> status(call)
        "subscribe" -> subscribe(call)
        "unsubscribe" -> unsubscribe(call)
        "listSubscriptions" -> listSubscriptions(call)
        "getRoot" -> getRoot(call, call.params)
        "find" -> find(call)
        "get" -> get(call)
        "performNode" -> performNode(call)
        "performGlobal" -> performGlobal(call)
        "dispatchGesture" -> dispatchGesture(call)
        else -> throw PluginRuntimeException("invalid_request", "Unknown Android accessibility method")
    }

    private fun requireService(): PluginAccessibilityService =
        service ?: throw PluginRuntimeException(
            "capability_unavailable",
            "Android AccessibilityService is not connected; enable BJTU MIS in system accessibility settings",
            details = buildJsonObject { put("settingsAction", Settings.ACTION_ACCESSIBILITY_SETTINGS) },
        )

    fun status(call: PluginCapabilityCall, enabled: Boolean = service != null): JsonObject = buildJsonObject {
        put("enabled", enabled)
        put("connected", service != null)
        put("subscriptionCount", subscriptionRecords(call).size)
    }

    private fun subscribe(call: PluginCapabilityCall): JsonObject {
        requireService()
        val identity = call.identity()
        val eventTypes = call.params["eventTypes"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
            ?.toSet()
            .orEmpty()
        if (eventTypes.isEmpty()) throw IllegalArgumentException("eventTypes must not be empty")
        val packageNames = call.params["packageNames"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.toSet()
            .orEmpty()
        val persistent = call.params["persistent"]?.jsonPrimitive?.content == "true"
        val includeSource = call.params["includeSource"]?.jsonPrimitive?.content == "true"
        val persisted = if (persistent) {
            automationStore?.list()?.firstOrNull { record ->
                record.publisherSubjectId == identity.publisherSubjectId &&
                    record.serviceId == identity.serviceId &&
                    record.capability == "android.accessibility.events@1" &&
                    record.eventTypes == eventTypes &&
                    record.packageNames == packageNames &&
                    record.includeSource == includeSource
            }
        } else {
            null
        }
        val existing = persisted?.subscriptionId?.let(subscriptions::get)
            ?.takeIf { it.identity == identity }
        if (
            existing == null &&
            persisted == null &&
            subscriptionRecords(call).size >= MAX_SUBSCRIPTIONS_PER_PLUGIN
        ) {
            throw PluginRuntimeException(
                "quota_exceeded",
                "A plugin may have at most $MAX_SUBSCRIPTIONS_PER_PLUGIN accessibility subscriptions",
            )
        }
        val id = existing?.id ?: persisted?.subscriptionId
            ?.takeUnless(subscriptions::containsKey)
            ?: UUID.randomUUID().toString()
        if (persisted != null && persisted.subscriptionId != id) {
            automationStore?.remove(identity.publisherSubjectId, identity.serviceId, persisted.subscriptionId)
        }
        val subscription = existing ?: Subscription(
            id = id,
            identity = identity,
            serviceId = identity.serviceId,
            eventTypes = eventTypes,
            packageNames = packageNames,
            persistent = persistent,
            includeSource = includeSource,
        ).also { subscriptions[id] = it }
        subscription.sinks[call.runtimeId] = RuntimeSink(
            runtimeId = call.runtimeId,
            background = call.backgroundRuntime,
            attachedOrder = sinkOrder.incrementAndGet(),
            send = call.eventSink,
        )
        if (persistent) {
            automationStore?.save(
                PluginAutomationSubscriptionRecord(
                    subscriptionId = id,
                    publisherSubjectId = identity.publisherSubjectId,
                    serviceId = identity.serviceId,
                    eventTypes = eventTypes,
                    packageNames = packageNames,
                    includeSource = includeSource,
                    capability = "android.accessibility.events@1",
                ),
            )
            supervisor?.ensureService(identity.publisherSubjectId, identity.serviceId)
            refreshAutomationForeground()
        }
        return subscriptionJson(subscription)
    }

    private fun unsubscribe(call: PluginCapabilityCall): JsonObject {
        val id = call.params.requiredString("subscriptionId")
        val identity = call.identity()
        val removed = subscriptions[id]
            ?.takeIf { it.identity == identity }
            ?.let { subscriptions.remove(id, it) }
            ?: false
        val removedPersisted = automationStore?.remove(
            identity.publisherSubjectId,
            identity.serviceId,
            id,
        ) == true
        if (!hasPersistentSubscriptions(identity.publisherSubjectId, identity.serviceId)) {
            supervisor?.stopService(identity.publisherSubjectId, identity.serviceId)
        }
        refreshAutomationForeground()
        return buildJsonObject { put("deleted", removed || removedPersisted) }
    }

    private fun listSubscriptions(call: PluginCapabilityCall): JsonArray = buildJsonArray {
        subscriptionRecords(call).forEach { add(subscriptionJson(it)) }
    }

    private fun subscriptionRecords(call: PluginCapabilityCall): List<PluginAutomationSubscriptionRecord> {
        val identity = call.identity()
        val persistent = automationStore?.list().orEmpty()
            .filter {
                it.publisherSubjectId == identity.publisherSubjectId &&
                    it.serviceId == identity.serviceId &&
                    it.capability == "android.accessibility.events@1"
            }
        val persistedIds = persistent.mapTo(mutableSetOf(), PluginAutomationSubscriptionRecord::subscriptionId)
        val ephemeral = subscriptions.values
            .filter { it.identity == identity && it.id !in persistedIds }
            .map { subscription ->
                PluginAutomationSubscriptionRecord(
                    subscriptionId = subscription.id,
                    publisherSubjectId = identity.publisherSubjectId,
                    serviceId = identity.serviceId,
                    eventTypes = subscription.eventTypes,
                    packageNames = subscription.packageNames,
                    includeSource = subscription.includeSource,
                    persistent = false,
                )
            }
        return (persistent + ephemeral).sortedBy(PluginAutomationSubscriptionRecord::subscriptionId)
    }

    private fun subscriptionJson(subscription: Subscription): JsonObject = buildJsonObject {
        put("subscriptionId", subscription.id)
        put("eventTypes", buildJsonArray { subscription.eventTypes.sorted().forEach { add(JsonPrimitive(it)) } })
        put("packageNames", buildJsonArray { subscription.packageNames.sorted().forEach { add(JsonPrimitive(it)) } })
        put("persistent", subscription.persistent)
        put("includeSource", subscription.includeSource)
    }

    private fun subscriptionJson(record: PluginAutomationSubscriptionRecord): JsonObject = buildJsonObject {
        put("subscriptionId", record.subscriptionId)
        put("eventTypes", buildJsonArray { record.eventTypes.sorted().forEach { add(JsonPrimitive(it)) } })
        put("packageNames", buildJsonArray { record.packageNames.sorted().forEach { add(JsonPrimitive(it)) } })
        put("persistent", record.persistent)
        put("includeSource", record.includeSource)
    }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        val eventType = eventTypeName(event.eventType)
        val packageName = event.packageName?.toString().orEmpty()
        val className = event.className?.toString().orEmpty()
        subscriptions.values
            .filter { subscription ->
                ("*" in subscription.eventTypes || eventType in subscription.eventTypes) &&
                    (subscription.packageNames.isEmpty() || packageName in subscription.packageNames)
            }
            .forEach { subscription ->
                val sink = subscription.sinks.values
                    .filterNot(RuntimeSink::background)
                    .maxByOrNull(RuntimeSink::attachedOrder)
                    ?: subscription.sinks.values.maxByOrNull(RuntimeSink::attachedOrder)
                    ?: return@forEach
                if (!subscription.rateLimiter.tryAcquire(SystemClock.elapsedRealtime())) return@forEach
                val sourceNode = event.source
                val sensitive = event.isPassword || sourceNode?.let(::isSensitiveNode) == true
                val source = if (subscription.includeSource && sourceNode != null) {
                    snapshotNode(
                        sourceNode,
                        subscription.identity,
                        depth = 0,
                        maxDepth = MAX_NODE_DEPTH,
                        budget = TraversalBudget(MAX_SNAPSHOT_NODES),
                    )
                } else null
                sink.send(
                    PluginRuntimeEvent(
                        capability = "android.accessibility.events@1",
                        event = "received",
                        data = buildJsonObject {
                            put("subscriptionId", subscription.id)
                            put("eventType", eventType)
                            put("packageName", packageName)
                            put("className", className)
                            put("eventTime", event.eventTime)
                            if (!sensitive) {
                                put("text", buildJsonArray {
                                    event.text.take(128).forEach { value ->
                                        add(JsonPrimitive(value.toString().take(MAX_TEXT_CHARS)))
                                    }
                                })
                                event.contentDescription?.toString()?.take(MAX_TEXT_CHARS)?.let {
                                    put("contentDescription", it)
                                }
                            }
                            put("source", source ?: kotlinx.serialization.json.JsonNull)
                        },
                    ),
                )
            }
    }

    private fun getRoot(call: PluginCapabilityCall, params: JsonObject): JsonObject {
        val owner = requireService()
        val root = params["windowId"]?.jsonPrimitive?.intOrNull?.let { id ->
            owner.windows.firstOrNull { it.id == id }?.root
        } ?: owner.rootInActiveWindow
            ?: throw PluginRuntimeException("capability_unavailable", "No active accessibility window")
        return snapshotNode(
            root,
            call.identity(),
            depth = 0,
            maxDepth = params["maxDepth"]?.jsonPrimitive?.intOrNull?.coerceIn(1, MAX_NODE_DEPTH)
                ?: MAX_NODE_DEPTH,
            budget = TraversalBudget(
                params["maxNodes"]?.jsonPrimitive?.intOrNull?.coerceIn(1, MAX_SNAPSHOT_NODES)
                    ?: MAX_SNAPSHOT_NODES,
            ),
        )
    }

    private fun find(call: PluginCapabilityCall): JsonObject {
        val root = getRoot(call, call.params)
        val selector = call.params["selector"]?.jsonObject ?: JsonObject(emptyMap())
        val maxResults = call.params["maxResults"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 256) ?: 256
        val matches = mutableListOf<JsonElement>()
        var truncated = false
        fun visit(node: JsonElement) {
            if (truncated) return
            val obj = node.jsonObject
            if (matchesSelector(obj, selector)) {
                if (matches.size < maxResults) matches += node else {
                    truncated = true
                    return
                }
            }
            obj["children"]?.jsonArray?.forEach(::visit)
        }
        visit(root)
        return buildJsonObject {
            put("nodes", buildJsonArray { matches.forEach(::add) })
            put("truncated", truncated)
        }
    }

    private fun matchesSelector(node: JsonObject, selector: JsonObject): Boolean =
        selector.all { (key, value) ->
            val actual = node[key]?.jsonPrimitive ?: return@all false
            when {
                value is JsonPrimitive && value.isString -> actual.content == value.content
                value is JsonPrimitive -> actual.content == value.content
                else -> false
            }
        }

    private fun get(call: PluginCapabilityCall): JsonObject {
        val cached = getCachedNode(call.identity(), call.params.requiredString("nodeId"))
        return snapshotNode(cached.node, call.identity(), 0, 1, TraversalBudget(1))
    }

    private suspend fun performNode(call: PluginCapabilityCall): JsonObject {
        val owner = requireService()
        requireActionQuota(call)
        val params = call.params
        val cached = getCachedNode(call.identity(), params.requiredString("nodeId"))
        val actionName = params.requiredString("action")
        val action = nodeAction(actionName)
        val args = params["arguments"]?.jsonObject
        val performed = withContext(Dispatchers.Main.immediate) {
            if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                val text = args?.get("text")?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalArgumentException("setText requires arguments.text")
                Bundle().also { it.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
                    .let { cached.node.performAction(action, it) }
            } else if (action == AccessibilityNodeInfo.ACTION_SET_SELECTION) {
                val bundle = Bundle().apply {
                    putInt(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                        args?.get("start")?.jsonPrimitive?.intOrNull ?: 0,
                    )
                    putInt(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                        args?.get("end")?.jsonPrimitive?.intOrNull ?: 0,
                    )
                }
                cached.node.performAction(action, bundle)
            } else {
                cached.node.performAction(action)
            }
        }
        return buildJsonObject { put("performed", performed) }
    }

    private suspend fun performGlobal(call: PluginCapabilityCall): JsonObject {
        val owner = requireService()
        requireActionQuota(call)
        val action = when (call.params.requiredString("action")) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quickSettings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "powerDialog" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "splitScreen" -> AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            else -> throw IllegalArgumentException("Unsupported global accessibility action")
        }
        val performed = withContext(Dispatchers.Main.immediate) { owner.performGlobalAction(action) }
        return buildJsonObject { put("performed", performed) }
    }

    private suspend fun dispatchGesture(call: PluginCapabilityCall): JsonObject {
        val owner = requireService()
        requireActionQuota(call)
        val params = call.params
        val strokes = params["strokes"]?.jsonArray ?: throw IllegalArgumentException("strokes is required")
        val description = GestureDescription.Builder()
        strokes.forEach { element ->
            val stroke = element.jsonObject
            val points = stroke["points"]?.jsonArray ?: throw IllegalArgumentException("stroke.points is required")
            if (points.isEmpty()) throw IllegalArgumentException("stroke.points must not be empty")
            val path = Path()
            points.forEachIndexed { index, point ->
                val value = point.jsonObject
                val x = value["x"]?.jsonPrimitive?.doubleOrNull ?: throw IllegalArgumentException("point.x is required")
                val y = value["y"]?.jsonPrimitive?.doubleOrNull ?: throw IllegalArgumentException("point.y is required")
                if (index == 0) path.moveTo(x.toFloat(), y.toFloat()) else path.lineTo(x.toFloat(), y.toFloat())
            }
            description.addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    stroke["startTimeMs"]?.jsonPrimitive?.longOrNull ?: 0L,
                    (stroke["durationMs"]?.jsonPrimitive?.longOrNull ?: 100L).coerceIn(1L, 60000L),
                ),
            )
        }
        val performed = suspendCancellableCoroutine { continuation ->
            val accepted = owner.dispatchGesture(
                description.build(),
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
                null,
            )
            if (!accepted && continuation.isActive) continuation.resume(false)
            continuation.invokeOnCancellation { }
        }
        return buildJsonObject { put("performed", performed) }
    }

    private fun nodeAction(name: String): Int = when (name) {
        "click" -> AccessibilityNodeInfo.ACTION_CLICK
        "longClick" -> AccessibilityNodeInfo.ACTION_LONG_CLICK
        "focus" -> AccessibilityNodeInfo.ACTION_FOCUS
        "clearFocus" -> AccessibilityNodeInfo.ACTION_CLEAR_FOCUS
        "select" -> AccessibilityNodeInfo.ACTION_SELECT
        "clearSelection" -> AccessibilityNodeInfo.ACTION_CLEAR_SELECTION
        "scrollForward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        "scrollBackward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        "scrollUp" -> 16908346
        "scrollDown" -> 16908344
        "scrollLeft" -> 16908345
        "scrollRight" -> 16908347
        "expand" -> AccessibilityNodeInfo.ACTION_EXPAND
        "collapse" -> AccessibilityNodeInfo.ACTION_COLLAPSE
        "dismiss" -> AccessibilityNodeInfo.ACTION_DISMISS
        "showOnScreen" -> 16908342
        "setText" -> AccessibilityNodeInfo.ACTION_SET_TEXT
        "setSelection" -> AccessibilityNodeInfo.ACTION_SET_SELECTION
        "copy" -> AccessibilityNodeInfo.ACTION_COPY
        "paste" -> AccessibilityNodeInfo.ACTION_PASTE
        else -> throw IllegalArgumentException("Unsupported node accessibility action")
    }

    private fun snapshotNode(
        node: AccessibilityNodeInfo,
        identity: PluginAutomationIdentity,
        depth: Int,
        maxDepth: Int,
        budget: TraversalBudget,
    ): JsonObject {
        check(budget.remaining > 0) { "Accessibility node traversal budget exhausted" }
        budget.remaining -= 1
        val nodeId = cacheNode(node, identity)
        val bounds = Rect().also(node::getBoundsInScreen)
        val sensitive = isSensitiveNode(node)
        return buildJsonObject {
            put("nodeId", nodeId)
            put("windowId", node.windowId)
            put("className", node.className?.toString().orEmpty().take(512))
            put("packageName", node.packageName?.toString().orEmpty().take(256))
            node.viewIdResourceName?.take(512)?.let { put("viewIdResourceName", it) }
            if (!sensitive) {
                node.text?.toString()?.take(MAX_TEXT_CHARS)?.let { put("text", it) }
                node.contentDescription?.toString()?.take(MAX_TEXT_CHARS)?.let { put("contentDescription", it) }
            }
            put("bounds", buildJsonObject {
                put("left", bounds.left)
                put("top", bounds.top)
                put("right", bounds.right)
                put("bottom", bounds.bottom)
            })
            put("actions", buildJsonArray {
                node.actionList.take(64).forEach { add(JsonPrimitive(actionName(it.id))) }
            })
            put("clickable", node.isClickable)
            put("enabled", node.isEnabled)
            put("focused", node.isFocused)
            put("focusable", node.isFocusable)
            put("scrollable", node.isScrollable)
            put("selected", node.isSelected)
            put("checked", node.isChecked)
            put("password", node.isPassword)
            put("sensitive", sensitive)
            put("childCount", node.childCount)
            if (depth < maxDepth && budget.remaining > 0) {
                put("children", buildJsonArray {
                    val limit = minOf(node.childCount, budget.remaining)
                    for (index in 0 until limit) {
                        if (budget.remaining <= 0) break
                        runCatching { node.getChild(index) }.getOrNull()?.let { child ->
                            add(snapshotNode(child, identity, depth + 1, maxDepth, budget))
                        }
                    }
                })
            }
        }
    }

    private fun cacheNode(node: AccessibilityNodeInfo, identity: PluginAutomationIdentity): String =
        synchronized(nodeLock) {
            removeExpiredNodesLocked(SystemClock.elapsedRealtime())
            while (nodes.size >= MAX_CACHED_NODES) {
                val oldest = nodes.entries.firstOrNull() ?: break
                nodes.remove(oldest.key)?.node?.recycle()
            }
            val nodeId = UUID.randomUUID().toString()
            nodes[nodeId] = CachedNode(
                node = AccessibilityNodeInfo.obtain(node),
                identity = identity,
                expiresAt = SystemClock.elapsedRealtime() + NODE_TTL_MS,
            )
            nodeId
        }

    private fun getCachedNode(identity: PluginAutomationIdentity, nodeId: String): CachedNode =
        synchronized(nodeLock) {
            val now = SystemClock.elapsedRealtime()
            removeExpiredNodesLocked(now)
            nodes[nodeId]?.takeIf { it.identity == identity }
                ?: throw PluginRuntimeException("invalid_request", "Unknown or expired accessibility nodeId")
        }

    private fun removeNodes(identity: PluginAutomationIdentity) = synchronized(nodeLock) {
        val matching = nodes.filterValues { it.identity == identity }.keys.toList()
        matching.forEach { nodeId -> nodes.remove(nodeId)?.node?.recycle() }
    }

    private fun clearNodes() = synchronized(nodeLock) {
        nodes.values.forEach { it.node.recycle() }
        nodes.clear()
    }

    private fun removeExpiredNodesLocked(now: Long) {
        val expired = nodes.filterValues { it.expiresAt <= now }.keys.toList()
        expired.forEach { nodeId -> nodes.remove(nodeId)?.node?.recycle() }
    }

    private fun requireActionQuota(call: PluginCapabilityCall) {
        val limiter = actionRateLimiters.getOrPut(call.identity()) {
            FixedWindowRateLimiter(ACTIONS_PER_MINUTE, 60_000L)
        }
        if (!limiter.tryAcquire(SystemClock.elapsedRealtime())) {
            throw PluginRuntimeException("quota_exceeded", "Accessibility action quota exceeded")
        }
    }

    private fun isSensitiveNode(node: AccessibilityNodeInfo): Boolean =
        AndroidAutomationPolicy.isSensitiveInput(node.isPassword, node.isEditable, node.inputType)

    private fun refreshAutomationForeground() {
        service?.setAutomationForeground(automationStore?.list()?.isNotEmpty() == true)
    }

    private fun actionName(action: Int): String = when (action) {
        AccessibilityNodeInfo.ACTION_CLICK -> "click"
        AccessibilityNodeInfo.ACTION_LONG_CLICK -> "longClick"
        AccessibilityNodeInfo.ACTION_FOCUS -> "focus"
        AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> "clearFocus"
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "scrollForward"
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "scrollBackward"
        AccessibilityNodeInfo.ACTION_SET_TEXT -> "setText"
        AccessibilityNodeInfo.ACTION_SET_SELECTION -> "setSelection"
        else -> "action:$action"
    }

    private fun eventTypeName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "viewClicked"
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "viewLongClicked"
        AccessibilityEvent.TYPE_VIEW_SELECTED -> "viewSelected"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "viewFocused"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "viewTextChanged"
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "windowStateChanged"
        AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "notificationStateChanged"
        AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> "viewHoverEnter"
        AccessibilityEvent.TYPE_VIEW_HOVER_EXIT -> "viewHoverExit"
        AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START -> "touchExplorationGestureStart"
        AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END -> "touchExplorationGestureEnd"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "windowContentChanged"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "viewScrolled"
        AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> "viewTextSelectionChanged"
        AccessibilityEvent.TYPE_ANNOUNCEMENT -> "announcement"
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> "viewAccessibilityFocused"
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> "viewAccessibilityFocusCleared"
        AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY -> "viewTextTraversed"
        AccessibilityEvent.TYPE_GESTURE_DETECTION_START -> "gestureDetectionStart"
        AccessibilityEvent.TYPE_GESTURE_DETECTION_END -> "gestureDetectionEnd"
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> "touchInteractionStart"
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> "touchInteractionEnd"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "windowsChanged"
        AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED -> "viewContextClicked"
        AccessibilityEvent.TYPE_ASSIST_READING_CONTEXT -> "assistReadingContext"
        else -> "type:$type"
    }

    private const val MAX_SUBSCRIPTIONS_PER_PLUGIN = 16
    private const val MAX_SNAPSHOT_NODES = 4096
    private const val MAX_NODE_DEPTH = 64
    private const val MAX_CACHED_NODES = 16384
    private const val MAX_TEXT_CHARS = 4096
    private const val NODE_TTL_MS = 30_000L
    private const val ACTIONS_PER_MINUTE = 120
}

class PluginAccessibilityService : AccessibilityService() {
    private var automationForeground = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        val container = (application as BjtuMisApplication).container
        AndroidAccessibilityController.configure(
            container.pluginAutomationStore,
            container.pluginAutomationSupervisor,
        )
        AndroidAccessibilityController.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        AndroidAccessibilityController.onAccessibilityEvent(event)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        AndroidAccessibilityController.detach(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    fun setAutomationForeground(enabled: Boolean) {
        if (enabled == automationForeground) return
        automationForeground = enabled
        if (!enabled) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                "插件自动化",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示当前由无障碍服务运行的插件自动化"
                setShowBadge(false)
            },
        )
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopAll = PendingIntent.getBroadcast(
            this,
            1,
            Intent(this, StopPluginAutomationReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.loading_mascot)
            .setContentTitle(getString(R.string.plugin_automation_notification_title))
            .setContentText(getString(R.string.plugin_automation_notification_text))
            .setContentIntent(openApp)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.plugin_automation_stop_all),
                stopAll,
            )
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val NOTIFICATION_CHANNEL = "plugin_automation"
        const val NOTIFICATION_ID = 24017
    }
}

class StopPluginAutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val container = (context.applicationContext as BjtuMisApplication).container
        AndroidAccessibilityController.configure(
            container.pluginAutomationStore,
            container.pluginAutomationSupervisor,
        )
        AndroidAccessibilityController.stopAllAutomation()
        AndroidNativeEventController.stopAll()
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                container.thirdPartyServiceRepository.clearAllAppAutoStart()
                container.pluginAutomationSupervisor.onAutoStartGrantsCleared()
            } finally {
                pending.finish()
            }
        }
    }
}

class AndroidSystemCapabilityProvider(
    private val context: Context? = null,
    automationStore: PluginAutomationStore? = null,
) : PluginCapabilityProvider {
    init {
        automationStore?.let(AndroidAccessibilityController::configureStore)
    }
    private val settingsRateLimiters = ConcurrentHashMap<PluginAutomationIdentity, FixedWindowRateLimiter>()
    override val capabilityIds: Set<String> = setOf(
        "android.accessibility.events@1",
        "android.accessibility.nodes@1",
        "android.accessibility.actions@1",
        "android.packages.read@1",
        "android.settings.open@1",
    )

    override suspend fun invoke(call: PluginCapabilityCall): JsonElement = when {
        call.capability == "android.accessibility.events@1" && call.method == "getStatus" ->
            AndroidAccessibilityController.status(call, isAccessibilityServiceEnabled())
        call.capability.startsWith("android.accessibility.") -> AndroidAccessibilityController.invoke(call)
        call.capability == "android.packages.read@1" -> invokePackages(call)
        call.capability == "android.settings.open@1" -> invokeSettings(call)
        else -> throw PluginRuntimeException("invalid_request", "Unknown Android system capability")
    }

    private fun packageManager(): PackageManager =
        context?.packageManager ?: throw PluginRuntimeException("capability_unavailable", "PackageManager is unavailable")

    private fun isAccessibilityServiceEnabled(): Boolean {
        val appContext = context ?: return AndroidAccessibilityController.isConnected()
        val manager = appContext.getSystemService(AccessibilityManager::class.java) ?: return false
        return manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                info.resolveInfo.serviceInfo.packageName == appContext.packageName &&
                    info.resolveInfo.serviceInfo.name == PluginAccessibilityService::class.java.name
            }
    }

    @Suppress("DEPRECATION")
    private fun installedPackages(): List<PackageInfo> = packageManager().getInstalledPackages(
        PackageManager.GET_PERMISSIONS or
            PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or
            if (android.os.Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES,
    )

    private fun invokePackages(call: PluginCapabilityCall): JsonElement {
        val manager = packageManager()
        return when (call.method) {
            "list" -> {
                val includeSystem = call.params["includeSystem"]?.jsonPrimitive?.content != "false"
                val includeDisabled = call.params["includeDisabled"]?.jsonPrimitive?.content == "true"
                val selected = installedPackages()
                    .asSequence()
                    .filter { includeSystem || it.applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) == 0 }
                    .filter { includeDisabled || it.applicationInfo?.enabled != false }
                    .take(MAX_PACKAGES + 1)
                    .toList()
                val packages = selected
                    .take(MAX_PACKAGES)
                    .map { packageInfoJson(manager, it) }
                buildJsonObject {
                    put("packages", buildJsonArray { packages.forEach(::add) })
                    put("truncated", selected.size > MAX_PACKAGES)
                }
            }
            "get" -> {
                val name = call.params.requiredString("packageName")
                val info = runCatching { manager.getPackageInfo(name, packageFlags()) }
                    .getOrElse { throw PluginRuntimeException("invalid_request", "Unknown packageName") }
                packageInfoJson(manager, info)
            }
            "resolveIntent" -> {
                val action = call.params.requiredString("action")
                val intent = Intent(action).apply {
                    call.params["dataUri"]?.jsonPrimitive?.contentOrNull?.let { data = Uri.parse(it) }
                }
                val activities = manager.queryIntentActivities(intent, 0)
                    .take(256)
                    .mapNotNull { it.activityInfo?.let(::activityJson) }
                buildJsonObject { put("activities", buildJsonArray { activities.forEach(::add) }) }
            }
            else -> throw PluginRuntimeException("invalid_request", "Unknown android.packages method")
        }
    }

    private fun invokeSettings(call: PluginCapabilityCall): JsonElement {
        val appContext = context ?: throw PluginRuntimeException("capability_unavailable", "Android context is unavailable")
        val limiter = settingsRateLimiters.getOrPut(call.identity()) {
            FixedWindowRateLimiter(SETTINGS_OPENS_PER_MINUTE, 60_000L)
        }
        if (!limiter.tryAcquire(SystemClock.elapsedRealtime())) {
            throw PluginRuntimeException("quota_exceeded", "Settings open quota exceeded")
        }
        val action = AndroidAutomationPolicy.requireSettingsAction(call.params.requiredString("action"))
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        call.params["packageName"]?.jsonPrimitive?.contentOrNull?.let { packageName ->
            AndroidAutomationPolicy.requirePackageName(packageName)
            runCatching { appContext.packageManager.getApplicationInfo(packageName, 0) }
                .getOrElse { throw IllegalArgumentException("Unknown packageName") }
            intent.data = Uri.parse("package:$packageName")
        }
        if (intent.resolveActivity(appContext.packageManager) == null) {
            throw PluginRuntimeException("capability_unavailable", "No system activity resolves the requested Settings action")
        }
        runCatching { appContext.startActivity(intent) }
            .getOrElse { throw PluginRuntimeException("capability_unavailable", "Unable to open the requested Settings action") }
        return buildJsonObject {
            put("opened", true)
            put("action", action)
        }
    }

    @Suppress("DEPRECATION")
    private fun packageFlags(): Int = PackageManager.GET_PERMISSIONS or
        PackageManager.GET_ACTIVITIES or
        PackageManager.GET_SERVICES or
        PackageManager.GET_RECEIVERS or
        PackageManager.GET_PROVIDERS or
        if (android.os.Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    private fun packageInfoJson(manager: PackageManager, info: PackageInfo): JsonObject {
        val application = info.applicationInfo
        val requested = info.requestedPermissions.orEmpty()
        val flags = info.requestedPermissionsFlags ?: intArrayOf()
        return buildJsonObject {
            put("packageName", info.packageName)
            put("label", application?.loadLabel(manager)?.toString().orEmpty().take(512))
            put("versionName", info.versionName.orEmpty())
            put("versionCode", if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong())
            put("uid", (application?.uid ?: 0).coerceAtLeast(0))
            put("enabled", application?.enabled == true)
            put("system", application?.flags?.and(ApplicationInfo.FLAG_SYSTEM) != 0)
            put("firstInstallTime", info.firstInstallTime)
            put("lastUpdateTime", info.lastUpdateTime)
            put("requestedPermissions", buildJsonArray { requested.forEach { add(JsonPrimitive(it)) } })
            put("grantedPermissions", buildJsonArray {
                requested.forEachIndexed { index, permission ->
                    if (index < flags.size && flags[index].and(PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) add(JsonPrimitive(permission))
                }
            })
            put("signingCertificates", buildJsonArray { certificateDigests(info).forEach { add(JsonPrimitive(it)) } })
            put("components", componentsJson(info))
        }
    }

    private fun componentsJson(info: PackageInfo): JsonObject {
        var remaining = MAX_COMPONENTS
        fun take(values: Array<out ComponentInfo>): JsonArray {
            val selected = values.take(remaining)
            remaining -= selected.size
            return buildJsonArray { selected.forEach { add(JsonPrimitive(it.name)) } }
        }
        return buildJsonObject {
            put("activities", take(info.activities.orEmpty()))
            put("services", take(info.services.orEmpty()))
            put("receivers", take(info.receivers.orEmpty()))
            put("providers", take(info.providers.orEmpty()))
        }
    }

    private fun activityJson(info: ActivityInfo): JsonObject = buildJsonObject {
        put("packageName", info.packageName)
        put("className", info.name)
        put("exported", info.exported)
    }

    @Suppress("DEPRECATION")
    private fun certificateDigests(info: PackageInfo): List<String> {
        val signatures = if (android.os.Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners.orEmpty().toList()
        } else {
            info.signatures.orEmpty().toList()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }
    }

    private companion object {
        const val MAX_PACKAGES = 4096
        const val MAX_COMPONENTS = 4096
        const val SETTINGS_OPENS_PER_MINUTE = 30
    }
}

private fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("Missing $name")

private fun PluginCapabilityCall.identity(): PluginAutomationIdentity =
    PluginAutomationIdentity(service.publisherSubjectId, service.serviceId)
