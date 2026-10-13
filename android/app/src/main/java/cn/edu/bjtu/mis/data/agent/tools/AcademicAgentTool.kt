package cn.edu.bjtu.mis.data.agent.tools

import cn.edu.bjtu.mis.data.calendar.examScheduleDate
import cn.edu.bjtu.mis.data.homework.homeworkDueDate
import cn.edu.bjtu.mis.data.repository.LocalSnapshotMissingException
import cn.edu.bjtu.mis.data.repository.ModuleLoadStrategy
import cn.edu.bjtu.mis.data.repository.ModuleRepository
import cn.edu.bjtu.mis.data.repository.SessionRepository
import cn.edu.bjtu.mis.model.*
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

data class CampusSection(
    val items: List<JsonObject>,
    val sourceSystem: String,
    val syncedAt: String?,
    val fromCache: Boolean,
    val provisional: Boolean = false,
    val errors: List<String> = emptyList(),
)

interface AcademicAgentGateway {
    suspend fun status(): JsonObject
    suspend fun load(module: String, start: LocalDate, end: LocalDate, refresh: Boolean): CampusSection
}

class RepositoryAcademicAgentGateway(
    private val modules: ModuleRepository,
    private val session: SessionRepository,
) : AcademicAgentGateway {
    override suspend fun status(): JsonObject = buildJsonObject {
        put("sessionState", when (session.cachedStatus().state) {
            SessionState.Ready -> "ready"
            SessionState.WaitingForLogin -> "waiting_for_login"
            SessionState.Expired -> "expired"
        })
        put("sessionValidated", false)
        val snapshots = modules.snapshots().associateBy { it.moduleKey }
        put("modules", JsonArray(CAMPUS_MODULES.map { module ->
            val snapshot = snapshots[module]
            buildJsonObject {
                put("module", module)
                put("cacheAvailable", snapshot != null)
                snapshot?.let {
                    put("sourceSystem", it.sourceSystem)
                    put("syncedAt", it.syncedAt)
                }
                put("stale", isCampusSnapshotStale(snapshot?.syncedAt, Instant.now()))
            }
        }))
    }

    override suspend fun load(module: String, start: LocalDate, end: LocalDate, refresh: Boolean): CampusSection {
        val strategy = if (refresh) ModuleLoadStrategy.NetworkFirst else ModuleLoadStrategy.CacheOnly
        // NetworkFirst can fall back silently; unchanged snapshot timestamps must remain labelled cached.
        val before = modules.snapshots().associate { it.moduleKey to it.syncedAt }
        fun <T> section(envelope: ModuleEnvelope<T>, items: List<JsonObject>) = CampusSection(
            items, envelope.sourceSystem, envelope.syncedAt,
            !refresh || envelope.syncedAt == null || envelope.syncedAt == before[module],
            envelope.coverage != CoverageLevel.Verified,
        )
        return when (module) {
            ModuleKeys.Timetable -> modules.timetable(strategy).let { envelope ->
                section(envelope, envelope.data.entries.map { item -> buildJsonObject {
                    text("courseName", item.courseName)
                    text("courseCode", item.courseCode)
                    text("weekday", item.weekday)
                    normalizedTimetableWeekdayIndex(item.weekday)?.let { put("weekdayIndex", it + 1) }
                    text("period", item.period)
                    text("timeRange", item.timeRange)
                    text("weeks", item.weeks)
                    text("location", item.locationText ?: listOfNotNull(item.campus, item.building, item.room).joinToString(" "))
                    put("isUserCreated", item.isUserCreated)
                } })
            }
            ModuleKeys.Exams -> modules.exams(strategy = strategy).let { envelope ->
                section(envelope, envelope.data.items.map { item -> buildJsonObject {
                    text("courseName", item.courseName)
                    text("schedule", item.schedule)
                    text("date", examScheduleDate(item.schedule, item.term)?.toString())
                    text("status", item.status)
                } })
            }
            ModuleKeys.Homework -> modules.homework(strategy = strategy).let { envelope ->
                section(envelope, envelope.data.items.map { item -> buildJsonObject {
                    item.homeworkId?.let { put("homeworkId", it) }
                    put("courseId", item.courseId)
                    text("course", item.course)
                    text("title", item.title)
                    text("dueAt", item.dueAt)
                    text("date", homeworkDueDate(item)?.toString())
                    text("status", item.status)
                    put("attachments", JsonArray(item.attachments.take(20).map { attachment -> buildJsonObject {
                        text("attachmentId", attachment.attachmentId)
                        text("filename", attachment.filename)
                        text("size", attachment.size)
                    } }))
                    put("attachmentsTruncated", item.attachments.size > 20)
                } })
            }
            ModuleKeys.Calendar -> {
                val months = generateSequence(YearMonth.from(start)) { it.plusMonths(1) }
                    .takeWhile { it <= YearMonth.from(end) }.toList()
                val errors = mutableListOf<String>()
                val parts = months.mapNotNull { month ->
                    try {
                        val cachedAt = modules.snapshots().firstOrNull { it.moduleKey == module }?.syncedAt
                        modules.calendar(month.toString(), strategy).let { envelope ->
                            CampusSection(envelope.data.items.map { item -> buildJsonObject {
                                text("date", item.date)
                                text("week", item.week)
                                text("note", item.note)
                            } }, envelope.sourceSystem, envelope.syncedAt,
                                !refresh || envelope.syncedAt == null || envelope.syncedAt == cachedAt,
                                envelope.coverage != CoverageLevel.Verified)
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        errors += "$month: ${if (error is LocalSnapshotMissingException) "cache_missing" else "query_failed"}"
                        null
                    }
                }
                if (parts.isEmpty()) {
                    if (errors.all { it.endsWith("cache_missing") }) throw LocalSnapshotMissingException(module)
                    throw IllegalStateException("Calendar query failed")
                }
                CampusSection(parts.flatMap { it.items }, "VE", parts.mapNotNull { it.syncedAt }.minOrNull(),
                    parts.any { it.fromCache }, parts.any { it.provisional }, errors)
            }
            "todos" -> CampusSection(modules.userTodos().map { item -> buildJsonObject {
                put("id", item.id)
                text("title", item.title)
                text("date", item.date)
                put("done", item.done)
            } }, "local", null, true)
            else -> throw AgentToolPolicyException("unknown_module", "未知校园模块")
        }
    }
}

private val CAMPUS_MODULES = setOf(ModuleKeys.Timetable, ModuleKeys.Exams, ModuleKeys.Homework, ModuleKeys.Calendar)
private fun JsonObjectBuilder.text(key: String, value: String?) {
    value?.takeIf { it.isNotBlank() }?.let { put(key, it.take(1000)) }
    if (value != null && value.length > 1000) put("fieldsTruncated", true)
}

internal fun isCampusSnapshotStale(timestamp: String?, now: Instant): Boolean = timestamp?.let {
    runCatching { Duration.between(Instant.parse(it), now).let { age -> age.isNegative || age > Duration.ofHours(24) } }.getOrDefault(true)
} ?: true

class AcademicAgentTool(
    private val gateway: AcademicAgentGateway,
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Shanghai")),
) {
    fun tools(): List<AgentTool> = listOf(StatusTool(), QueryTool("timetable"), QueryTool("exams"),
        QueryTool("homework"), QueryTool("agenda"))

    private inner class StatusTool : AgentTool {
        override val name = "system.status"
        override val requiresWorkspace = false
        override val description = "Read cached campus session state and module snapshot timestamps. No credentials or network validation."
        override val parameters = objectSchema()
        override suspend fun execute(taskId: String, arguments: JsonObject) = ToolResult(buildJsonObject {
            put("ok", true)
            put("data", gateway.status())
            put("meta", buildJsonObject { put("fromCache", true); put("timezone", clock.zone.id) })
        })
    }

    private inner class QueryTool(private val kind: String) : AgentTool {
        override val name = "academic.$kind"
        override val requiresWorkspace = false
        override val description = "Query campus $kind for an inclusive date window (default this week, max 31 days). " +
            "Cache only by default; refresh=true requests network after native consent. " +
            "Requires consent to send results to the configured model provider. " +
            "Timetable entries are recurrence candidates, not confirmed dated classes; supply teaching week when known. " +
            "Unknown dates and partial failures are explicit; never interpret them as no obligations."
        override val parameters = objectSchema(
            "startDate" to stringSchema("Inclusive YYYY-MM-DD, campus timezone Asia/Shanghai."),
            "endDate" to stringSchema("Inclusive YYYY-MM-DD. Defaults to 6 days after startDate."),
            "week" to integerSchema("Optional teaching week for timetable recurrence filtering.", 1, 60),
            "refresh" to booleanSchema(false),
            "limit" to integerSchema("Maximum total returned items, default 50.", 1, 100),
        )

        override suspend fun execute(taskId: String, arguments: JsonObject): ToolResult {
            val today = LocalDate.now(clock)
            val start = date(arguments.string("startDate")) ?: today.minusDays((today.dayOfWeek.value - 1).toLong())
            val end = date(arguments.string("endDate")) ?: start.plusDays(6)
            if (end < start || end > start.plusDays(30)) throw AgentToolPolicyException("invalid_arguments", "日期范围必须为 1–31 天")
            val refresh = arguments.boolean("refresh")
            val week = arguments["week"]?.jsonPrimitive?.intOrNull
            var remaining = arguments.int("limit", 50).coerceIn(1, 100)
            val modules = if (kind == "agenda") listOf(ModuleKeys.Timetable, ModuleKeys.Exams, ModuleKeys.Homework, ModuleKeys.Calendar, "todos")
                else listOf(kind)
            val sections = linkedMapOf<String, JsonElement>()
            var partial = false
            for (module in modules) {
                try {
                    val section = withTimeout(10_000) { gateway.load(module, start, end, refresh) }
                    val matching = section.items.filter { matches(it, module, start, end, week) }
                    val selected = matching.take(remaining)
                    remaining -= selected.size
                    val unresolved = matching.count { it.string("date") == null }
                    val truncated = matching.size > selected.size
                    val refreshFailed = refresh && section.fromCache && section.sourceSystem != "local"
                    val fieldsTruncated = selected.any { it["fieldsTruncated"] == JsonPrimitive(true) || it["attachmentsTruncated"] == JsonPrimitive(true) }
                    val incomplete = section.provisional || unresolved > 0 || truncated || refreshFailed || fieldsTruncated || section.errors.isNotEmpty()
                    partial = partial || incomplete
                    sections[module] = buildJsonObject {
                        put("ok", true)
                        put("items", JsonArray(selected))
                        put("meta", buildJsonObject {
                            put("sourceSystem", section.sourceSystem)
                            section.syncedAt?.let { put("syncedAt", it) }
                            put("fromCache", section.fromCache)
                            put("stale", section.sourceSystem != "local" && stale(section.syncedAt))
                            put("partial", incomplete)
                            put("truncated", truncated)
                            put("matchedCount", matching.size)
                            put("unresolvedDateCount", unresolved)
                            put("refreshFailed", refreshFailed)
                            put("fieldsTruncated", fieldsTruncated)
                            put("errors", JsonArray(section.errors.map { JsonPrimitive(it) }))
                            if (module == ModuleKeys.Timetable) put("warning", "课程为按星期/教学周筛选的周期候选，未核实校历日期和临时调课，不能据此保证无冲突。")
                        })
                    }
                } catch (error: Exception) {
                    if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                    partial = true
                    sections[module] = buildJsonObject {
                        put("ok", false)
                        put("error", when (error) {
                            is LocalSnapshotMissingException -> "cache_missing"
                            is kotlinx.coroutines.TimeoutCancellationException -> "timeout"
                            else -> "query_failed"
                        })
                        put("message", "此模块查询失败，不能认定没有事务。无缓存时请请求 refresh 并确认联网。")
                    }
                }
            }
            return ToolResult(buildJsonObject {
                put("ok", sections.values.any { (it as JsonObject)["ok"] == JsonPrimitive(true) })
                put("data", JsonObject(sections))
                put("meta", buildJsonObject {
                    put("startDate", start.toString()); put("endDate", end.toString())
                    put("timezone", clock.zone.id); put("partial", partial); put("refreshRequested", refresh)
                })
            })
        }
    }

    private fun date(text: String?): LocalDate? = text?.let {
        try { LocalDate.parse(it) } catch (_: Exception) { throw AgentToolPolicyException("invalid_arguments", "日期应为 YYYY-MM-DD") }
    }

    private fun stale(timestamp: String?): Boolean = isCampusSnapshotStale(timestamp, clock.instant())

    private fun matches(item: JsonObject, module: String, start: LocalDate, end: LocalDate, week: Int?): Boolean {
        if (module == ModuleKeys.Timetable) {
            val weekday = item["weekdayIndex"]?.jsonPrimitive?.intOrNull
            val weekdays = generateSequence(start) { it.plusDays(1) }.takeWhile { it <= end }.map { it.dayOfWeek.value }.toSet()
            if (weekday != null && weekday !in weekdays) return false
            val weeks = parseTimetableWeekNumbers(item.string("weeks"))
            return week == null || weeks == null || week in weeks
        }
        val date = item.string("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        return date == null || date in start..end
    }
}
