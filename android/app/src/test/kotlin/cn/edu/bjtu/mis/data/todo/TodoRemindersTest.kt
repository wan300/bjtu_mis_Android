package cn.edu.bjtu.mis.data.todo

import cn.edu.bjtu.mis.model.UserTodoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class TodoRemindersTest {
    private val initial = UserTodoItem(1, "Example", "2026-10-13", createdAt = "v1", updatedAt = "v1")

    @Test fun replacementSurvivesRestartAndOldWorkerCannotSend() = runBlocking {
        val f = Fixture()
        val old = f.service().set(1, "v1", 2000)
        val replacement = f.service().set(1, "v1", 3000)
        f.now = 3000
        val restarted = f.service()
        assertEquals("stale", restarted.deliver(1, old.token))
        assertEquals("delivered", restarted.deliver(1, replacement.token))
        assertEquals("stale", f.service().deliver(1, replacement.token))
        assertEquals(1, f.scheduler.sent)
    }

    @Test fun cancellationRevokesTokenEvenWhenWorkManagerFails() = runBlocking {
        val f = Fixture()
        val reminder = f.service().set(1, "v1", 2000)
        f.scheduler.failCancel = true
        try { f.service().cancel(1); fail() } catch (_: IllegalStateException) { }
        assertTrue(f.store.read().isEmpty())
        f.now = 2000
        assertEquals("cancelled", f.service().deliver(1, reminder.token))
        assertEquals(0, f.scheduler.sent)
    }

    @Test fun failedSchedulingIsVisibleAndNeverDelivers() = runBlocking {
        val f = Fixture()
        f.scheduler.failSchedule = true
        expectCode("reminder_schedule_failed") { f.service().set(1, "v1", 2000) }
        val failed = f.store.read().getValue(1)
        assertEquals("failed", failed.state)
        f.now = 2000
        assertEquals("stale", f.service().deliver(1, failed.token))
        assertEquals(0, f.scheduler.sent)
    }

    @Test fun completedDeletedOrEditedTodosNeverNotify() = runBlocking {
        for (replacement in listOf(null, initial.copy(done = true), initial.copy(updatedAt = "v2"))) {
            val f = Fixture()
            val reminder = f.service().set(1, "v1", 2000)
            f.todo = replacement
            f.now = 2000
            assertEquals("stale", f.service().deliver(1, reminder.token))
            assertEquals(0, f.scheduler.sent)
            assertTrue(f.store.read().isEmpty())
        }
    }

    @Test fun deniedPermissionDoesNotCreateReminderAndRevocationBlocksDelivery() = runBlocking {
        val f = Fixture()
        f.scheduler.enabled = false
        expectCode("notification_permission_required") { f.service().set(1, "v1", 2000) }
        assertTrue(f.store.read().isEmpty())
        f.scheduler.enabled = true
        val reminder = f.service().set(1, "v1", 2000)
        f.scheduler.enabled = false
        f.now = 2000
        assertEquals("blocked", f.service().deliver(1, reminder.token))
        assertEquals("blocked", f.store.read().getValue(1).state)
        assertEquals(0, f.scheduler.sent)
    }

    @Test fun validatesExistenceCompletionVersionAndTimeBeforeScheduling() = runBlocking {
        val f = Fixture()
        expectCode("todo_not_found") { f.service().set(2, "v1", 2000) }
        expectCode("todo_conflict") { f.service().set(1, "wrong", 2000) }
        expectCode("invalid_reminder_time") { f.service().set(1, "v1", 1000) }
        expectCode("invalid_reminder_time") { f.service().set(1, "v1", 1001 + TodoReminders.MaxDelayMs) }
        f.todo = initial.copy(done = true)
        expectCode("todo_completed") { f.service().set(1, "v1", 2000) }
        assertEquals(0, f.scheduler.scheduled)
    }

    @Test fun earlyDeliveryCanRetryAndExcessivelyLateDeliveryExpires() = runBlocking {
        val f = Fixture()
        val reminder = f.service().set(1, "v1", 2000)
        assertEquals("early", f.service().deliver(1, reminder.token))
        f.now = 2001 + TodoReminders.MaxLatenessMs
        assertEquals("expired", f.service().deliver(1, reminder.token))
        assertEquals(0, f.scheduler.sent)
    }

    @Test fun notifyFailureIsReportedWithoutAutomaticRedelivery() = runBlocking {
        val f = Fixture()
        val reminder = f.service().set(1, "v1", 2000)
        f.scheduler.sendResult = false
        f.now = 2000
        assertEquals("failed", f.service().deliver(1, reminder.token))
        assertEquals("stale", f.service().deliver(1, reminder.token))
        assertEquals(1, f.scheduler.sent)
    }

    @Test fun deliveryWaitsForNativeMutationAndCannotRaceCompletion() = runBlocking {
        val f = Fixture()
        val service = f.service()
        val reminder = service.set(1, "v1", 2000)
        f.now = 2000
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val mutation = launch {
            f.mutex.withLock {
                entered.complete(Unit)
                proceed.await()
                f.todo = initial.copy(done = true, updatedAt = "v2")
            }
        }
        entered.await()
        val delivery = async { service.deliver(1, reminder.token) }
        yield()
        assertFalse(delivery.isCompleted)
        proceed.complete(Unit)
        mutation.join()
        assertEquals("stale", delivery.await())
        assertEquals(0, f.scheduler.sent)
    }

    @Test fun quotaRejectsNewRecordsButAllowsReplacingExistingReminder() = runBlocking {
        val f = Fixture()
        for (id in 2L..257L) f.store.put(TodoReminder(id, "v1", 2000, "old-$id", "scheduled"))
        expectCode("reminder_quota_exceeded") { f.service().set(1, "v1", 2000) }
        f.store.remove(257)
        f.service().set(1, "v1", 2000)
        assertEquals(256, f.store.read().size)
        f.service().set(1, "v1", 3000)
        assertEquals(3000L, f.store.read().getValue(1).triggerAtMs)
    }

    private suspend fun expectCode(code: String, action: suspend () -> Unit) {
        try { action(); fail("Expected $code") } catch (error: TodoReminderException) { assertEquals(code, error.code) }
    }

    private inner class Fixture {
        var now = 1000L
        var todo: UserTodoItem? = initial
        var tokens = 0
        val store = MemoryStore()
        val scheduler = FakeScheduler()
        val mutex = Mutex()
        fun service() = TodoReminders(store, scheduler, { id -> todo?.takeIf { it.id == id } }, { now }, { "token-${++tokens}" }, mutex)
    }
    private class MemoryStore : TodoReminderStore {
        override val records = MutableStateFlow<Map<Long, TodoReminder>>(emptyMap())
        override suspend fun read() = records.value
        override suspend fun put(record: TodoReminder) { records.value = records.value + (record.todoId to record) }
        override suspend fun remove(todoId: Long) { records.value = records.value - todoId }
    }
    private class FakeScheduler : TodoReminderScheduler {
        var enabled = true
        var failSchedule = false
        var failCancel = false
        var sendResult = true
        var sent = 0
        var scheduled = 0
        override fun notificationsEnabled() = enabled
        override suspend fun schedule(record: TodoReminder) { if (failSchedule) error("schedule failed"); scheduled++ }
        override suspend fun cancel(todoId: Long) { if (failCancel) error("cancel failed") }
        override fun notify(todo: UserTodoItem): Boolean { sent++; return sendResult }
    }
}
