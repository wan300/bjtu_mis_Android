package cn.edu.bjtu.mis.data.agent.tools

import java.security.MessageDigest
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

data class AgentToolPolicy(
    val operation: String,
    val dataScope: String,
    val requiresConfirmation: Boolean,
    val requiresForeground: Boolean = requiresConfirmation,
    val timeoutMs: Long = 60_000,
    val maxInputBytes: Int = 256 * 1024,
    val maxOutputBytes: Int = 1024 * 1024,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("operation", operation)
        put("dataScope", dataScope)
        put("requiresConfirmation", requiresConfirmation)
        put("requiresForeground", requiresForeground)
        put("timeoutMs", timeoutMs)
        put("maxInputBytes", maxInputBytes)
        put("maxOutputBytes", maxOutputBytes)
        put("requiresIdempotencyKey", operation == "command")
    }

    companion object {
        fun forTool(name: String): AgentToolPolicy = when (name) {
            "system.status" -> AgentToolPolicy("read", "status", false)
            "academic.timetable", "academic.exams", "academic.homework", "academic.agenda" ->
                AgentToolPolicy("read", "academic", true)
            "todo.list" -> AgentToolPolicy("read", "todo", true)
            "todo.create", "todo.update", "todo.delete", "reminder.set", "reminder.cancel" ->
                AgentToolPolicy("command", "todo", true)
            "app.open_page" -> AgentToolPolicy("command", "navigation", true)
            "file.list", "file.read" -> AgentToolPolicy("read", "workspace", false)
            "file.write", "file.delete", "archive.extract", "archive.create_zip",
            "document.extract_pdf", "document.extract_docx", "document.generate_pdf",
            "document.generate_docx", "package.results" -> AgentToolPolicy("command", "workspace", true)
            "code.run_js" -> AgentToolPolicy("command", "workspace", true)
            "mail.list_folders", "mail.list_recent", "mail.read", "mail.digest_context",
            "mail.search_contacts" -> AgentToolPolicy("read", "mail", true)
            "mail.mark_read", "mail.save_draft", "mail.send" -> AgentToolPolicy("command", "mail", true)
            else -> AgentToolPolicy("unavailable", "unknown", true)
        }
    }
}

class AgentToolPolicyException(val code: String, message: String) : IllegalArgumentException(message)

/** This executor belongs to one visible WebView bridge; no grants or receipts survive its destruction. */
class AgentToolExecutor(private val registry: ToolRegistry) {
    private val mutex = Mutex()
    private data class Receipt(val digest: String, val result: ToolResult)
    private val receipts = linkedMapOf<String, Receipt>()

    suspend fun execute(
        name: String,
        workspaceId: String,
        arguments: JsonObject,
        requestId: String?,
        isForeground: suspend () -> Boolean,
        confirm: suspend (AgentTool, JsonObject) -> Boolean,
    ): ToolResult = mutex.withLock {
        val tool = registry.get(name) ?: reject("unknown_tool", "工具未注册")
        val policy = tool.policy
        if (policy.operation == "unavailable") reject("capability_unavailable", "工具尚未配置执行策略")
        validateToolStructure(arguments)
        if (arguments.toString().toByteArray().size > policy.maxInputBytes) reject("input_too_large", "工具参数过大")
        validateToolValue(arguments, tool.parameters, "arguments")
        if (tool.requiresWorkspace && !workspaceId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))) {
            reject("workspace_required", "需要有效的 Agent 工作区")
        }
        if (policy.requiresForeground && !isForeground()) reject("foreground_required", "请在前台打开 Agent")
        val command = policy.operation == "command"
        val key = requestId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
        if (command && key == null) reject("request_id_required", "状态修改需要请求 ID")
        val digest = digest(buildJsonObject {
            put("tool", name)
            put("workspace", workspaceId)
            put("arguments", arguments)
        })
        if (command) {
            receipts[key]?.let {
                if (it.digest != digest) reject("idempotency_conflict", "请求 ID 已用于不同参数")
                return@withLock it.result
            }
            // Never evict receipts and silently permit an old command to execute twice.
            if (receipts.size >= 64) reject("quota_exceeded", "本次 Agent 会话操作已达上限，请重新打开")
        }
        if (policy.requiresConfirmation && !confirm(tool, arguments)) reject("user_denied", "用户取消授权")
        if (policy.requiresForeground && !isForeground()) reject("foreground_required", "Agent 已离开前台")
        if (command) receipts[key!!] = Receipt(digest, ToolResult(errorOutput("execution_uncertain", "操作未完成，请先检查实际状态，勿自动重试")))
        try {
            val result = withTimeout(policy.timeoutMs) { tool.execute(workspaceId, arguments) }
            val outputBytes = result.output.toString().toByteArray().size +
                (result.stdout?.toByteArray()?.size ?: 0) + (result.stderr?.toByteArray()?.size ?: 0) +
                result.artifacts.sumOf { it.relativePath.toByteArray().size + it.mimeType.length + it.role.length + 32 }
            if (outputBytes > policy.maxOutputBytes) reject("output_too_large", "工具输出超过限额")
            if (command) receipts[key!!] = Receipt(digest, result)
            result
        } catch (error: TimeoutCancellationException) {
            throw AgentToolPolicyException("timeout", "工具执行超时；状态修改请先检查实际结果")
        } catch (error: CancellationException) {
            throw error
        }
    }

    private fun digest(value: JsonElement): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical(value).toString().toByteArray()).joinToString("") { "%02x".format(it) }

    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }
}

