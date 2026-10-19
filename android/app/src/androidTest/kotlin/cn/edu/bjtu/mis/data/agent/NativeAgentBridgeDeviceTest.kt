package cn.edu.bjtu.mis.data.agent

import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bjtu.mis.BjtuMisApplication
import cn.edu.bjtu.mis.MainActivity
import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.security.SecureCookieStore
import cn.edu.bjtu.mis.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises the production Capacitor bridge and its real visible Android confirmation dialogs. */
@RunWith(AndroidJUnit4::class)
class NativeAgentBridgeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container get() = (context.applicationContext as BjtuMisApplication).container

    @Test
    fun nativeDialogDenialAndReplayProtectTheActualRoomTodo() = runBlocking {
        prepareOfflineUi()
        agentActivity().use { scenario ->
            val webView = awaitAgent(scenario)
            val metadata = evaluate(webView, "JSON.stringify(await Capacitor.Plugins.NativeAgentTools.listTools())", async = true)
            assertTrue(metadata.contains("agent_todo_create"))
            assertTrue(metadata.contains("agent_reminder_set"))
            val originalIds = container.moduleRepository.userTodos().map { it.id }.toSet()
            val args = buildJsonObject { put("title", "QA 原生桥待办"); put("date", LocalDate.now().toString()) }
            val denied = command("agent_todo_create", args)
            begin(webView, denied)
            clickNativeButton("取消")
            assertEquals("user_denied", awaitResult(webView)["output"]!!.jsonObject["error"]!!.jsonPrimitive.content)
            assertEquals(originalIds, container.moduleRepository.userTodos().map { it.id }.toSet())
            val accepted = command("agent_todo_create", args)
            begin(webView, accepted)
            clickNativeButton("允许本次")
            val receipt = awaitResult(webView)
            val id = receipt["output"]!!.jsonObject["todo"]!!.jsonObject["id"]!!.jsonPrimitive.long
            try {
                assertEquals("QA 原生桥待办", container.moduleRepository.userTodos().first { it.id == id }.title)
                begin(webView, accepted)
                assertEquals(receipt, awaitResult(webView))
                assertEquals(1, container.moduleRepository.userTodos().count { it.id !in originalIds })
            } finally { container.moduleRepository.deleteUserTodo(id) }
        }
    }

    @Test
    fun confirmedNavigationOpensNativeCalendarAndBackRestoresAgent() = runBlocking {
        prepareOfflineUi()
        agentActivity().use { scenario ->
            val webView = awaitAgent(scenario)
            begin(webView, command("agent_app_open_page", buildJsonObject { put("page", "calendar") }))
            clickNativeButton("允许本次")
            val result = awaitResult(webView)
            assertEquals("queued", result["output"]!!.jsonObject["status"]!!.jsonPrimitive.content)
            awaitCondition("WebView must hide after confirmed navigation") { !onMain { webView.isShown } }
            // Keep the production frame clock: a Compose test rule stalls this bridge's
            // coroutine-driven Activity setup until a Compose synchronization call occurs.
            awaitCondition("Native calendar title and add-todo action must be visible") {
                val visible = windowTexts(instrumentation.uiAutomation.rootInActiveWindow)
                "学年日历" in visible && "新增待办" in visible
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitCondition("Back must restore the Agent WebView") { onMain { webView.isShown } }
            assertTrue(evaluate(webView, "location.origin").contains("localhost"))
        }
    }

    private suspend fun prepareOfflineUi() {
        assertTrue(context.packageName.endsWith(".debug"))
        SecureCookieStore(context).save("[]")
        container.syncRepository.saveSnapshot(ModuleKeys.Profile, ModuleEnvelope(module = ModuleKeys.Profile,
            sourceSystem = "QA_SYNTHETIC_OFFLINE", coverage = CoverageLevel.Provisional,
            data = StudentProfileData(name = "QA 合成数据（离线验收）")))
        container.syncRepository.saveSnapshot(ModuleKeys.Calendar, ModuleEnvelope(module = ModuleKeys.Calendar,
            sourceSystem = "QA_SYNTHETIC_OFFLINE", coverage = CoverageLevel.Provisional,
            sourceParams = buildJsonObject { put("month", LocalDate.now().toString().take(7)) },
            data = CalendarData(month = LocalDate.now().toString().take(7))))
    }

    private fun agentActivity(): ActivityScenario<MainActivity> = ActivityScenario.launch(
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_ROUTE, ModuleKeys.OpenWebUiAgent)
    )

    private suspend fun awaitAgent(scenario: ActivityScenario<MainActivity>): WebView = withTimeout(40_000) {
        while (true) {
            var view: WebView? = null
            scenario.onActivity { activity ->
                view = activity.findViewById(com.getcapacitor.android.R.id.webview)
            }
            val candidate = view
            if (candidate != null && onMain { candidate.isShown } &&
                evaluate(candidate, "typeof Capacitor !== 'undefined' && !!Capacitor.Plugins.NativeAgentTools").contains("true")) {
                return@withTimeout candidate
            }
            // A stale cached fixture has no credentials; dismiss only the explicit retry dialog.
            instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("稍后处理")
                ?.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(100)
        }
        @Suppress("UNREACHABLE_CODE") error("Agent unavailable")
    }

    private fun command(name: String, arguments: JsonObject) = buildJsonObject {
        put("workspaceId", ""); put("toolName", name); put("requestId", UUID.randomUUID().toString()); put("arguments", arguments)
    }

    private fun begin(webView: WebView, command: JsonObject) {
        evaluate(webView, "window.__qaResult=null; Capacitor.Plugins.NativeAgentTools.executeTool($command).then(r=>window.__qaResult=r).catch(e=>window.__qaResult={error:String(e)}); true")
    }

    private suspend fun awaitResult(webView: WebView): JsonObject = withTimeout(20_000) {
        while (true) {
            val encoded = AppJson.parseToJsonElement(evaluate(webView, "JSON.stringify(window.__qaResult)"))
            val json = (encoded as? JsonPrimitive)?.contentOrNull
            if (json != null && json != "null") return@withTimeout AppJson.parseToJsonElement(json).jsonObject
            delay(100)
        }
        @Suppress("UNREACHABLE_CODE") error("Tool did not finish")
    }

    private suspend fun clickNativeButton(label: String) = withTimeout(15_000) {
        while (true) {
            val button = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)
                ?.firstOrNull { it.text?.toString() == label && it.isClickable }
            if (button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return@withTimeout
            delay(100)
        }
    }

    private suspend fun awaitCondition(message: String, condition: () -> Boolean) {
        val passed = withTimeoutOrNull(15_000) {
            while (!condition()) delay(100)
            true
        } == true
        val root = instrumentation.uiAutomation.rootInActiveWindow
        assertTrue("$message; foreground=${root?.packageName}; visible=${if (root?.packageName == context.packageName) windowTexts(root) else emptyList<String>()}", passed)
    }

    private fun windowTexts(node: AccessibilityNodeInfo?): List<String> = if (node == null) emptyList() else
        listOfNotNull(node.text?.toString(), node.contentDescription?.toString()) +
            (0 until node.childCount).flatMap { windowTexts(node.getChild(it)) }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }

    private fun evaluate(webView: WebView, script: String, async: Boolean = false): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String>()
        val expression = if (async) "(async()=>{window.__qaMetadata=$script;})()" else script
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(expression) { result.set(it); latch.countDown() }
        }
        check(latch.await(10, TimeUnit.SECONDS)) { "WebView evaluation timed out" }
        if (async) {
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                val metadata = evaluate(webView, "window.__qaMetadata || null")
                if (metadata != "null") return metadata
                Thread.sleep(50)
            }
            error("Native metadata unavailable")
        }
        return result.get()
    }
}
