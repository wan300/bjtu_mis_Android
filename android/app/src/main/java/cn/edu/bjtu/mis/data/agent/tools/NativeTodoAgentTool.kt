package cn.edu.bjtu.mis.data.agent.tools

import cn.edu.bjtu.mis.data.repository.ModuleRepository
import cn.edu.bjtu.mis.data.repository.TodoMutationException
import cn.edu.bjtu.mis.data.todo.TodoReminder
import cn.edu.bjtu.mis.data.todo.TodoReminderException
import cn.edu.bjtu.mis.data.todo.TodoReminders
import cn.edu.bjtu.mis.model.UserTodoDraft
import cn.edu.bjtu.mis.model.UserTodoItem
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

interface NativeTodoGateway {
    suspend fun list(): List<UserTodoItem>
    suspend fun save(draft: UserTodoDraft, expectedUpdatedAt: String? = null): Long
    suspend fun delete(id: Long, expectedUpdatedAt: String)
    suspend fun reminders(): Map<Long, TodoReminder>
    fun notificationsEnabled(): Boolean
    suspend fun setReminder(id: Long, expectedUpdatedAt: String, triggerAtMs: Long): TodoReminder
    suspend fun cancelReminder(id: Long)
}

class RepositoryNativeTodoGateway(private val modules: ModuleRepository, private val reminders: TodoReminders) : NativeTodoGateway {
    override suspend fun list() = modules.userTodos()
    override suspend fun save(draft: UserTodoDraft, expectedUpdatedAt: String?) = modules.saveUserTodo(draft, expectedUpdatedAt)
    override suspend fun delete(id: Long, expectedUpdatedAt: String) = modules.deleteUserTodo(id, expectedUpdatedAt)
    override suspend fun reminders() = reminders.store.read()
    override fun notificationsEnabled() = reminders.notificationsEnabled()
    override suspend fun setReminder(id: Long, expectedUpdatedAt: String, triggerAtMs: Long) = reminders.set(id, expectedUpdatedAt, triggerAtMs)
    override suspend fun cancelReminder(id: Long) = reminders.cancel(id)
}

class NativeTodoAgentTool(private val gateway: NativeTodoGateway) {
    fun tools(): List<AgentTool> = listOf(
        tool("todo.list", "查询 App 原生待办及关联提醒。与 WebUI tasks 不同；默认仅未完成，日期可选，最多 100 项。", objectSchema(
            "startDate" to stringSchema("ISO 日期，含当天"), "endDate" to stringSchema("ISO 日期，含当天"),
            "includeDone" to booleanSchema(false), "limit" to integerSchema(minimum = 1, maximum = 100),
        )) { args ->
            val start = args.string("startDate")?.let(::parseDate)
            val end = args.string("endDate")?.let(::parseDate)
            if (start != null && end != null && start > end) invalid("日期范围无效")
            val records = gateway.reminders()
            val items = gateway.list().filter {
                (args.boolean("includeDone") || !it.done) &&
                    (start == null || LocalDate.parse(it.date) >= start) &&
                    (end == null || LocalDate.parse(it.date) <= end)
            }.sortedWith(compareBy({ it.date }, { it.id }))
            val limit = args.int("limit", 50)
            buildJsonObject {
                put("ok", true); put("source", "native_room"); put("notificationsEnabled", gateway.notificationsEnabled())
                put("items", JsonArray(items.take(limit).map { todoJson(it, records[it.id]) }))
                put("total", items.size); put("truncated", items.size > limit)
            }
        },
        tool("todo.create", "创建 App 原生待办，确认后显示在日历。date 必须由用户确定；提醒另用 agent_reminder_set。", objectSchema(
            "title" to stringSchema("1–200 字"), "date" to stringSchema("ISO 日期 YYYY-MM-DD"),
            "note" to stringSchema("可选，最多 2000 字"), required = listOf("title", "date"),
        )) { args ->
            val id = gateway.save(UserTodoDraft(title = title(args.requiredString("title")),
                date = parseDate(args.requiredString("date")).toString(), note = note(args.string("note"))))
            receipt(id)
        },
        tool("todo.update", "编辑或完成 App 待办；先查询 id 和 expectedUpdatedAt。仅修改提供的字段，编辑会撤销旧提醒，需要时重新设置。", objectSchema(
            "id" to integerSchema(minimum = 1), "expectedUpdatedAt" to stringSchema("查询返回的 updatedAt"),
            "title" to stringSchema(), "date" to stringSchema(), "note" to stringSchema("空字符串清空"),
            "done" to booleanSchema(), required = listOf("id", "expectedUpdatedAt"),
        )) { args ->
            val id = id(args)
            val existing = gateway.list().find { it.id == id } ?: missing()
            val fields = args.keys - setOf("id", "expectedUpdatedAt")
            if (fields.isEmpty()) invalid("至少提供一个修改字段")
            gateway.save(UserTodoDraft(id, args.string("title")?.let(::title) ?: existing.title,
                args.string("date")?.let(::parseDate)?.toString() ?: existing.date,
                if ("note" in args) note(args.string("note")) else existing.note,
                args["done"]?.jsonPrimitive?.booleanOrNull ?: existing.done), args.requiredString("expectedUpdatedAt"))
            receipt(id)
        },
        tool("todo.delete", "删除 App 原生待办并撤销关联提醒，需原生确认和最新 updatedAt。", objectSchema(
            "id" to integerSchema(minimum = 1), "expectedUpdatedAt" to stringSchema(), required = listOf("id", "expectedUpdatedAt"),
        )) { args ->
            val id = id(args)
            gateway.delete(id, args.requiredString("expectedUpdatedAt"))
            buildJsonObject { put("ok", true); put("id", id); put("deleted", true); put("reminderCancelled", true) }
        },
        tool("reminder.set", "为原生待办设置/替换一次提醒。使用 id、最新 updatedAt 和带时区的 remindAt（如 2026-10-13T09:00:00+08:00）。需要系统通知许可；系统省电可能延迟，迟到超过 1 小时失效。", objectSchema(
            "id" to integerSchema(minimum = 1), "expectedUpdatedAt" to stringSchema(), "remindAt" to stringSchema("RFC3339，必须有时区；未来 365 天内"),
            required = listOf("id", "expectedUpdatedAt", "remindAt"),
        )) { args ->
            val trigger = try { OffsetDateTime.parse(args.requiredString("remindAt")).toInstant().toEpochMilli() }
                catch (_: Exception) { invalid("remindAt 必须包含有效时区") }
            val record = gateway.setReminder(id(args), args.requiredString("expectedUpdatedAt"), trigger)
            buildJsonObject { put("ok", true); put("id", record.todoId); put("reminder", reminderJson(record)); put("exact", false) }
        },
        tool("reminder.cancel", "撤销原生待办提醒和已发通知；不会删除待办，可重复取消。", objectSchema(
            "id" to integerSchema(minimum = 1), required = listOf("id"),
        )) { args ->
            val id = id(args)
            gateway.cancelReminder(id)
            buildJsonObject { put("ok", true); put("id", id); put("cancelled", true) }
        },
    )

