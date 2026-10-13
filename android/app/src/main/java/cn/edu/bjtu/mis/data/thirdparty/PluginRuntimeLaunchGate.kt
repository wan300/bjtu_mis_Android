package cn.edu.bjtu.mis.data.thirdparty

/** Cancels queued work on stop/revoke and makes admission plus runtime creation atomic. */
internal class PluginRuntimeLaunchGate(private val limit: Int = 4) {
    private var generation = 0L
    private val stopped = mutableSetOf<PluginAutomationIdentity>()
    private val running = mutableSetOf<PluginAutomationIdentity>()

    @Synchronized fun token(): Long = generation
    @Synchronized fun isCurrent(token: Long): Boolean = token == generation
    @Synchronized fun allow(identity: PluginAutomationIdentity) { stopped.remove(identity) }

    @Synchronized fun stop(identity: PluginAutomationIdentity) {
        generation++
        stopped += identity
        running -= identity
    }

    @Synchronized fun stopAll() {
        generation++
        stopped += running
        running.clear()
    }

    @Synchronized fun release(identity: PluginAutomationIdentity) { running -= identity }

    @Synchronized fun start(token: Long, identity: PluginAutomationIdentity, create: () -> Boolean): Boolean {
        if (token != generation || identity in stopped || identity in running || running.size >= limit) return false
        if (!create()) return false
        running += identity
        return true
    }
}
