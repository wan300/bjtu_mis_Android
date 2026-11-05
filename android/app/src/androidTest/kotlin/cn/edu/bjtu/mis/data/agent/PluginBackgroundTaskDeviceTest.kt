package cn.edu.bjtu.mis.data.agent

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bjtu.mis.BjtuMisApplication
import cn.edu.bjtu.mis.MainActivity
import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.thirdparty.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PluginBackgroundTaskDeviceTest {
    @Test fun nativeTicksPreferForegroundAndCancelRevokeAndNotificationStopAreDurable() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".debug"))
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val container = (context.applicationContext as BjtuMisApplication).container
        val repository = container.thirdPartyServiceRepository
        val provider = container.pluginBackgroundTaskProvider
        val publisher = "qa:background"
        val id = "qa.task.${UUID.randomUUID().toString().replace("-", "")}"
        val base = File(context.filesDir, "third-party-services/installed").canonicalFile
        val root = File(base, id).canonicalFile
        check(root.path.startsWith(base.path + File.separator))
        root.mkdirs()
        File(root, "index.html").writeText("<!doctype html><title>Synthetic timer test</title>")
        val manifest = ThirdPartyServiceManifest(schemaVersion = 3, id = id, name = "Synthetic timer test", version = "1.0.0",
            entrypoint = "index.html", dataSchemaVersion = 1, capabilities = ThirdPartyCapabilityDeclaration(
                required = listOf("runtime.lifecycle@1"), optional = listOf(PLUGIN_BACKGROUND_TASK_CAPABILITY, "android.credentials.import@1", "android.network.status@1")))
        container.database.thirdPartyServiceDao().saveService(ThirdPartyServiceEntity(
            serviceId = id, name = manifest.name, description = "Synthetic offline fixture", version = "1.0.0", author = "QA",
            sourceUrl = "", githubOwner = "", githubRepo = "", defaultBranch = "", commitSha = "qa", packageDigestSha256 = "qa",
            manifestJson = AppJson.encodeToString(manifest), grantedPermissionsJson = "[]",
            grantedCapabilitiesJson = AppJson.encodeToString(listOf("runtime.lifecycle@1", PLUGIN_BACKGROUND_TASK_CAPABILITY, "android.credentials.import@1", "android.network.status@1")),
            allowedOriginsJson = "[]", publisherSubjectId = publisher, dataSchemaVersion = 1, runtimeProfile = "contract_v1",
            runtimeFloor = 4, compatibilityState = "contract_v1", installDir = root.path, entrypoint = "index.html", icon = "",
            enabled = true, needsReview = false, installedAt = "2026-11-05T00:00:00Z", updatedAt = "2026-11-05T00:00:00Z"))
        val foreground = AtomicInteger(); val background = AtomicInteger()
        suspend fun invoke(method: String, params: JsonObject = buildJsonObject {}, isBackground: Boolean = false) =
            container.thirdPartyServiceApiRegistry.invoke(repository.getService(id)!!, PLUGIN_BACKGROUND_TASK_CAPABILITY,
                method, params, confirmer = ThirdPartySensitiveActionConfirmer { _, _ -> fail("Persistent tasks must not reconfirm"); false },
                currentPageUrl = ThirdPartyServiceSandbox.entrypointUrlFor(repository.getService(id)!!),
                backgroundRuntime = isBackground)
        fun schedule(key: String) = buildJsonObject {
            put("idempotencyKey", key); put("callbackId", "sync"); put("intervalMs", 1000); put("maxDurationMs", 60_000)
        }
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
                delay(500)
                val service = repository.getService(id)!!
                val manager = context.getSystemService(NotificationManager::class.java)
                var confirmations = 0
                val deniedImport = container.thirdPartyServiceApiRegistry.invoke(service, "android.credentials.import@1", "import",
                    buildJsonObject { put("packageName", "com.example.fixture"); put("credentials", buildJsonObject { put("identity", "fixture-user") }) },
                    confirmer = ThirdPartySensitiveActionConfirmer { _, _ ->
                        confirmations++
                        withTimeout(5_000) { while (manager.activeNotifications.none { n -> n.notification.channelId == "plugin_credential_import" }) delay(50) }
                        false
                    }, currentPageUrl = ThirdPartyServiceSandbox.entrypointUrlFor(service))
                assertEquals("user_cancelled", deniedImport["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
                assertEquals(1, confirmations)
                withTimeout(5_000) { while (manager.activeNotifications.any { n -> n.notification.channelId == "plugin_credential_import" }) delay(50) }
                val denied = invoke("schedule", schedule("background"), true)
                assertEquals("foreground_required", denied["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
                val receipt = invoke("schedule", schedule("first"))
                assertEquals("true", receipt["ok"]!!.jsonPrimitive.content)
                provider.attach(publisher, id, "test-foreground", false) { foreground.incrementAndGet() }
                provider.attach(publisher, id, "test-background", true) { background.incrementAndGet() }
                withTimeout(10_000) { while (foreground.get() == 0) delay(100) }
                assertEquals(0, background.get())
                // The real supervisor may have attached its background WebView during the first tick.
                // Make this observation sink the newest background runtime before testing fallback.
                provider.attach(publisher, id, "test-background", true) { background.incrementAndGet() }
                provider.detach("test-foreground")
                withTimeout(10_000) { while (background.get() == 0) delay(100) }
                val taskId = receipt["result"]!!.jsonObject["result"]!!.jsonObject["taskId"]!!.jsonPrimitive.content
                invoke("cancel", buildJsonObject { put("idempotencyKey", "cancel"); put("taskId", taskId) }, true)
                assertTrue(invoke("list")["result"]!!.jsonObject["tasks"]!!.jsonArray.isEmpty())
                assertEquals(receipt["result"], invoke("schedule", schedule("first"))["result"])
                assertTrue(invoke("list")["result"]!!.jsonObject["tasks"]!!.jsonArray.isEmpty())
                val second = invoke("schedule", schedule("second"))
                assertEquals(second.toString(), "true", second["ok"]!!.jsonPrimitive.content)
                repository.grantCapabilities(id, setOf("runtime.lifecycle@1"))
                assertFalse(PluginBackgroundTasks.owners().any { owner -> owner.serviceId == id })
                withTimeout(10_000) { while (manager.activeNotifications.any { n -> n.id == 24020 }) delay(100) }
            }
        } finally {
            provider.detach("test-foreground"); provider.detach("test-background")
            provider.revoke(publisher, id)
            repository.deleteService(id)
            if (root.exists()) root.deleteRecursively()
        }
    }
}
