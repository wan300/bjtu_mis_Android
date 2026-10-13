package cn.edu.bjtu.mis.data.agent.tools

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentToolPolicyTest {
    private class FakeTool(
        override val name: String = "mail.send",
        override val requiresWorkspace: Boolean = false,
        override val policy: AgentToolPolicy = AgentToolPolicy.forTool(name),
    ) : AgentTool {
        override val description = "Test action"
        override val parameters = objectSchema("value" to stringSchema(), required = listOf("value"))
        var calls = 0
        var fail = false
        var waitMs = 0L
        override suspend fun execute(taskId: String, arguments: JsonObject): ToolResult {
            calls++
            delay(waitMs)
            if (fail) throw IllegalStateException("test failure")
            return ToolResult(okOutput("value" to arguments.getValue("value")))
        }
    }

    private val args = buildJsonObject { put("value", "test") }
    private suspend fun execute(executor: AgentToolExecutor, arguments: JsonObject = args,
        key: String? = "request-1", foreground: Boolean = true, allowed: Boolean = true,
        name: String = "mail.send", workspace: String = "") =
        executor.execute(name, workspace, arguments, key, { foreground }, { _, _ -> allowed })

    private suspend fun rejects(code: String, action: suspend () -> Unit) {
        try { action(); fail("Expected $code") } catch (error: AgentToolPolicyException) { assertEquals(code, error.code) }
    }

    @Test fun refusesMissingConfirmationBackgroundAndMissingRequestId() = runBlocking {
        val tool = FakeTool()
        val executor = AgentToolExecutor(ToolRegistry(listOf(tool)))
        rejects("user_denied") { execute(executor, allowed = false) }
        rejects("foreground_required") { execute(executor, foreground = false) }
        rejects("request_id_required") { execute(executor, key = null) }
        assertEquals(0, tool.calls)
    }

    @Test fun validatesRegisteredToolsAndSchemaBeforeSideEffects() = runBlocking {
        val tool = FakeTool()
        val executor = AgentToolExecutor(ToolRegistry(listOf(tool)))
        rejects("unknown_tool") { execute(executor, name = "missing") }
        rejects("invalid_arguments") { execute(executor, buildJsonObject {}) }
        rejects("invalid_arguments") { execute(executor, buildJsonObject { put("value", 3) }) }
        rejects("invalid_arguments") { execute(executor, buildJsonObject { put("value", "x"); put("extra", true) }) }
        assertEquals(0, tool.calls)
    }

    @Test fun commandRetriesAreBoundToArgumentsAndSerialized() = runBlocking {
        val tool = FakeTool().apply { waitMs = 5 }
        val executor = AgentToolExecutor(ToolRegistry(listOf(tool)))
        val results = (1..4).map { async { execute(executor) } }.awaitAll()
        assertEquals(1, tool.calls)
        assertEquals(1, results.distinct().size)
        rejects("idempotency_conflict") { execute(executor, buildJsonObject { put("value", "changed") }) }
        assertEquals(1, tool.calls)
    }

    @Test fun uncertainCommandIsNeverAutomaticallyExecutedAgain() = runBlocking {
        val tool = FakeTool().apply { fail = true }
        val executor = AgentToolExecutor(ToolRegistry(listOf(tool)))
        try { execute(executor); fail("Expected failure") } catch (_: IllegalStateException) { }
        val result = execute(executor)
        assertEquals("execution_uncertain", result.output["error"]!!.jsonPrimitive.content)
        assertEquals(1, tool.calls)
    }

    @Test fun rejectsWorkspaceTraversalAndCrossWorkspaceReceiptReuse() = runBlocking {
        val tool = FakeTool(name = "file.write", requiresWorkspace = true)
        val executor = AgentToolExecutor(ToolRegistry(listOf(tool)))
        rejects("workspace_required") { execute(executor, name = tool.name) }
        rejects("workspace_required") { execute(executor, name = tool.name, workspace = "../outside") }
        execute(executor, name = tool.name, workspace = "workspace-1")
        rejects("idempotency_conflict") { execute(executor, name = tool.name, workspace = "workspace-2") }
        assertEquals(1, tool.calls)
    }

    @Test fun enforcesTimeoutAndInputOutputBudgets() = runBlocking {
        val smallInput = FakeTool(policy = AgentToolPolicy("read", "academic", false, maxInputBytes = 2))
        rejects("input_too_large") { execute(AgentToolExecutor(ToolRegistry(listOf(smallInput)))) }
        assertEquals(0, smallInput.calls)
        val smallOutput = FakeTool(policy = AgentToolPolicy("read", "academic", false, maxOutputBytes = 2))
        rejects("output_too_large") { execute(AgentToolExecutor(ToolRegistry(listOf(smallOutput)))) }
        val slow = FakeTool(policy = AgentToolPolicy("read", "academic", false, timeoutMs = 5)).apply { waitMs = 100 }
        rejects("timeout") { execute(AgentToolExecutor(ToolRegistry(listOf(slow)))) }
    }

    @Test fun rechecksForegroundAfterConfirmationAndFailsClosedForNewTools() = runBlocking {
        val tool = FakeTool()
        val executor = AgentToolExecutor(ToolRegistry(listOf(tool)))
        var foreground = true
        rejects("foreground_required") {
            executor.execute(tool.name, "", args, "id", { foreground }, { _, _ -> foreground = false; true })
        }
        val unknown = FakeTool(name = "future.command")
        rejects("capability_unavailable") { execute(AgentToolExecutor(ToolRegistry(listOf(unknown))), name = unknown.name) }
        assertEquals(0, tool.calls)
        assertEquals(0, unknown.calls)
    }

    @Test fun statusIsAvailableWithoutAuthorizationAndUnknownSchemaTypesFail() = runBlocking {
        val tool = FakeTool(name = "system.status")
        execute(AgentToolExecutor(ToolRegistry(listOf(tool))), foreground = false, allowed = false, name = tool.name)
        assertEquals(1, tool.calls)
        rejects("invalid_arguments") { validateToolValue(JsonPrimitive("x"), buildJsonObject { put("type", "unknown") }, "test") }
        rejects("invalid_arguments") { validateToolValue(JsonPrimitive("2"), integerSchema(minimum = 1), "test") }
        rejects("invalid_arguments") { validateToolValue(JsonPrimitive(101), integerSchema(maximum = 100), "test") }
    }

    @Test fun rejectsRemoteMalformedAndLookalikeTopLevelOrigins() {
        assertTrue(isTrustedAgentOrigin("https://localhost/chat/1?q=2", "https", "localhost"))
        listOf(null, "", "https://localhost.evil/", "http://localhost/", "https://localhost:444/",
            "https://localhost@evil/", "https://evil@localhost/", "https://local%68ost/", "file:///localhost", "invalid % url")
            .forEach { assertFalse(it, isTrustedAgentOrigin(it, "https", "localhost")) }
    }

    @Test fun capsReceiptCountAndChecksFreeFormInputDepth() = runBlocking {
        val tool = FakeTool()
        val executor = AgentToolExecutor(ToolRegistry(listOf(tool)))
        repeat(64) { execute(executor, key = "request-$it") }
        rejects("quota_exceeded") { execute(executor, key = "request-65") }
        execute(executor, key = "request-0")
        assertEquals(64, tool.calls)
        var nested: JsonElement = JsonPrimitive("test")
        repeat(34) { nested = buildJsonObject { put("child", nested) } }
        rejects("invalid_arguments") { execute(executor, buildJsonObject { put("value", nested) }) }
    }

    @Test fun commandDigestIgnoresJsonPropertyOrder() = runBlocking {
        val tool = object : AgentTool {
            override val name = "code.run_js"
            override val description = "Test free-form input"
            override val requiresWorkspace = false
            override val parameters = JsonObject(objectSchema() + ("additionalProperties" to JsonPrimitive(true)))
            var calls = 0
            override suspend fun execute(taskId: String, arguments: JsonObject): ToolResult {
                calls++
                return ToolResult(okOutput())
            }
        }
        val executor = AgentToolExecutor(ToolRegistry(listOf(tool)))
        execute(executor, buildJsonObject { put("a", 1); put("b", 2) }, name = tool.name)
        execute(executor, buildJsonObject { put("b", 2); put("a", 1) }, name = tool.name)
        assertEquals(1, tool.calls)
    }
}
