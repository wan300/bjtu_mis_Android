package cn.edu.bjtu.mis.data.todo

import cn.edu.bjtu.mis.model.UserTodoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class TodoReminder(
    val todoId: Long,
    val todoUpdatedAt: String,
    val triggerAtMs: Long,
    val token: String,
    val state: String,
)

interface TodoReminderStore {
    val records: Flow<Map<Long, TodoReminder>>
    suspend fun read(): Map<Long, TodoReminder>
    suspend fun put(record: TodoReminder)
    suspend fun remove(todoId: Long)
}

interface TodoReminderScheduler {
    fun notificationsEnabled(): Boolean
    suspend fun schedule(record: TodoReminder)
    suspend fun cancel(todoId: Long)
    fun notify(todo: UserTodoItem): Boolean
}

class TodoReminderException(val code: String, message: String) : IllegalArgumentException(message)

/** Persisted tokens and current Room versions, never stale WorkManager input, authorize delivery. */
class TodoReminders(
    val store: TodoReminderStore,
    private val scheduler: TodoReminderScheduler,
    private val findTodo: suspend (Long) -> UserTodoItem?,
    private val now: () -> Long = System::currentTimeMillis,
    private val newToken: () -> String = { UUID.randomUUID().toString() },
    private val mutex: Mutex = Mutex(),
) {

    fun notificationsEnabled(): Boolean = scheduler.notificationsEnabled()

    suspend fun set(todoId: Long, expectedUpdatedAt: String, triggerAtMs: Long): TodoReminder = mutex.withLock {
        val todo = findTodo(todoId) ?: fail("todo_not_found", "待办不存在")
        if (todo.updatedAt != expectedUpdatedAt) fail("todo_conflict", "待办已修改，请重新查询")
        if (todo.done) fail("todo_completed", "已完成待办不能设置提醒")
        val delay = triggerAtMs - now()
        if (delay <= 0 || delay > MaxDelayMs) fail("invalid_reminder_time", "提醒时间必须在未来 365 天内，并包含时区")
        if (!scheduler.notificationsEnabled()) fail("notification_permission_required", "请在系统设置中允许本应用及待办提醒频道的通知，再重新设置提醒")
        val records = store.read()
        if (todoId !in records && records.size >= 256) fail("reminder_quota_exceeded", "提醒记录已达 256 项，请先取消旧提醒")
        val pending = TodoReminder(todoId, todo.updatedAt, triggerAtMs, newToken(), "pending")
        // Invalidate any previous work before replacing it, including after process death.
        store.put(pending)
        try {
            scheduler.cancel(todoId)
            scheduler.schedule(pending)
            pending.copy(state = "scheduled").also { store.put(it) }
        } catch (error: CancellationException) {
            // pending is intentionally not deliverable; never retry a cancelled command silently.
            throw error
        } catch (_: Exception) {
            store.put(pending.copy(state = "failed"))
            fail("reminder_schedule_failed", "提醒安排失败，请查询状态后重新设置")
        }
    }

    suspend fun cancel(todoId: Long) = mutex.withLock {
        cancelForMutation(todoId)
    }

    /** Repository mutation already owns the shared todo mutex. */
    internal suspend fun cancelForMutation(todoId: Long) {
        store.remove(todoId)
        // Revocation is durable even if WorkManager cancellation fails.
        scheduler.cancel(todoId)
    }

    suspend fun deliver(todoId: Long, token: String): String = mutex.withLock {
        val record = store.read()[todoId] ?: return@withLock "cancelled"
        if (record.token != token || record.state != "scheduled") return@withLock "stale"
        val todo = findTodo(todoId)
        if (todo == null || todo.done || todo.updatedAt != record.todoUpdatedAt) {
            store.remove(todoId)
            return@withLock "stale"
        }
        val lateness = now() - record.triggerAtMs
        if (lateness < 0) return@withLock "early"
        if (lateness > MaxLatenessMs) {
            store.put(record.copy(state = "expired"))
            return@withLock "expired"
        }
        if (!scheduler.notificationsEnabled()) {
            store.put(record.copy(state = "blocked"))
            return@withLock "blocked"
        }
        // Claim first: a restarted worker must not send a second notification.
        store.put(record.copy(state = "delivering"))
        val sent = scheduler.notify(todo)
        val state = if (sent) "delivered" else "failed"
        store.put(record.copy(state = state))
        state
    }

    private fun fail(code: String, message: String): Nothing = throw TodoReminderException(code, message)

    companion object {
        const val MaxDelayMs = 365L * 24 * 60 * 60 * 1000
        const val MaxLatenessMs = 60L * 60 * 1000
    }
}
