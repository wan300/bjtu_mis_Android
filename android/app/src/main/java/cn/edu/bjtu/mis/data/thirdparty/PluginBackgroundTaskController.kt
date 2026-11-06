package cn.edu.bjtu.mis.data.thirdparty

import kotlinx.serialization.Serializable
import java.security.MessageDigest

const val PLUGIN_BACKGROUND_TASK_CAPABILITY = "android.background.task@1"

@Serializable
data class PluginBackgroundTaskRecord(
    val callbackId: String,
    val intervalMs: Long,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val createdElapsedMs: Long,
    val expiresElapsedMs: Long,
    val nextElapsedMs: Long,
    val bootId: Int,
    val version: String,
    val requestDigest: String,
    val active: Boolean = true,
)

/** Native time is authoritative; persisted wall time is only a second, fail-closed bound. */
class PluginBackgroundTaskController(
    private val store: PluginAutomationStore,
    private val now: () -> Long,
    private val elapsed: () -> Long,
    private val bootId: () -> Int,
) {
    @Synchronized
    fun schedule(publisher: String, plugin: String, key: String, digest: String, callback: String,
        intervalMs: Long, durationMs: Long, version: String): PluginAutomationSubscriptionRecord {
        require(key.isNotBlank() && key.length <= 128 && callback.isNotBlank() && callback.length <= 128)
        require(intervalMs in 1_000..3_600_000 && durationMs in intervalMs..3_600_000)
        require(bootId() >= 0) { "Boot identity unavailable" }
        val id = "task-" + MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        active() // Persist expiry/clock/boot invalidation before considering any new request.
        records(publisher, plugin).filter { now() - it.task!!.createdAtMs > 7L * 24 * 60 * 60 * 1000 }.forEach {
            store.remove(publisher, plugin, it.subscriptionId)
        }
        val records = records(publisher, plugin)
        records.firstOrNull { it.subscriptionId == id }?.let {
            if (it.task!!.requestDigest != digest) throw PluginIdempotencyConflict()
            // Never reactivate, extend or reschedule a replay, including after cancel or expiry.
            return it
        }
        if (records.count(::isActive) >= 4 || records.size >= 1024) {
            throw PluginRuntimeException("quota_exceeded", "Background task quota exceeded")
        }
        val wall = now(); val uptime = elapsed()
        val record = PluginAutomationSubscriptionRecord(
            subscriptionId = id, publisherSubjectId = publisher, serviceId = plugin,
            capability = PLUGIN_BACKGROUND_TASK_CAPABILITY,
            task = PluginBackgroundTaskRecord(callback, intervalMs, wall, wall + durationMs,
                uptime, uptime + durationMs, uptime + intervalMs, bootId(), version, digest),
        )
        store.save(record)
        return record
    }

    @Synchronized fun active(): List<PluginAutomationSubscriptionRecord> {
        val records = store.list().filter { it.capability == PLUGIN_BACKGROUND_TASK_CAPABILITY }
        records.filter { it.task?.active == true && !isActive(it) }.forEach {
            store.save(it.copy(task = it.task!!.copy(active = false)))
        }
        return records.filter(::isActive)
    }

    @Synchronized fun list(publisher: String, plugin: String): List<PluginAutomationSubscriptionRecord> =
        active().filter { it.publisherSubjectId == publisher && it.serviceId == plugin }

    @Synchronized fun cancel(publisher: String, plugin: String, id: String): Boolean {
        val record = records(publisher, plugin).firstOrNull { it.subscriptionId == id } ?: return false
        val active = isActive(record)
        if (record.task!!.active) store.save(record.copy(task = record.task.copy(active = false)))
        return active
    }

    /** Claim before dispatch; recovery skips missed ticks rather than replaying a burst. */
    @Synchronized fun claim(record: PluginAutomationSubscriptionRecord): Boolean {
        val current = records(record.publisherSubjectId, record.serviceId)
            .firstOrNull { it.subscriptionId == record.subscriptionId } ?: return false
        if (!isActive(current) || current.task != record.task || elapsed() < current.task!!.nextElapsedMs) return false
        store.save(current.copy(task = current.task.copy(nextElapsedMs = elapsed() + current.task.intervalMs)))
        return true
    }

    @Synchronized fun revoke(publisher: String, plugin: String) {
        records(publisher, plugin).filter { it.task?.active == true }.forEach {
            store.save(it.copy(task = it.task!!.copy(active = false)))
        }
    }

    @Synchronized fun stopAll() {
        store.list().filter { it.capability == PLUGIN_BACKGROUND_TASK_CAPABILITY }.forEach {
            revoke(it.publisherSubjectId, it.serviceId)
        }
    }

    private fun records(publisher: String, plugin: String) = store.list().filter {
        it.publisherSubjectId == publisher && it.serviceId == plugin && it.capability == PLUGIN_BACKGROUND_TASK_CAPABILITY
    }

    private fun isActive(record: PluginAutomationSubscriptionRecord): Boolean {
        val task = record.task ?: return false
        return task.active && task.bootId >= 0 && task.bootId == bootId() && now() in task.createdAtMs until task.expiresAtMs &&
            elapsed() in task.createdElapsedMs until task.expiresElapsedMs
    }
}
