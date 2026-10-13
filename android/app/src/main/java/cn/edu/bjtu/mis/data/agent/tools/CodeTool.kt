package cn.edu.bjtu.mis.data.agent.tools

import cn.edu.bjtu.mis.data.agent.runtime.RuntimeManager
import kotlinx.serialization.json.JsonObject

class CodeTool(
    private val runtimeManager: RuntimeManager,
) {
    fun tools(): List<AgentTool> = listOf(RunJsTool())

    private inner class RunJsTool : AgentTool {
        override val name = "code.run_js"
        override val description = "Run a small ECMAScript snippet in AndroidX JavaScriptSandbox. No Node.js, npm, file system, network, or shell."
        override val parameters = objectSchema(
            "code" to stringSchema("JavaScript body. Use return to return a JSON-serializable result."),
            "input" to kotlinx.serialization.json.JsonObject(objectSchema() +
                ("additionalProperties" to kotlinx.serialization.json.JsonPrimitive(true))),
            "timeoutSeconds" to integerSchema(minimum = 1, maximum = 30),
            required = listOf("code"),
        )

        override suspend fun execute(taskId: String, arguments: JsonObject): ToolResult {
            val result = runtimeManager.runJs(
                code = arguments.requiredString("code"),
                input = arguments.objectOrEmpty("input"),
                timeoutSeconds = arguments.int("timeoutSeconds", 5),
            )
            return ToolResult(result.result, stdout = result.stdout, stderr = result.stderr)
        }
    }
}
