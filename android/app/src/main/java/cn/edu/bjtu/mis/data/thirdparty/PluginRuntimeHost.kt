package cn.edu.bjtu.mis.data.thirdparty

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.CoroutineScope
import java.io.File

class PluginRuntimeHost(
    private val service: ThirdPartyService,
    private val apiRegistry: ThirdPartyServiceApiRegistry,
    private val resourceStore: ThirdPartyResourceStore,
    private val kvStore: ThirdPartyKvStore?,
    private val confirmer: ThirdPartySensitiveActionConfirmer,
    private val scope: CoroutineScope,
    private val openExternal: (String) -> Unit,
    private val onCloseService: () -> Unit,
    private val onMainPageReady: (WebView) -> Unit = {},
    private val backgroundRuntime: Boolean = false,
    val diagnostics: PluginDiagnostics = PluginDiagnostics(service.serviceId),
) {
    private var attachedWebView: WebView? = null
    private var transport: BridgeTransport? = null
    private var lifecycle: PluginLifecycleDispatcher? = null
    private var developmentRuntime: PluginDevelopmentRuntime? = null

    fun attach(webView: WebView): Boolean {
        attachedWebView = webView
        PluginWebViewPolicy.configure(webView)
        val runtimeEnvironment = PluginWebViewPolicy.runtimeEnvironment()
        if (!service.canRun) {
            renderError(webView, "插件未完成 contract_v1 授权，只能进入数据救援。")
            return false
        }
        if (!runtimeEnvironment.secureRuntimeAvailable) {
            renderError(
                webView,
                "系统 WebView 缺少 DOCUMENT_START_SCRIPT 或 WEB_MESSAGE_LISTENER；运行时已安全关闭。",
            )
            return false
        }
        val unavailableRequired =
            PluginWebViewPolicy.unavailableRequiredCapabilities(service, runtimeEnvironment)
        if (unavailableRequired.isNotEmpty()) {
            renderError(
                webView,
                "系统 WebView 缺少 required capability 支持：${unavailableRequired.joinToString()}",
            )
            return false
        }

        val localOrigin = ThirdPartyServiceSandbox.originFor(
            service.serviceId,
            service.publisherSubjectId,
        )
        val development = PluginDevelopmentRuntimeLoader.create(webView.context, service)
        developmentRuntime = development
        development?.attach(webView, localOrigin)
        val navigation = PluginNavigationController(service, openExternal)
        val resourceServer = PluginResourceServer(service, resourceStore, development)
        val bridge = BridgeTransport(
            service = service,
            apiRegistry = apiRegistry,
            confirmer = confirmer,
            scope = scope,
            navigationController = navigation,
            closePlugin = {
                webView.postDelayed(onCloseService, 50L)
            },
            diagnostics = diagnostics,
            binaryDirectory = File(
                webView.context.cacheDir,
                "plugin-bridge/${ThirdPartyServiceSandbox.hostFor(service.serviceId, service.publisherSubjectId)}",
            ),
            runtimeEnvironment = runtimeEnvironment,
            backgroundRuntime = backgroundRuntime,
        )
        transport = bridge
        AndroidAccessibilityController.attachRuntime(
            publisherSubjectId = service.publisherSubjectId,
            serviceId = service.serviceId,
            runtimeId = bridge.runtimeId,
            backgroundRuntime = backgroundRuntime,
            eventSink = bridge::sendEvent,
            grantedCapabilities = service.grantedCapabilities,
        )
        PluginBackgroundTasks.attach(
            service.publisherSubjectId, service.serviceId, bridge.runtimeId, backgroundRuntime,
            service.grantedCapabilities, bridge::sendEvent,
        )
        PluginSessionKeepAlive.attach(
            service.publisherSubjectId, service.serviceId, bridge.runtimeId, backgroundRuntime,
            service.grantedCapabilities, bridge::sendEvent,
        )
        AndroidNativeEventController.attachRuntime(
            publisherSubjectId = service.publisherSubjectId,
            serviceId = service.serviceId,
            runtimeId = bridge.runtimeId,
            backgroundRuntime = backgroundRuntime,
            eventSink = bridge::sendEvent,
            grantedCapabilities = service.grantedCapabilities,
        )
        lifecycle = PluginLifecycleDispatcher(service, bridge, kvStore, scope).also { it.start() }
        webView.webViewClient = PluginRuntimeWebViewClient(
            service,
            navigation,
            resourceServer,
            onMainPageReady,
        )
        WebViewCompat.addDocumentStartJavaScript(
            webView,
            PluginWebViewPolicy.managedStorageGuardScript() +
                "\n" +
                BridgeTransport.documentStartScript(
                    runtimeEnvironment.webMessageArrayBufferSupported,
                ),
            setOf(localOrigin),
        )
        WebViewCompat.addWebMessageListener(
            webView,
            BridgeTransport.OBJECT_NAME,
            setOf(localOrigin),
            object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(
                    view: WebView,
                    message: androidx.webkit.WebMessageCompat,
                    sourceOrigin: android.net.Uri,
                    isMainFrame: Boolean,
                    replyProxy: androidx.webkit.JavaScriptReplyProxy,
                ) {
                    bridge.onMessage(view, message, sourceOrigin, isMainFrame, replyProxy)
                }
            },
        )
        webView.loadUrl(ThirdPartyServiceSandbox.entrypointUrlFor(service))
        return true
    }

    fun lifecycleDispatcher(): PluginLifecycleDispatcher? = lifecycle

    fun close() {
        lifecycle?.close()
        lifecycle = null
        transport?.close()
        transport = null
        attachedWebView?.let { view ->
            developmentRuntime?.close(view)
            runCatching {
                WebViewCompat.removeWebMessageListener(view, BridgeTransport.OBJECT_NAME)
            }
        }
        developmentRuntime = null
        attachedWebView = null
    }

    private fun renderError(webView: WebView, message: String) {
        diagnostics.record("error", "runtime_unavailable", code = "capability_unavailable")
        webView.loadData(
            "<html><body><h2>插件运行时不可用</h2><p>${escapeHtml(message)}</p></body></html>",
            "text/html",
            "UTF-8",
        )
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}

private class PluginRuntimeWebViewClient(
    private val service: ThirdPartyService,
    private val navigation: PluginNavigationController,
    private val resources: PluginResourceServer,
    private val onMainPageReady: (WebView) -> Unit,
) : WebViewClient() {
    override fun onPageFinished(view: WebView, url: String?) {
        if (
            ThirdPartyServiceSandbox.isServiceSandboxUrl(
                url,
                service.serviceId,
                service.publisherSubjectId,
            )
        ) {
            onMainPageReady(view)
        }
    }

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean = navigation.shouldOverride(request)

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? = resources.intercept(request)
}
