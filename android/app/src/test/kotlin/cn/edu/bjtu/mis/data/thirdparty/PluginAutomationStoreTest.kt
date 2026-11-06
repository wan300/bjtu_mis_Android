package cn.edu.bjtu.mis.data.thirdparty

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginAutomationStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun interruptedMutationHidesOldSnapshotAndFreshSaveCannotRestoreIt() {
        val root = temp.newFolder("interrupted")
        val store = FilePluginAutomationStore(root, AutomationTestCipher)
        val old = record("old", "publisher:1", "test.plugin")
        store.save(old)
        val snapshot = root.listFiles()!!.single()
        java.io.File(root, ".${snapshot.name}.pending").writeText("1")
        assertTrue(store.list().isEmpty())
        val next = record("new", "publisher:1", "test.plugin")
        store.save(next)
        assertEquals(listOf(next), store.list())
    }

    @Test fun encryptionFailureCannotLeaveAnActiveSnapshotReadable() {
        val root = temp.newFolder("key-failure")
        var fail = false
        val cipher = object : ThirdPartyKvCipher {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
                check(!fail) { "Synthetic key failure" }
                return AutomationTestCipher.encrypt(plaintext, associatedData)
            }
            override fun decrypt(payload: ByteArray, associatedData: ByteArray) =
                AutomationTestCipher.decrypt(payload, associatedData)
        }
        val store = FilePluginAutomationStore(root, cipher)
        store.save(record("old", "publisher:1", "test.plugin"))
        store.save(record("battery", "publisher:1", "test.plugin").copy(capability = "android.battery.status@1"))
        fail = true
        assertThrows(IllegalStateException::class.java) { store.removeCapability("publisher:1", "test.plugin", "android.accessibility.events@1") }
        fail = false
        assertTrue(store.list().isEmpty())
        store.clear()
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun encryptedSubscriptionsRemainPublisherAndPluginIsolated() {
        val root = temp.newFolder("automation")
        val store = FilePluginAutomationStore(root, AutomationTestCipher)
        val first = record("subscription-a", "github-owner:1", "bjtu.demo")
        val second = record("subscription-b", "github-owner:2", "bjtu.demo")

        store.save(first)
        store.save(second)

        assertEquals(setOf(first, second), store.list().toSet())
        assertEquals(2, root.listFiles().orEmpty().size)
        val persisted = root.listFiles().orEmpty().joinToString { file ->
            file.readBytes().toString(Charsets.ISO_8859_1)
        }
        assertFalse(persisted.contains("github-owner"))
        assertFalse(persisted.contains("subscription-a"))

        store.removeService(first.publisherSubjectId, first.serviceId)
        assertEquals(listOf(second), store.list())
    }

    @Test
    fun subscriptionRemovalAndClearArePersistent() {
        val store = FilePluginAutomationStore(temp.newFolder("clear"), AutomationTestCipher)
        val first = record("subscription-a", "github-owner:1", "bjtu.demo")
        val second = record("subscription-b", "github-owner:1", "bjtu.demo")
        store.save(first)
        store.save(second)

        assertTrue(store.remove(first.publisherSubjectId, first.serviceId, first.subscriptionId))
        assertFalse(store.remove(first.publisherSubjectId, first.serviceId, "missing"))
        assertEquals(listOf(second), store.list())

        store.clear()
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun capabilityRemovalKeepsOtherPersistentSubscriptions() {
        val store = FilePluginAutomationStore(temp.newFolder("capabilities"), AutomationTestCipher)
        val accessibility = record("accessibility", "github-owner:1", "bjtu.demo")
        val battery = accessibility.copy(
            subscriptionId = "battery",
            capability = "android.battery.status@1",
            eventTypes = emptySet(),
            packageNames = emptySet(),
        )
        store.save(accessibility)
        store.save(battery)

        store.removeCapability("github-owner:1", "bjtu.demo", "android.accessibility.events@1")

        assertEquals(listOf(battery), store.list())
    }

    private fun record(
        subscriptionId: String,
        publisherSubjectId: String,
        serviceId: String,
    ) = PluginAutomationSubscriptionRecord(
        subscriptionId = subscriptionId,
        publisherSubjectId = publisherSubjectId,
        serviceId = serviceId,
        eventTypes = setOf("viewClicked"),
        packageNames = setOf("com.example.app"),
        includeSource = true,
    )
}

class AndroidAutomationPolicyTest {
    @Test
    fun fixedWindowLimiterResetsAndRejectsOverflow() {
        val limiter = FixedWindowRateLimiter(limit = 2, windowMs = 1_000L)

        assertTrue(limiter.tryAcquire(10L))
        assertTrue(limiter.tryAcquire(20L))
        assertFalse(limiter.tryAcquire(30L))
        assertTrue(limiter.tryAcquire(1_010L))
    }

    @Test
    fun settingsPolicyRejectsArbitraryIntentsAndInvalidPackages() {
        assertEquals(
            "android.settings.ACCESSIBILITY_SETTINGS",
            AndroidAutomationPolicy.requireSettingsAction("android.settings.ACCESSIBILITY_SETTINGS"),
        )
        assertEquals(
            "cn.edu.bjtu.mis",
            AndroidAutomationPolicy.requirePackageName("cn.edu.bjtu.mis"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            AndroidAutomationPolicy.requireSettingsAction("android.intent.action.VIEW")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AndroidAutomationPolicy.requirePackageName("../mis")
        }
    }

    @Test
    fun sensitiveInputsAreRedactedByType() {
        assertTrue(AndroidAutomationPolicy.isSensitiveInput(true, false, InputType.TYPE_NULL))
        assertTrue(
            AndroidAutomationPolicy.isSensitiveInput(
                false,
                true,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            ),
        )
        assertTrue(
            AndroidAutomationPolicy.isSensitiveInput(
                false,
                true,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
            ),
        )
        assertTrue(
            AndroidAutomationPolicy.isSensitiveInput(
                false,
                true,
                InputType.TYPE_CLASS_PHONE,
            ),
        )
        assertFalse(
            AndroidAutomationPolicy.isSensitiveInput(
                false,
                true,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL,
            ),
        )
    }
}

private object AutomationTestCipher : ThirdPartyKvCipher {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        plaintext.map { (it.toInt() xor MASK).toByte() }.toByteArray()

    override fun decrypt(payload: ByteArray, associatedData: ByteArray): ByteArray =
        encrypt(payload, associatedData)

    private const val MASK = 0x5a
}
