package cn.edu.bjtu.mis.data.thirdparty

import org.junit.Assert.*
import org.junit.Test

class PluginRuntimeLaunchGateTest {
    private fun identity(id: Int, publisher: String = "owner") = PluginAutomationIdentity(publisher, "plugin.$id")

    @Test fun queuedStartCannotRunAfterStopOrRevocation() {
        val gate = PluginRuntimeLaunchGate()
        val token = gate.token()
        gate.stop(identity(1))
        assertFalse(gate.start(token, identity(1)) { fail("stale callback"); true })
        assertFalse(gate.start(gate.token(), identity(1)) { true })
        gate.allow(identity(1))
        assertTrue(gate.start(gate.token(), identity(1)) { true })
    }

    @Test fun filtersAndFailedEntriesDoNotConsumeCapacityAndIdentityIncludesPublisher() {
        val gate = PluginRuntimeLaunchGate()
        val token = gate.token()
        repeat(6) { assertFalse(gate.start(token, identity(it)) { false }) }
        assertTrue(gate.start(token, identity(0)) { true })
        assertFalse(gate.start(token, identity(0)) { fail("duplicate"); true })
        assertTrue(gate.start(token, identity(0, "other")) { true })
        assertTrue(gate.start(token, identity(1)) { true })
        assertTrue(gate.start(token, identity(2)) { true })
        assertFalse(gate.start(token, identity(3)) { fail("over limit"); true })
        gate.release(identity(1))
        assertTrue(gate.start(token, identity(3)) { true })
    }

    @Test fun stopAllInvalidatesInFlightCreation() {
        val gate = PluginRuntimeLaunchGate()
        val token = gate.token()
        gate.stopAll()
        assertFalse(gate.start(token, identity(1)) { true })
    }
}
