package cn.edu.bjtu.mis.data.thirdparty

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Owns at most four authorized background runtimes, sharing the foreground page's bridge policy. */
class PluginAutomationSupervisor(
    private val context: Context,
    private val repository: ThirdPartyServiceRepository,
    private val apiRegistry: ThirdPartyServiceApiRegistry,
    private val resourceStore: ThirdPartyResourceStore,
    private val kvStore: ThirdPartyKvStore,
    private val automationStore: PluginAutomationStore,
) {
    private data class ActiveRuntime(val view: WebView, val host: PluginRuntimeHost, val scope: CoroutineScope)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val active = ConcurrentHashMap<PluginAutomationIdentity, ActiveRuntime>()
    private val autoStartOwners = ConcurrentHashMap.newKeySet<PluginAutomationIdentity>()
    private val launchGate = PluginRuntimeLaunchGate()
    private val reconcileMutex = Mutex()
    private val mutableAutoStartStatus = MutableStateFlow("等待打开主应用")
    val autoStartStatus = mutableAutoStartStatus.asStateFlow()
    private var appVisible = false
    private var appSessionOpened = false
    private var foregroundReady = false
    private var foregroundRequested = false
    private var autoStartSuppressed = false

    init {
        repository.onAutoStartChanged = { service ->
            scope.launch {
                val identity = PluginAutomationIdentity(service.publisherSubjectId, service.serviceId)
                stopService(identity)
                if (PluginAppAutoStartPolicy.isEligible(service)) {
                    launchGate.allow(identity)
                    if (appVisible) appSessionOpened = true
                }
                requestAutoStartReconciliation()
            }
        }
        val token = launchGate.token()
        scope.launch { restorePersistentRuntimes(token) }
    }

    /** Called only by the visible main Activity, never by Application or a background worker. */
    fun onAppVisible() {
        appVisible = true
        appSessionOpened = true
        requestAutoStartReconciliation()
    }

    fun onAppHidden() { appVisible = false }

    internal fun onAutoStartForegroundReady() {
        foregroundRequested = false
        if (!appSessionOpened) {
            PluginAutoStartForegroundService.stop(context)
            return
        }
        foregroundReady = true
        requestAutoStartReconciliation()
    }

    internal fun onAutoStartForegroundStopped(message: String = "后台服务已停止，下次打开主应用时重试") {
        foregroundReady = false
        foregroundRequested = false
        appSessionOpened = false
        val owners = autoStartOwners.toList()
        autoStartOwners.clear()
        owners.forEach(::stopService)
        mutableAutoStartStatus.value = message
    }

    fun onAccessibilityConnected() {
        val token = launchGate.token()
        scope.launch { restorePersistentRuntimes(token) }
        requestAutoStartReconciliation()
    }

    fun onAccessibilityDisconnected() {
        scope.launch {
            active.keys.toList().forEach { identity ->
                if (identity !in autoStartOwners && identity !in PluginSessionKeepAlive.owners() &&
                    automationStore.list().none {
                        it.publisherSubjectId == identity.publisherSubjectId && it.serviceId == identity.serviceId &&
                            it.capability != "android.accessibility.events@1"
                    }) releaseRuntime(identity)
            }
        }
    }

    fun ensureService(publisherSubjectId: String, serviceId: String) {
        val identity = PluginAutomationIdentity(publisherSubjectId, serviceId)
        launchGate.allow(identity)
        val token = launchGate.token()
        scope.launch { startSafely(identity, token) }
    }

    fun stopService(publisherSubjectId: String, serviceId: String) =
        stopService(PluginAutomationIdentity(publisherSubjectId, serviceId))

    fun stopAll() {
        launchGate.stopAll()
        appSessionOpened = false
        autoStartSuppressed = true
        autoStartOwners.clear()
        active.keys.toList().forEach(::stopService)
        PluginSessionKeepAlive.stopAll()
        PluginAutoStartForegroundService.stop(context)
        mutableAutoStartStatus.value = "已停止；重新开启开关后可自启"
    }

    /** Releases the stop-all guard after the persisted auto-start grants have been removed. */
    internal fun onAutoStartGrantsCleared() {
        autoStartSuppressed = false
    }

    fun activeRuntimeCount(): Int = active.size

    fun releaseKeepAliveReason(publisher: String, plugin: String, force: Boolean = false) {
        scope.launch {
            val identity = PluginAutomationIdentity(publisher, plugin)
            if (identity !in autoStartOwners && (force || identity !in PluginSessionKeepAlive.owners()) &&
                automationStore.list().none { it.publisherSubjectId == publisher && it.serviceId == plugin }) {
                releaseRuntime(identity)
            }
        }
    }

    private fun stopService(identity: PluginAutomationIdentity) {
        launchGate.stop(identity)
        autoStartOwners.remove(identity)
        releaseRuntime(identity)
    }

    private fun releaseRuntime(identity: PluginAutomationIdentity) {
        launchGate.release(identity)
        val runtime = active.remove(identity) ?: return
        scope.launch {
            runtime.host.close()
            runtime.view.stopLoading()
            runtime.view.destroy()
            runtime.scope.cancel()
        }
    }

    private fun requestAutoStartReconciliation() {
        val token = launchGate.token()
        scope.launch {
            reconcileMutex.withLock {
                if (!launchGate.isCurrent(token)) return@withLock
                if (autoStartSuppressed) return@withLock
                try {
                    val candidates = withContext(Dispatchers.IO) { repository.listServices() }
                        .filter(PluginAppAutoStartPolicy::isEligible)
                    if (!launchGate.isCurrent(token)) return@withLock
                    val eligible = candidates.map { PluginAutomationIdentity(it.publisherSubjectId, it.serviceId) }.toSet()
                    (autoStartOwners - eligible).forEach(::stopService)
                    if (eligible.isEmpty()) {
                        PluginAutoStartForegroundService.stop(context)
                        mutableAutoStartStatus.value = "没有已开启自启的插件"
                        return@withLock
                    }
                    if (!appSessionOpened) return@withLock
                    if (!foregroundReady) {
                        if (appVisible && !foregroundRequested) {
                            val error = PluginAutoStartForegroundService.start(context)
                            foregroundRequested = error == null
                            mutableAutoStartStatus.value = error ?: "正在启动后台服务"
                        }
                        return@withLock
                    }
                    val currentToken = launchGate.token()
                    // Filter first; a broken/ineligible plugin must not consume one of the four slots.
                    eligible.forEach { identity -> startSafely(identity, currentToken, autoStart = true) }
                    mutableAutoStartStatus.value = if (autoStartOwners.isEmpty()) {
                        "插件未能启动或后台运行名额已满（最多 4 个）"
                    } else "自启后台运行中（" + autoStartOwners.size + " 个，后台总上限 4 个）"
                    if (autoStartOwners.isEmpty()) PluginAutoStartForegroundService.stop(context)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    mutableAutoStartStatus.value = "读取自启插件失败，下次打开主应用时重试"
                }
            }
        }
    }

    private suspend fun startSafely(identity: PluginAutomationIdentity, token: Long, autoStart: Boolean = false) {
        try {
            startService(identity, token, autoStart)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Failure is isolated to this plugin; do not record plugin data or prevent other candidates.
        }
    }

    private suspend fun startService(identity: PluginAutomationIdentity, token: Long, autoStart: Boolean) {
        if (!launchGate.isCurrent(token)) return
        val service = withContext(Dispatchers.IO) { repository.getService(identity.serviceId) }
        if (service == null || service.publisherSubjectId != identity.publisherSubjectId) {
            automationStore.removeService(identity.publisherSubjectId, identity.serviceId)
            return
        }
        val declared = service.manifest.requiredCapabilities + service.manifest.optionalCapabilities
        val hasAutoStart = autoStart && foregroundReady && appSessionOpened && PluginAppAutoStartPolicy.isEligible(service)
        val hasKeepAlive = identity in PluginSessionKeepAlive.owners() &&
            "android.session.keepAlive@1" in service.grantedCapabilities && "android.session.keepAlive@1" in declared
        val records = automationStore.list().filter {
            it.publisherSubjectId == identity.publisherSubjectId && it.serviceId == identity.serviceId
        }
        val usable = records.any { it.capability in declared && it.capability in service.grantedCapabilities &&
            (it.capability != "android.accessibility.events@1" || AndroidAccessibilityController.isConnected()) }
        if (!service.canRun) {
            automationStore.removeService(identity.publisherSubjectId, identity.serviceId)
            return
        }
        if (!usable && !hasKeepAlive && !hasAutoStart) return
        if (!launchGate.isCurrent(token)) return
        if (active.containsKey(identity)) {
            if (hasAutoStart) autoStartOwners += identity
            return
        }
        launchGate.start(token, identity) {
            val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            var view: WebView? = null
            var host: PluginRuntimeHost? = null
            try {
                val runtimeView = WebView(context).also { view = it }
                val runtimeHost = PluginRuntimeHost(
                    service = service, apiRegistry = apiRegistry, resourceStore = resourceStore, kvStore = kvStore,
                    confirmer = ThirdPartySensitiveActionConfirmer { _, _ -> false }, scope = runtimeScope,
                    openExternal = {}, onCloseService = { stopService(identity) }, backgroundRuntime = true,
                ).also { host = it }
                if (!runtimeHost.attach(runtimeView)) return@start false
                active[identity] = ActiveRuntime(runtimeView, runtimeHost, runtimeScope)
                if (hasAutoStart) autoStartOwners += identity
                true
            } finally {
                if (!active.containsKey(identity)) {
                    host?.close()
                    view?.destroy()
                    runtimeScope.cancel()
                }
            }
        }
    }

    private suspend fun restorePersistentRuntimes(token: Long) {
        automationStore.list().map { PluginAutomationIdentity(it.publisherSubjectId, it.serviceId) }
            .distinct().forEach { startSafely(it, token) }
    }
}
