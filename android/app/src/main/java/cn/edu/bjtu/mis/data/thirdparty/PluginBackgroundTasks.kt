package cn.edu.bjtu.mis.data.thirdparty

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import cn.edu.bjtu.mis.BjtuMisApplication
import cn.edu.bjtu.mis.MainActivity
import cn.edu.bjtu.mis.R
import cn.edu.bjtu.mis.data.sync.AndroidSessionKeepAlive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

internal object PluginBackgroundTasks {
    var provider: PluginBackgroundTaskProvider? = null
    fun revoke(publisher: String, plugin: String) { provider?.revoke(publisher, plugin) }
    fun stopAll() { provider?.stopAll() }
    fun owners(): Set<PluginAutomationIdentity> = provider?.owners().orEmpty()
    fun attach(publisher: String, plugin: String, runtime: String, background: Boolean,
        grants: Set<String>, sink: (PluginRuntimeEvent) -> Unit) {
        if (PLUGIN_BACKGROUND_TASK_CAPABILITY in grants) provider?.attach(publisher, plugin, runtime, background, sink)
    }
    fun detach(runtime: String) { provider?.detach(runtime) }
}

/** Runs timers outside the WebView; no JS callback body, origin or runtime can be supplied. */
class PluginBackgroundTaskProvider(
    private val context: Context,
    private val repository: ThirdPartyServiceRepository,
    store: PluginAutomationStore,
) : PluginCapabilityProvider {
    override val capabilityIds = setOf(PLUGIN_BACKGROUND_TASK_CAPABILITY)
    private val controller = PluginBackgroundTaskController(store, System::currentTimeMillis,
        SystemClock::elapsedRealtime, { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sequence = AtomicLong()
    private val epoch = AtomicLong()
    private val rateLimits = ConcurrentHashMap<PluginAutomationIdentity, FixedWindowRateLimiter>()
    private var runningOwners = emptySet<PluginAutomationIdentity>()
    private data class Sink(val owner: PluginAutomationIdentity, val background: Boolean,
        val order: Long, val send: (PluginRuntimeEvent) -> Unit)
    private val sinks = ConcurrentHashMap<String, Sink>()
    @Volatile private var ready = CompletableDeferred<Unit>()
    private var pendingSchedules = 0
    private var serviceToken: String? = null
    @Volatile private var running = false
    var supervisor: PluginAutomationSupervisor? = null

    init {
        PluginBackgroundTasks.provider = this
        // Application initialization never starts a foreground service. Recovery waits for visible UI.
        scope.launch {
            while (true) {
                delay(250)
                try {
                    if (running) dispatch()
                    else if (AndroidSessionKeepAlive.foreground && controller.active().isNotEmpty()) startForeground(false)
                } catch (_: Exception) { stopAll() }
            }
        }
    }

    override suspend fun invoke(call: PluginCapabilityCall): JsonElement = withContext(Dispatchers.Main.immediate) {
        invokeOnMain(call)
    }

    private suspend fun invokeOnMain(call: PluginCapabilityCall): JsonElement {
        val publisher = call.service.publisherSubjectId; val plugin = call.service.serviceId
        val currentEpoch = epoch.get()
        val current = repository.getService(plugin)
        if (!authorized(current, call.service)) throw PluginRuntimeException("permission_denied", "Background task grant changed")
        return when (call.method) {
            "schedule" -> {
                if (call.backgroundRuntime || !AndroidSessionKeepAlive.foreground) {
                    throw PluginRuntimeException("foreground_required", "Schedule tasks from a visible plugin")
                }
                val interval = call.params.getValue("intervalMs").jsonPrimitive.long
                val duration = call.params.getValue("maxDurationMs").jsonPrimitive.long
                if (interval > duration) throw PluginRuntimeException("invalid_request", "Interval exceeds task lifetime")
                if (!rateLimits.getOrPut(PluginAutomationIdentity(publisher, plugin)) { FixedWindowRateLimiter(6, 60_000) }
                        .tryAcquire(SystemClock.elapsedRealtime())) {
                    throw PluginRuntimeException("quota_exceeded", "Too many task schedules")
                }
                pendingSchedules++
                try {
                    startForeground(call.backgroundRuntime)
                    if (epoch.get() != currentEpoch || !running || !authorized(repository.getService(plugin), call.service)) {
                        throw PluginRuntimeException("permission_denied", "Background task grant changed")
                    }
                    val record = controller.schedule(publisher, plugin,
                        call.params.getValue("idempotencyKey").jsonPrimitive.content,
                        pluginCommandRequestDigest(call.capability, call.method, call.params),
                        call.params.getValue("callbackId").jsonPrimitive.content, interval, duration,
                        version(call.service))
                    supervisor?.ensureService(publisher, plugin)
                    runningOwners = runningOwners + PluginAutomationIdentity(publisher, plugin)
                    taskJson(record)
                } finally { pendingSchedules-- }
            }
            "cancel" -> buildJsonObject {
                put("cancelled", controller.cancel(publisher, plugin, call.params.getValue("taskId").jsonPrimitive.content))
                releaseUnused(publisher, plugin)
            }
            "list" -> buildJsonObject {
                put("tasks", buildJsonArray { controller.list(publisher, plugin).forEach { add(taskJson(it)) } })
            }
            else -> throw PluginRuntimeException("invalid_request", "Unknown background task method")
        }
    }

    private suspend fun startForeground(background: Boolean) {
        if (background || !AndroidSessionKeepAlive.foreground) throw PluginRuntimeException("foreground_required", "Visible activity required")
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
            throw PluginRuntimeException("capability_unavailable", "Enable background task notifications first")
        }
        if (running) return
        if (ready.isCompleted) ready = CompletableDeferred()
        try {
            context.startForegroundService(Intent(context, PluginBackgroundTaskService::class.java))
            withTimeout(5_000) { ready.await() }
        } catch (error: Exception) {
            stopAll()
            throw PluginRuntimeException("capability_unavailable", "Android declined background task service")
        }
    }

    internal fun foregroundReady(token: String) { serviceToken = token; running = true; ready.complete(Unit) }
    internal fun foregroundStopped(token: String) {
        if (serviceToken != token) return
        serviceToken = null
        // A new schedule may already be awaiting a replacement service after cancelling the last task.
        if (!running && pendingSchedules > 0 && controller.active().isEmpty()) return
        running = false
        stopAll()
    }

    fun revoke(publisher: String, plugin: String) {
        epoch.incrementAndGet()
        controller.revoke(publisher, plugin)
        sinks.entries.removeIf { it.value.owner == PluginAutomationIdentity(publisher, plugin) }
        scope.launch { releaseUnused(publisher, plugin) }
    }

    fun stopAll() {
        epoch.incrementAndGet()
        running = false
        ready.cancel()
        val owners = runCatching {
            controller.active().map { PluginAutomationIdentity(it.publisherSubjectId, it.serviceId) }.toSet()
        }.getOrDefault(emptySet()) + runningOwners
        runningOwners = emptySet()
        try { controller.stopAll() } finally {
            context.stopService(Intent(context, PluginBackgroundTaskService::class.java))
            owners.forEach { supervisor?.releaseKeepAliveReason(it.publisherSubjectId, it.serviceId) }
        }
    }

    internal fun owners(): Set<PluginAutomationIdentity> = if (running) {
        controller.active().map { PluginAutomationIdentity(it.publisherSubjectId, it.serviceId) }.toSet()
    } else emptySet()
    internal fun attach(publisher: String, plugin: String, id: String, background: Boolean, send: (PluginRuntimeEvent) -> Unit) {
        sinks[id] = Sink(PluginAutomationIdentity(publisher, plugin), background, sequence.incrementAndGet(), send)
    }
    internal fun detach(runtime: String) { sinks.remove(runtime) }

    private suspend fun dispatch() {
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (!notifications.areNotificationsEnabled() ||
            notifications.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE ||
            notifications.activeNotifications.none { it.id == 24020 }) {
            stopAll()
            return
        }
        val records = controller.active()
        val currentOwners = records.map { PluginAutomationIdentity(it.publisherSubjectId, it.serviceId) }.toSet()
        (runningOwners - currentOwners).forEach { supervisor?.releaseKeepAliveReason(it.publisherSubjectId, it.serviceId) }
        runningOwners = currentOwners
        if (records.isEmpty() && pendingSchedules == 0) {
            running = false
            context.stopService(Intent(context, PluginBackgroundTaskService::class.java))
            return
        }
        for (record in records) {
            val before = epoch.get()
            val service = repository.getService(record.serviceId)
            if (service == null || !service.canRun || service.publisherSubjectId != record.publisherSubjectId ||
                PLUGIN_BACKGROUND_TASK_CAPABILITY !in service.grantedCapabilities ||
                PLUGIN_BACKGROUND_TASK_CAPABILITY !in (service.manifest.requiredCapabilities + service.manifest.optionalCapabilities) ||
                version(service) != record.task!!.version) {
                revoke(record.publisherSubjectId, record.serviceId)
                continue
            }
            if (!running || before != epoch.get()) continue
            supervisor?.ensureService(record.publisherSubjectId, record.serviceId)
            val identity = PluginAutomationIdentity(record.publisherSubjectId, record.serviceId)
            val sink = sinks.values.filter { it.owner == identity }
                .sortedWith(compareBy<Sink> { it.background }.thenByDescending { it.order }).firstOrNull() ?: continue
            if (controller.claim(record)) sink.send(PluginRuntimeEvent(PLUGIN_BACKGROUND_TASK_CAPABILITY, "tick", data = buildJsonObject {
                put("taskId", record.subscriptionId); put("callbackId", record.task.callbackId)
                put("scheduledAtMs", System.currentTimeMillis()); put("expiresAtMs", record.task.expiresAtMs)
            }))
        }
    }

    private fun releaseUnused(publisher: String, plugin: String) {
        if (controller.list(publisher, plugin).isEmpty()) supervisor?.releaseKeepAliveReason(publisher, plugin)
        if (controller.active().isEmpty() && pendingSchedules == 0) {
            running = false
            context.stopService(Intent(context, PluginBackgroundTaskService::class.java))
        }
    }

    private fun authorized(current: ThirdPartyService?, previous: ThirdPartyService) =
        current != null && current.canRun && current.publisherSubjectId == previous.publisherSubjectId &&
            version(current) == version(previous) && PLUGIN_BACKGROUND_TASK_CAPABILITY in current.grantedCapabilities &&
            PLUGIN_BACKGROUND_TASK_CAPABILITY in (current.manifest.requiredCapabilities + current.manifest.optionalCapabilities)

    private fun version(service: ThirdPartyService) = "${service.commitSha}:${service.updatedAt}"
}

internal fun taskJson(record: PluginAutomationSubscriptionRecord) = buildJsonObject {
    put("taskId", record.subscriptionId); put("callbackId", record.task!!.callbackId)
    put("intervalMs", record.task.intervalMs); put("expiresAtMs", record.task.expiresAtMs)
}

private const val CHANNEL = "plugin_background_tasks"

/** A dedicated dataSync service owns notification and platform timeout/stop cleanup. */
class PluginBackgroundTaskService : Service() {
    private val provider get() = (application as BjtuMisApplication).container.pluginBackgroundTaskProvider
    private val token = UUID.randomUUID().toString()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "插件定时任务", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getBroadcast(this, 0, Intent(this, StopPluginAutomationReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        try {
            startForeground(24020, Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.loading_mascot)
                .setContentTitle("插件定时任务运行中").setContentText("每项最长 60 分钟；系统限制可能延迟回调")
                .setContentIntent(open).setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "全部停止", stop).build())
            provider.foregroundReady(token)
        } catch (_: Exception) { provider.stopAll(); stopSelf() }
        return START_NOT_STICKY
    }
    override fun onDestroy() { provider.foregroundStopped(token); super.onDestroy() }
    override fun onTimeout(startId: Int, fgsType: Int) { provider.foregroundStopped(token); stopSelf() }
    override fun onTaskRemoved(rootIntent: Intent?) { provider.foregroundStopped(token); stopSelf() }
    override fun onBind(intent: Intent?): IBinder? = null
}
