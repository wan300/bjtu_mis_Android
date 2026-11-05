package cn.edu.bjtu.mis.data.thirdparty

import android.content.Context
import cn.edu.bjtu.mis.data.sync.AndroidSessionKeepAlive
import cn.edu.bjtu.mis.data.sync.KeepAliveOwner
import cn.edu.bjtu.mis.data.sync.KeepAliveRejected
import cn.edu.bjtu.mis.data.sync.PLUGIN_KEEP_ALIVE_CAPABILITY
import cn.edu.bjtu.mis.data.sync.PluginKeepAliveLease
import cn.edu.bjtu.mis.data.sync.SessionKeepAliveForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Repository hooks remain available even when no plugin page is open. */
internal object PluginSessionKeepAlive {
    var provider: PluginSessionKeepAliveProvider? = null
    fun revoke(publisher: String, plugin: String) {
        PluginNetworkSessions.provider?.clearSessions(publisher, plugin)
        PluginBackgroundTasks.revoke(publisher, plugin)
        provider?.revoke(KeepAliveOwner(publisher, plugin))
    }
    fun owners(): Set<PluginAutomationIdentity> = provider?.owners().orEmpty()
    fun attach(publisher: String, plugin: String, runtimeId: String, background: Boolean,
        capabilities: Set<String>, sink: (PluginRuntimeEvent) -> Unit) {
        if (PLUGIN_KEEP_ALIVE_CAPABILITY in capabilities) provider?.attach(
            KeepAliveOwner(publisher, plugin), runtimeId, background, sink,
        )
    }
    fun detach(runtimeId: String) { provider?.detach(runtimeId) }
    fun stopAll() { provider?.stopAll() }
}

