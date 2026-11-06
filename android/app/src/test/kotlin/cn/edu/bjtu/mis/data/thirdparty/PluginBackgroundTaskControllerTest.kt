package cn.edu.bjtu.mis.data.thirdparty

import org.junit.Assert.*
import org.junit.Test

class PluginBackgroundTaskControllerTest {
    private val store = TaskTestStore()
    private var wall = 100_000L
    private var uptime = 10_000L
    private var boot = 1
    private fun controller() = PluginBackgroundTaskController(store, { wall }, { uptime }, { boot })
    private fun schedule(key: String = "once", publisher: String = "publisher:1", plugin: String = "test.plugin") =
        controller().schedule(publisher, plugin, key, "digest-$key", "callback", 1_000, 60_000, "v1")

    @Test fun replayAfterCancelOrRestartNeverReactivatesOrExtendsTask() {
        val task = schedule()
        assertTrue(controller().cancel("publisher:1", "test.plugin", task.subscriptionId))
        wall += 10_000; uptime += 10_000
        val replay = schedule()
        assertEquals(task.task!!.expiresAtMs, replay.task!!.expiresAtMs)
        assertFalse(replay.task!!.active)
        assertTrue(controller().active().isEmpty())
        assertThrows(PluginIdempotencyConflict::class.java) {
            controller().schedule("publisher:1", "test.plugin", "once", "different", "callback", 1_000, 60_000, "v1")
        }
    }

    @Test fun publisherPluginIsolationAndFourTaskQuota() {
        repeat(4) { schedule("key-$it") }
        assertThrows(PluginRuntimeException::class.java) { schedule("overflow") }
        assertEquals(4, controller().list("publisher:1", "test.plugin").size)
        val other = schedule(publisher = "publisher:2")
        assertFalse(controller().cancel("publisher:1", "other.plugin", other.subscriptionId))
        controller().revoke("publisher:1", "test.plugin")
        assertEquals(1, controller().active().size)
        controller().stopAll()
        assertTrue(controller().active().isEmpty())
    }

    @Test fun nativeClaimSkipsBacklogAndPersistsBeforeDispatch() {
        val task = schedule()
        assertFalse(controller().claim(task))
        wall += 10_000; uptime += 10_000
        assertTrue(controller().claim(task))
        assertFalse(controller().claim(task))
        assertFalse(controller().claim(controller().active().single()))
        wall += 1_000; uptime += 1_000
        assertTrue(controller().claim(controller().active().single()))
    }

    @Test fun expiryClockRollbackAndRebootPermanentlyInvalidateRecovery() {
        schedule(); wall += 60_000; uptime += 60_000
        assertTrue(controller().active().isEmpty())
        schedule("new"); boot++
        assertTrue(controller().active().isEmpty())
        boot--
        assertTrue(controller().active().isEmpty())
        schedule("clock"); wall--
        assertTrue(controller().active().isEmpty())
        wall++
        assertTrue(controller().active().isEmpty())
    }

    @Test fun rejectsInvalidLifetimeAndMissingBootIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            controller().schedule("publisher:1", "test.plugin", "key", "digest", "callback", 1_000, 3_600_001, "v1")
        }
        boot = -1
        assertThrows(IllegalArgumentException::class.java) { schedule() }
    }
}

private class TaskTestStore : PluginAutomationStore {
    private val records = mutableListOf<PluginAutomationSubscriptionRecord>()
    override fun list() = records.toList()
    override fun save(record: PluginAutomationSubscriptionRecord) {
        remove(record.publisherSubjectId, record.serviceId, record.subscriptionId)
        records += record
    }
    override fun remove(publisherSubjectId: String, serviceId: String, subscriptionId: String) = records.removeAll {
        it.publisherSubjectId == publisherSubjectId && it.serviceId == serviceId && it.subscriptionId == subscriptionId
    }
    override fun removeCapability(publisherSubjectId: String, serviceId: String, capability: String) {
        records.removeAll { it.publisherSubjectId == publisherSubjectId && it.serviceId == serviceId && it.capability == capability }
    }
    override fun removeService(publisherSubjectId: String, serviceId: String) {
        records.removeAll { it.publisherSubjectId == publisherSubjectId && it.serviceId == serviceId }
    }
    override fun clear() { records.clear() }
}
