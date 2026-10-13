package cn.edu.bjtu.mis.data.agent.tools

import cn.edu.bjtu.mis.data.repository.TodoMutationException
import cn.edu.bjtu.mis.data.todo.TodoReminder
import cn.edu.bjtu.mis.model.UserTodoDraft
import cn.edu.bjtu.mis.model.UserTodoItem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeTodoAgentToolTest {
    @Test fun createReturnsNativeIdentityAndUpdatePreservesOmittedFields() = runBlocking {
        val f = Fake()
        val created = execute(f, "todo.create", """{"title":" Example ","date":"2026-10-13","note":"Note"}""")
        assertEquals("Example", created["todo"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals(1L, created["todo"]!!.jsonObject["id"]!!.jsonPrimitive.long)
        execute(f, "todo.update", """{"id":1,"expectedUpdatedAt":"v1","done":true}""")
        assertEquals(UserTodoItem(1, "Example", "2026-10-13", "Note", true, "v1", "v2"), f.todos.single())
        assertEquals("todo_conflict", execute(f, "todo.update", """{"id":1,"expectedUpdatedAt":"v1","title":"Stale"}""")["error"]!!.jsonPrimitive.content)
        assertEquals("Example", f.todos.single().title)
    }

    @Test fun rejectsBadDatesLengthsMissingIdsAndEmptyUpdates() = runBlocking {
        val f = Fake()
        for (json in listOf("""{"title":"X","date":"tomorrow"}""", """{"title":" ","date":"2026-10-13"}""",
            buildJsonObject { put("title", "x".repeat(201)); put("date", "2026-10-13") }.toString(),
            buildJsonObject { put("title", "X"); put("date", "2026-10-13"); put("note", "x".repeat(2001)) }.toString())) {
            assertEquals("invalid_arguments", execute(f, "todo.create", json)["error"]!!.jsonPrimitive.content)
        }
        assertTrue(f.todos.isEmpty())
        assertEquals("todo_not_found", execute(f, "todo.update", """{"id":1,"expectedUpdatedAt":"v1","done":true}""")["error"]!!.jsonPrimitive.content)
        f.todos += item(1)
        assertEquals("invalid_arguments", execute(f, "todo.update", """{"id":1,"expectedUpdatedAt":"v1"}""")["error"]!!.jsonPrimitive.content)
    }

    @Test fun listSeparatesNativeTodosFiltersAndTruncatesWithoutExposingTokens() = runBlocking {
        val f = Fake()
        f.todos += listOf(item(1), item(2), item(3).copy(done = true), item(4).copy(date = "2026-10-20"))
        val result = execute(f, "todo.list", """{"startDate":"2026-10-13","endDate":"2026-10-13","limit":1}""")
        assertEquals(2, result["total"]!!.jsonPrimitive.int)
        assertTrue(result["truncated"]!!.jsonPrimitive.boolean)
        assertEquals("native_room", result["source"]!!.jsonPrimitive.content)
        assertFalse(result.toString().contains("private-token"))
        assertEquals(4, execute(f, "todo.list", """{"includeDone":true}""")["total"]!!.jsonPrimitive.int)
    }

    @Test fun reminderRequiresTimeZoneAndReturnsNonExactReceipt() = runBlocking {
        val f = Fake()
        assertEquals("invalid_arguments", execute(f, "reminder.set", """{"id":1,"expectedUpdatedAt":"v1","remindAt":"2026-10-13T09:00:00"}""")["error"]!!.jsonPrimitive.content)
        val receipt = execute(f, "reminder.set", """{"id":1,"expectedUpdatedAt":"v1","remindAt":"2026-10-13T09:00:00+08:00"}""")
        assertFalse(receipt["exact"]!!.jsonPrimitive.boolean)
        assertEquals("2026-10-13T01:00:00Z", receipt["reminder"]!!.jsonObject["remindAt"]!!.jsonPrimitive.content)
        assertFalse(receipt.toString().contains("private-token"))
        execute(f, "reminder.cancel", """{"id":1}""")
        assertEquals(listOf(1L), f.cancelled)
    }

    @Test fun denialBackgroundAndDuplicateRequestsAreEnforcedForNewCommands() = runBlocking {
        val f = Fake()
        val tools = NativeTodoAgentTool(f).tools()
        assertTrue(tools.all { !it.requiresWorkspace && it.policy.requiresConfirmation && it.policy.requiresForeground })
        val executor = AgentToolExecutor(ToolRegistry(tools))
        val args = parseToolArguments("""{"title":"X","date":"2026-10-13"}""")
        try { executor.execute("todo.create", "", args, "one", { true }, { _, _ -> false }); fail() }
        catch (e: AgentToolPolicyException) { assertEquals("user_denied", e.code) }
        try { executor.execute("todo.create", "", args, "one", { false }, { _, _ -> true }); fail() }
        catch (e: AgentToolPolicyException) { assertEquals("foreground_required", e.code) }
        assertTrue(f.todos.isEmpty())
        val first = executor.execute("todo.create", "", args, "one", { true }, { _, _ -> true })
        assertEquals(first, executor.execute("todo.create", "", args, "one", { true }, { _, _ -> fail("Reconfirmed"); false }))
        assertEquals(1, f.todos.size)
    }

    @Test fun deleteChecksVersionAndDoesNotCreateMissingObjects() = runBlocking {
        val f = Fake()
        f.todos += item(1)
        assertEquals("todo_conflict", execute(f, "todo.delete", """{"id":1,"expectedUpdatedAt":"old"}""")["error"]!!.jsonPrimitive.content)
        assertEquals(1, f.todos.size)
        assertTrue(execute(f, "todo.delete", """{"id":1,"expectedUpdatedAt":"v1"}""")["deleted"]!!.jsonPrimitive.boolean)
        assertTrue(f.todos.isEmpty())
    }

    private suspend fun execute(fake: Fake, name: String, json: String) = NativeTodoAgentTool(fake).tools().first { it.name == name }.execute("", parseToolArguments(json)).output
    private fun item(id: Long) = UserTodoItem(id, "Example", "2026-10-13", createdAt = "v1", updatedAt = "v1")
    private class Fake : NativeTodoGateway {
        val todos = mutableListOf<UserTodoItem>()
        val cancelled = mutableListOf<Long>()
        override suspend fun list() = todos.toList()
        override suspend fun save(draft: UserTodoDraft, expectedUpdatedAt: String?): Long {
            val old = todos.find { it.id == draft.id }
            if (expectedUpdatedAt != null && old?.updatedAt != expectedUpdatedAt) throw TodoMutationException("todo_conflict", "Changed")
            val id = draft.id ?: (todos.size + 1L)
            todos.removeAll { it.id == id }
            todos += UserTodoItem(id, draft.title, draft.date, draft.note, draft.done, old?.createdAt ?: "v1", if (old == null) "v1" else "v2")
            return id
        }
        override suspend fun delete(id: Long, expectedUpdatedAt: String) {
            if (todos.find { it.id == id }?.updatedAt != expectedUpdatedAt) throw TodoMutationException("todo_conflict", "Changed")
            todos.removeAll { it.id == id }
        }
        override suspend fun reminders() = mapOf(1L to TodoReminder(1, "v1", 0, "private-token", "scheduled"))
        override fun notificationsEnabled() = true
        override suspend fun setReminder(id: Long, expectedUpdatedAt: String, triggerAtMs: Long) = TodoReminder(id, expectedUpdatedAt, triggerAtMs, "private-token", "scheduled")
        override suspend fun cancelReminder(id: Long) { cancelled += id }
    }
}
