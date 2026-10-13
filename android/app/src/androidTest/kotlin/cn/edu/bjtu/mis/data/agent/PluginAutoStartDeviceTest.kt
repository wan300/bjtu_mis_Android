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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Synthetic local plugin, actual supervisor/WebView/foreground service, isolated debug data. */
@RunWith(AndroidJUnit4::class)
class PluginAutoStartDeviceTest {
    @Test
    fun optionalGrantStartsVisibleServiceAndRevocationStopsTheRuntime() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(context.packageName.endsWith(".debug"))
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val container = (context.applicationContext as BjtuMisApplication).container
        val repository = container.thirdPartyServiceRepository
        val supervisor = container.pluginAutomationSupervisor
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue("Enable debug notifications before physical-device acceptance", manager.areNotificationsEnabled())
        assertTrue("Secure WebView transport is required", PluginWebViewPolicy.supportsSecureTransport())
        val id = "qa.autostart.${UUID.randomUUID().toString().replace("-", "")}"
        val root = File(context.filesDir, "third-party-services/installed/$id").canonicalFile
        check(root.path.startsWith(File(context.filesDir, "third-party-services/installed").canonicalPath + File.separator))
        root.mkdirs()
        File(root, "index.html").writeText("<!doctype html><title>QA local auto-start</title><p>QA synthetic plugin</p>")
        val manifest = ThirdPartyServiceManifest(schemaVersion = 3, id = id, name = "QA 合成自启插件",
            version = "1.0.0", entrypoint = "index.html", dataSchemaVersion = 1,
            capabilities = ThirdPartyCapabilityDeclaration(required = listOf("runtime.lifecycle@1"),
                optional = listOf("android.app.autoStart@1")))
        container.database.thirdPartyServiceDao().saveService(ThirdPartyServiceEntity(
            serviceId = id, name = manifest.name, description = "QA synthetic offline fixture", version = "1.0.0",
            author = "QA", sourceUrl = "", githubOwner = "", githubRepo = "", defaultBranch = "",
            commitSha = "qa", packageDigestSha256 = "qa", manifestJson = AppJson.encodeToString(manifest),
            grantedPermissionsJson = "[]", grantedCapabilitiesJson = "[\"runtime.lifecycle@1\"]",
            allowedOriginsJson = "[]", publisherSubjectId = "qa:device", dataSchemaVersion = 1,
            runtimeProfile = "contract_v1", runtimeFloor = 4, compatibilityState = "contract_v1",
            installDir = root.path, entrypoint = "index.html", icon = "", enabled = true, needsReview = false,
            installedAt = "2026-10-13T00:00:00Z", updatedAt = "2026-10-13T00:00:00Z"))
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
                delay(500)
                assertFalse(repository.getService(id)!!.grantedCapabilities.contains("android.app.autoStart@1"))
                assertEquals(0, supervisor.activeRuntimeCount())
                repository.setAppAutoStartEnabled(id, true)
                withTimeout(20_000) {
                    while (supervisor.activeRuntimeCount() != 1 ||
                        manager.activeNotifications.none { it.id == 24019 }) delay(100)
                }
                repository.setAppAutoStartEnabled(id, false)
                withTimeout(15_000) {
                    while (supervisor.activeRuntimeCount() != 0 ||
                        manager.activeNotifications.any { it.id == 24019 }) delay(100)
                }
                assertFalse(repository.getService(id)!!.grantedCapabilities.contains("android.app.autoStart@1"))
            }
        } finally {
            instrumentation.runOnMainSync { supervisor.stopService("qa:device", id) }
            repository.deleteService(id)
            // Only this unique synthetic fixture directory, within the verified debug installation root.
            if (root.exists()) root.deleteRecursively()
        }
    }
}
