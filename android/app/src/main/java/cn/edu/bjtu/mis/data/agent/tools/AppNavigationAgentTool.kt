package cn.edu.bjtu.mis.data.agent.tools

import cn.edu.bjtu.mis.data.repository.ModuleLoadStrategy
import cn.edu.bjtu.mis.data.repository.ModuleRepository
import cn.edu.bjtu.mis.model.HomeworkItem
import cn.edu.bjtu.mis.model.ModuleKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.util.UUID

data class AgentPageTarget(
    val page: String,
    val route: String,
    val homework: HomeworkItem? = null,
    val courseId: String? = null,
    val folderId: String = "0",
)

data class AgentPageRequest(val id: String, val target: AgentPageTarget)

/** A single-use typed handoff; the bridge releases it only after returning the tool receipt. */
class AgentNavigationRequests {
    private var prepared: AgentPageRequest? = null
    private val mutableRequests = MutableStateFlow<AgentPageRequest?>(null)
    val requests = mutableRequests.asStateFlow()

    @Synchronized fun prepare(target: AgentPageTarget): String {
        val request = AgentPageRequest(UUID.randomUUID().toString(), target)
        prepared = request
        return request.id
    }
    @Synchronized fun dispatch(id: String) {
        val request = prepared?.takeIf { it.id == id } ?: return
        prepared = null
        mutableRequests.value = request
    }
    @Synchronized fun consume(id: String) {
        if (mutableRequests.value?.id == id) mutableRequests.value = null
    }
}

interface AgentPageGateway {
    suspend fun homework(courseId: Int, homeworkId: Int): HomeworkItem?
    suspend fun resourceFolderExists(courseId: String, folderId: String): Boolean
    fun prepare(target: AgentPageTarget): String
}

class RepositoryAgentPageGateway(private val modules: ModuleRepository, private val requests: AgentNavigationRequests) : AgentPageGateway {
    override suspend fun homework(courseId: Int, homeworkId: Int): HomeworkItem? =
        modules.homework(strategy = ModuleLoadStrategy.CacheOnly).data.items
            .find { it.courseId == courseId && it.homeworkId == homeworkId }

    override suspend fun resourceFolderExists(courseId: String, folderId: String): Boolean {
        val data = modules.courseResources(courseId = courseId, folderId = folderId,
            categoryKey = "all", strategy = ModuleLoadStrategy.CacheOnly).data
        return data.selectedCourseId?.toString() == courseId && data.folderId == folderId
    }
    override fun prepare(target: AgentPageTarget) = requests.prepare(target)
}

class AppNavigationAgentTool(private val gateway: AgentPageGateway) : AgentTool {
    override val name = "app.open_page"
    override val description = "确认后打开原生校园页面。page: timetable/exams/calendar/homework/course_resources/homework_detail。作业详情需缓存中的 courseId+homeworkId；资料目录需已缓存 courseId+folderId。仅导航，无联网刷新/下载/提交。返回 queued 表示请求已接收，界面随后切换。"
    override val requiresWorkspace = false
    override val parameters = objectSchema(
        "page" to buildJsonObject { put("type", "string"); put("enum", JsonArray(Pages.keys.map(::JsonPrimitive))) },
        "courseId" to stringSchema("正整数 ID"), "homeworkId" to integerSchema(minimum = 1),
        "folderId" to stringSchema("正整数 ID 或根目录 0"), required = listOf("page"),
    )

    override suspend fun execute(taskId: String, arguments: JsonObject): ToolResult = try {
        val page = arguments.requiredString("page")
        val route = Pages[page] ?: invalid("页面不在白名单中")
        val courseId = arguments.string("courseId")
        val homeworkId = arguments["homeworkId"]?.jsonPrimitive?.intOrNull
        val folderId = arguments.string("folderId") ?: "0"
        if (courseId != null && !courseId.matches(Regex("[1-9][0-9]{0,8}"))) invalid("courseId 无效")
        if (!folderId.matches(Regex("0|[1-9][0-9]{0,8}"))) invalid("folderId 无效")
        val target = when (page) {
            "homework_detail" -> {
                if (courseId == null || homeworkId == null || "folderId" in arguments) invalid("作业详情需要 courseId 和 homeworkId")
                val homework = gateway.homework(courseId.toInt(), homeworkId)
                    ?: throw AgentToolPolicyException("navigation_target_missing", "作业不在本地缓存中，请先在 App 同步作业")
                AgentPageTarget(page, route, homework = homework)
            }
            "course_resources" -> {
                if (homeworkId != null || ("folderId" in arguments && courseId == null)) invalid("资料目录参数无效")
                if (courseId != null && !gateway.resourceFolderExists(courseId, folderId)) {
                    throw AgentToolPolicyException("navigation_target_missing", "资料目录不在本地缓存中，请先在 App 打开该课程")
                }
                AgentPageTarget(page, route, courseId = courseId, folderId = folderId)
            }
            else -> {
                if (arguments.keys.any { it != "page" }) invalid("此页面不接受对象 ID")
                AgentPageTarget(page, route)
            }
        }
        val requestId = gateway.prepare(target)
        ToolResult(buildJsonObject { put("ok", true); put("page", page); put("status", "queued"); put("navigationRequestId", requestId) })
    } catch (error: CancellationException) { throw error
    } catch (error: AgentToolPolicyException) { ToolResult(errorOutput(error.code, error.message.orEmpty()))
    } catch (_: Exception) { ToolResult(errorOutput("navigation_target_missing", "本地导航目标不可用，请先在 App 查看或同步")) }

    private fun invalid(message: String): Nothing = throw AgentToolPolicyException("invalid_arguments", message)

    companion object {
        private val Pages = linkedMapOf("timetable" to ModuleKeys.Timetable, "exams" to ModuleKeys.Exams,
            "calendar" to ModuleKeys.Calendar, "homework" to ModuleKeys.Homework,
            "course_resources" to ModuleKeys.CourseResources, "homework_detail" to ModuleKeys.Homework)
    }
}