class PluginSessionKeepAliveProvider(
    private val context: Context,
    private val repository: ThirdPartyServiceRepository,
) : PluginCapabilityProvider {
    override val capabilityIds = setOf(PLUGIN_KEEP_ALIVE_CAPABILITY)
    private val controller = AndroidSessionKeepAlive.controller(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reconcileMutex = Mutex()
    private val epochs = mutableMapOf<KeepAliveOwner, Long>()
    private val order = AtomicLong()
    private data class Sink(val owner: KeepAliveOwner, val background: Boolean, val order: Long,
        val send: (PluginRuntimeEvent) -> Unit)
    private val sinks = ConcurrentHashMap<String, Sink>()
    var supervisor: PluginAutomationSupervisor? = null

    init {
        PluginSessionKeepAlive.provider = this
        AndroidSessionKeepAlive.onForeground = { scope.launch { runCatching { reconcile(restore = true) } } }
        AndroidSessionKeepAlive.onChanged = { scope.launch { runCatching { reconcile(restore = false) } } }
    }

    override suspend fun invoke(call: PluginCapabilityCall): JsonElement {
        val owner = KeepAliveOwner(call.service.publisherSubjectId, call.service.serviceId)
        val epoch = synchronized(controller) { epochs[owner] ?: 0 }
        val current = repository.getService(owner.pluginId)
        return synchronized(controller) {
            if ((epochs[owner] ?: 0) != epoch || !authorized(current, owner) ||
                current!!.commitSha != call.service.commitSha ||
                current.updatedAt != call.service.updatedAt) {
                throw PluginRuntimeException("permission_denied", "Plugin keep-alive permission changed")
            }
            try {
                if (call.method == "getStatus") return@synchronized status(owner)
                val key = call.params.getValue("idempotencyKey").jsonPrimitive.content
                val result = controller.command(
                    owner, current.commitSha, key,
                    pluginCommandRequestDigest(call.capability, call.method, call.params), call.method,
                    call.params["requestedDurationMs"]?.jsonPrimitive?.long ?: 0,
                    call.params["leaseId"]?.jsonPrimitive?.content,
                ) {
                    AndroidSessionKeepAlive.requireStartAllowed(context, call.backgroundRuntime)
                    try { SessionKeepAliveForegroundService.requestSync(context) }
                    catch (_: Exception) { throw KeepAliveRejected("foreground_service_denied") }
                }
                if (call.method == "release") runCatching { SessionKeepAliveForegroundService.syncExisting(context) }
                AndroidSessionKeepAlive.changed()
                buildJsonObject {
                    put("receiptId", result.receiptId)
                    put("idempotencyKey", key)
                    put("completedAt", Instant.ofEpochMilli(result.completedAtMs).toString())
                    put("result", buildJsonObject {
                        result.lease?.let { put("lease", leaseJson(it)) }
                        put("released", result.released)
                    })
                }
            } catch (error: KeepAliveRejected) {
                throw PluginRuntimeException(error.code, "Keep-alive request could not be completed")
            }
        }
    }

    internal fun owners(): Set<PluginAutomationIdentity> = controller.activeLeases().map {
        PluginAutomationIdentity(it.owner.publisherSubjectId, it.owner.pluginId)
    }.toSet()

    fun revoke(owner: KeepAliveOwner) = synchronized(controller) {
        epochs[owner] = (epochs[owner] ?: 0) + 1
        controller.revoke(owner)
        runCatching { SessionKeepAliveForegroundService.syncExisting(context) }
        AndroidSessionKeepAlive.changed()
    }

    fun stopAll() = synchronized(controller) {
        controller.leases().map { it.owner }.distinct().forEach { epochs[it] = (epochs[it] ?: 0) + 1 }
        controller.stopPlugins("user_stopped")
        runCatching { SessionKeepAliveForegroundService.syncExisting(context) }
        AndroidSessionKeepAlive.changed()
    }

    fun attach(owner: KeepAliveOwner, id: String, background: Boolean, sink: (PluginRuntimeEvent) -> Unit) {
        sinks[id] = Sink(owner, background, order.incrementAndGet(), sink)
    }
    fun detach(id: String) { sinks.remove(id) }

    private suspend fun reconcile(restore: Boolean) = reconcileMutex.withLock {
        for (lease in controller.leases()) {
            val epoch = synchronized(controller) { epochs[lease.owner] ?: 0 }
            val service = repository.getService(lease.owner.pluginId)
            synchronized(controller) {
                if ((epochs[lease.owner] ?: 0) != epoch) return@synchronized
                if (!authorized(service, lease.owner) || service!!.commitSha != lease.version) {
                    controller.revoke(lease.owner)
                } else {
                    controller.validate(lease.leaseId)
                }
            }
        }
        if (restore && controller.leases().isNotEmpty()) {
            runCatching {
                AndroidSessionKeepAlive.requireStartAllowed(context, false)
                SessionKeepAliveForegroundService.requestSync(context)
            }
        }
        if (!AndroidSessionKeepAlive.running) owners().forEach {
            supervisor?.releaseKeepAliveReason(it.publisherSubjectId, it.serviceId, force = true)
        }
        if (AndroidSessionKeepAlive.running) owners().forEach {
            supervisor?.ensureService(it.publisherSubjectId, it.serviceId)
        }
        controller.drainEnded().forEach { (lease, reason) ->
            sinks.values.filter { it.owner == lease.owner }
                .sortedWith(compareBy<Sink> { it.background }.thenByDescending { it.order })
                .firstOrNull()?.send?.invoke(PluginRuntimeEvent(
                    PLUGIN_KEEP_ALIVE_CAPABILITY, "ended", data = buildJsonObject {
                        put("leaseId", lease.leaseId); put("reason", reason)
                    },
                ))
            supervisor?.releaseKeepAliveReason(lease.owner.publisherSubjectId, lease.owner.pluginId)
        }
    }

    private fun authorized(service: ThirdPartyService?, owner: KeepAliveOwner): Boolean =
        service != null && service.canRun && service.publisherSubjectId == owner.publisherSubjectId &&
            PLUGIN_KEEP_ALIVE_CAPABILITY in service.grantedCapabilities &&
            PLUGIN_KEEP_ALIVE_CAPABILITY in (service.manifest.requiredCapabilities + service.manifest.optionalCapabilities)

    private fun status(owner: KeepAliveOwner): JsonObject = buildJsonObject {
        val leases = controller.leases().filter { it.owner == owner }
        val validated = controller.activeLeases().any { it.owner == owner }
        put("active", validated && AndroidSessionKeepAlive.running)
        put("serviceState", when {
            leases.isEmpty() -> "stopped"
            !AndroidSessionKeepAlive.running || !validated -> "pending"
            AndroidSessionKeepAlive.sessionState == "degraded" -> "degraded"
            else -> "running"
        })
        put("leases", buildJsonArray { leases.forEach { add(leaseJson(it)) } })
    }
}

private fun leaseJson(lease: PluginKeepAliveLease): JsonObject = buildJsonObject {
    put("leaseId", lease.leaseId)
    put("expiresAtMs", lease.expiresAtMs)
    put("maxExpiresAtMs", lease.maxExpiresAtMs)
}
