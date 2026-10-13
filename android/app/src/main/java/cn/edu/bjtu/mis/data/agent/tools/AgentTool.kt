package cn.edu.bjtu.mis.data.agent.tools

import cn.edu.bjtu.mis.data.AppJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

interface AgentTool {
    val name: String
    val description: String
    val parameters: JsonObject
    val requiresWorkspace: Boolean
        get() = true
    val policy: AgentToolPolicy
        get() = AgentToolPolicy.forTool(name)

    suspend fun execute(taskId: String, arguments: JsonObject): ToolResult
}

data class ToolResult(
    val output: JsonObject,
    val stdout: String? = null,
    val stderr: String? = null,
    val artifacts: List<ToolArtifact> = emptyList(),
)

data class ToolArtifact(
    val relativePath: String,
    val mimeType: String,
    val role: String,
    val sizeBytes: Long,
)

class ToolRegistry(
    tools: List<AgentTool>,
) {
    init {
        require(tools.map { it.name }.distinct().size == tools.size) { "Duplicate Agent tool name" }
    }
    private val byName = tools.associateBy { it.name }

    val allTools: List<AgentTool> = tools

    fun get(name: String): AgentTool? = byName[name]
}

fun AgentTool.openAiSchema(): JsonObject = buildJsonObject {
    put("type", "function")
    put("function", buildJsonObject {
        put("name", name)
        put("description", description)
        put("parameters", parameters)
    })
}

fun objectSchema(vararg properties: Pair<String, JsonObject>, required: List<String> = emptyList()): JsonObject =
    buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("properties", buildJsonObject {
            properties.forEach { (name, schema) -> put(name, schema) }
        })
        put("required", kotlinx.serialization.json.JsonArray(required.map { JsonPrimitive(it) }))
    }

fun stringSchema(description: String? = null): JsonObject = buildJsonObject {
    put("type", "string")
    if (description != null) put("description", description)
}

fun booleanSchema(defaultValue: Boolean? = null): JsonObject = buildJsonObject {
    put("type", "boolean")
    if (defaultValue != null) put("default", defaultValue)
}

fun integerSchema(description: String? = null, minimum: Int? = null, maximum: Int? = null): JsonObject = buildJsonObject {
    put("type", "integer")
    if (description != null) put("description", description)
    if (minimum != null) put("minimum", minimum)
    if (maximum != null) put("maximum", maximum)
}

fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

fun JsonObject.requiredString(name: String): String =
    string(name)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("缺少参数 $name")

fun JsonObject.boolean(name: String, defaultValue: Boolean = false): Boolean =
    this[name]?.jsonPrimitive?.booleanOrNull ?: defaultValue

fun JsonObject.int(name: String, defaultValue: Int): Int =
    this[name]?.jsonPrimitive?.intOrNull ?: defaultValue

fun JsonObject.objectOrEmpty(name: String): JsonObject =
    this[name]?.jsonObject ?: buildJsonObject { }

fun parseToolArguments(arguments: String): JsonObject =
    AppJson.parseToJsonElement(arguments).jsonObject

fun errorOutput(code: String, message: String): JsonObject = buildJsonObject {
    put("ok", false)
    put("error", code)
    put("message", message)
}

fun okOutput(vararg values: Pair<String, JsonElement>): JsonObject = buildJsonObject {
    put("ok", true)
    values.forEach { (key, value) -> put(key, value) }
}