private fun reject(code: String, message: String): Nothing = throw AgentToolPolicyException(code, message)

internal fun isTrustedAgentOrigin(pageUrl: String?, scheme: String, host: String): Boolean = runCatching {
    val page = URI(pageUrl.orEmpty())
    page.scheme == scheme && page.rawAuthority == host && page.rawUserInfo == null
}.getOrDefault(false)

private fun validateToolStructure(value: JsonElement) {
    val pending = ArrayDeque<Pair<JsonElement, Int>>()
    pending.add(value to 0)
    var nodes = 0
    while (pending.isNotEmpty()) {
        val (current, depth) = pending.removeLast()
        if (depth > 32 || ++nodes > 16_384) reject("invalid_arguments", "参数结构超过限额")
        when (current) {
            is JsonObject -> current.values.forEach { pending.add(it to depth + 1) }
            is JsonArray -> current.forEach { pending.add(it to depth + 1) }
            else -> Unit
        }
    }
}

/** Validate the schema subset used by native tools without coercing model-supplied values. */
internal fun validateToolValue(value: JsonElement, schema: JsonObject, path: String, depth: Int = 0) {
    if (depth > 32) reject("invalid_arguments", "参数嵌套过深")
    fun invalid(): Nothing = reject("invalid_arguments", "参数不符合契约：$path")
    val primitive = value as? JsonPrimitive
    when (schema["type"]?.jsonPrimitive?.content) {
        "object" -> {
            val obj = value as? JsonObject ?: invalid()
            val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
            val required = (schema["required"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
            if (required.any { it !in obj }) invalid()
            if (schema["additionalProperties"] == JsonPrimitive(false) && obj.keys.any { it !in properties }) invalid()
            obj.forEach { (key, child) ->
                (properties[key] as? JsonObject)?.let { validateToolValue(child, it, "$path.$key", depth + 1) }
            }
        }
        "array" -> {
            val array = value as? JsonArray ?: invalid()
            if (array.size > 4096) invalid()
            val items = schema["items"] as? JsonObject ?: invalid()
            array.forEach { validateToolValue(it, items, "$path[]", depth + 1) }
        }
        "string" -> if (primitive?.isString != true) invalid()
        "boolean" -> if (primitive == null || primitive.isString || primitive.booleanOrNull == null) invalid()
        "integer", "number" -> {
            if (primitive == null || primitive.isString) invalid()
            val number = primitive.doubleOrNull?.takeIf { it.isFinite() } ?: invalid()
            if (schema["type"]?.jsonPrimitive?.content == "integer" && number % 1.0 != 0.0) invalid()
            schema["minimum"]?.jsonPrimitive?.doubleOrNull?.let { if (number < it) invalid() }
            schema["maximum"]?.jsonPrimitive?.doubleOrNull?.let { if (number > it) invalid() }
        }
        else -> invalid()
    }
    (schema["enum"] as? JsonArray)?.let { if (value !in it) invalid() }
}