    private fun tool(name: String, description: String, parameters: JsonObject, run: suspend (JsonObject) -> JsonObject) = object : AgentTool {
        override val name = name
        override val description = description
        override val parameters = parameters
        override val requiresWorkspace = false
        override suspend fun execute(taskId: String, arguments: JsonObject): ToolResult = try {
            ToolResult(run(arguments))
        } catch (error: CancellationException) { throw error
        } catch (error: TodoMutationException) { ToolResult(errorOutput(error.code, error.message.orEmpty()))
        } catch (error: TodoReminderException) { ToolResult(errorOutput(error.code, error.message.orEmpty()))
        } catch (error: AgentToolPolicyException) { ToolResult(errorOutput(error.code, error.message.orEmpty()))
        } catch (_: IllegalArgumentException) { ToolResult(errorOutput("invalid_arguments", "参数无效，请核对待办字段"))
        } catch (_: Exception) { ToolResult(errorOutput("todo_operation_failed", "操作未完成，请查询原生待办和提醒状态，勿自动重试")) }
    }

    private suspend fun receipt(id: Long): JsonObject {
        val item = gateway.list().find { it.id == id } ?: missing()
        return buildJsonObject { put("ok", true); put("todo", todoJson(item, gateway.reminders()[id])) }
    }
    private fun id(args: JsonObject) = args["id"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: invalid("id 无效")
    private fun title(value: String) = value.trim().also { if (it.isEmpty() || it.length > 200) invalid("标题须为 1–200 字") }
    private fun note(value: String?) = value?.trim()?.also { if (it.length > 2000) invalid("备注不能超过 2000 字") }?.ifBlank { null }
    private fun parseDate(value: String): LocalDate = try { LocalDate.parse(value) } catch (_: Exception) { invalid("日期必须为 YYYY-MM-DD") }
    private fun invalid(message: String): Nothing = throw AgentToolPolicyException("invalid_arguments", message)
    private fun missing(): Nothing = throw TodoMutationException("todo_not_found", "待办不存在")
}

private fun todoJson(item: UserTodoItem, reminder: TodoReminder?): JsonObject = buildJsonObject {
    put("id", item.id); put("title", item.title); put("date", item.date); put("done", item.done)
    item.note?.let { put("note", it) }; put("updatedAt", item.updatedAt)
    if (reminder != null && !item.done && reminder.todoUpdatedAt == item.updatedAt) put("reminder", reminderJson(reminder))
}

private fun reminderJson(record: TodoReminder) = buildJsonObject {
    put("state", record.state); put("remindAt", Instant.ofEpochMilli(record.triggerAtMs).toString())
}
