package cn.edu.bjtu.mis.data.agent

import android.app.NotificationManager
import android.Manifest
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bjtu.mis.BjtuMisApplication
import cn.edu.bjtu.mis.data.agent.tools.AgentToolExecutor
import cn.edu.bjtu.mis.data.agent.tools.AgentToolPolicyException
import cn.edu.bjtu.mis.data.agent.tools.parseToolArguments
import cn.edu.bjtu.mis.data.repository.ModuleLoadStrategy
import cn.edu.bjtu.mis.data.repository.TodoMutationException
import cn.edu.bjtu.mis.data.security.SecureCookieStore
import cn.edu.bjtu.mis.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Device tests use the isolated debug application, never the user's release database or session. */
@RunWith(AndroidJUnit4::class)
class NativeCampusToolsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as BjtuMisApplication).container

    @Test
    fun cachedCampusToolsUseRoomAndSeedClearlyLabelledOfflineUiFixtures() = runBlocking {
        assertTrue("Only the isolated debug application may be seeded", context.packageName.endsWith(".debug"))
        val today = LocalDate.now()
        val source = "QA_SYNTHETIC_OFFLINE"
        val sync = container.syncRepository
        sync.saveSnapshot(ModuleKeys.Profile, ModuleEnvelope(module = ModuleKeys.Profile,
            sourceSystem = source, coverage = CoverageLevel.Provisional,
            data = StudentProfileData(name = "QA 合成数据（离线验收）")))
        sync.saveSnapshot(ModuleKeys.Calendar, ModuleEnvelope(module = ModuleKeys.Calendar,
            sourceSystem = source, coverage = CoverageLevel.Provisional,
            sourceParams = buildJsonObject { put("month", today.toString().take(7)) },
            data = CalendarData(month = today.toString().take(7), items = listOf(CalendarItem(today.toString(), note = "QA 合成日程")))))
        sync.saveSnapshot(ModuleKeys.Homework, ModuleEnvelope(module = ModuleKeys.Homework,
            sourceSystem = source, coverage = CoverageLevel.Provisional,
            data = HomeworkData(items = listOf(HomeworkItem(homeworkId = 9001, courseId = 9002,
                course = "QA 合成课程", title = "QA 合成作业（不可提交）", dueAt = today.plusDays(1).toString() + " 18:00:00",
                status = "open", subType = 0, canSubmit = false)))))
        sync.saveSnapshot(ModuleKeys.Exams, ModuleEnvelope(module = ModuleKeys.Exams,
            sourceSystem = source, coverage = CoverageLevel.Provisional,
            data = ExamData(items = listOf(ExamItem(courseName = "QA 合成考试", schedule = today.plusDays(2).toString() + " 09:00")))))
        sync.saveSnapshot(ModuleKeys.Timetable, ModuleEnvelope(module = ModuleKeys.Timetable,
            sourceSystem = source, coverage = CoverageLevel.Provisional,
            data = TimetableData(entries = listOf(CourseEntry(weekday = "周二", period = "1", courseCode = "QA",
                courseName = "QA 合成课程", weeks = "1-20")))))
        // Empty cookies contain no identity or authentication; CacheOnly UI can be exercised offline.
        SecureCookieStore(context).save("[]")
        assertEquals("QA 合成数据（离线验收）", container.moduleRepository.profile(ModuleLoadStrategy.CacheOnly).data.name)
        val tool = container.agentToolRegistry.get("academic.homework")!!
        val output = tool.execute("", buildJsonObject {
            put("startDate", today.toString()); put("endDate", today.plusDays(3).toString())
        }).output
        assertTrue(output["ok"]!!.jsonPrimitive.boolean)
        assertTrue(output.toString().contains("QA 合成作业"))
        assertFalse(output.toString().contains("requirementText"))
    }

    @Test
    fun realWorkManagerNotificationIsDeliveredAndCompletionRevokesIt() = runBlocking {
        assertTrue(context.packageName.endsWith(".debug"))
        // A test-only runtime grant for the API 35 CI emulator; API 31 OEM notification
        // switches still require the visible Settings UI during physical-device acceptance.
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName, Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        assertTrue("Enable notifications for the debug application before this test", container.todoReminders.notificationsEnabled())
        val modules = container.moduleRepository
        val id = modules.saveUserTodo(UserTodoDraft(title = "QA 实机提醒", date = LocalDate.now().toString()))
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            val todo = modules.userTodos().first { it.id == id }
            container.todoReminders.set(id, todo.updatedAt, System.currentTimeMillis() + 3_000)
            withTimeout(45_000) {
                while (manager.activeNotifications.none { it.tag == "native-todo-reminder-$id" }) delay(250)
            }
            assertEquals("delivered", container.todoReminders.store.read().getValue(id).state)
            modules.setUserTodoDone(id, true)
            assertFalse(container.todoReminders.store.read().containsKey(id))
            assertTrue(manager.activeNotifications.none { it.tag == "native-todo-reminder-$id" })
        } finally {
            modules.deleteUserTodo(id)
        }
    }

    @Test
    fun actualRoomConditionalWritesAndExecutorCancellationProtectNativeTodos() = runBlocking {
        assertTrue(context.packageName.endsWith(".debug"))
        val executor = AgentToolExecutor(container.agentToolRegistry)
        val before = container.moduleRepository.userTodos().size
        val args = parseToolArguments("""{"title":"QA 不应写入","date":"2026-10-13"}""")
        try {
            executor.execute("todo.create", "", args, "qa-denied", { true }, { _, _ -> false })
            fail("Denied native command must not execute")
        } catch (error: AgentToolPolicyException) { assertEquals("user_denied", error.code) }
        assertEquals(before, container.moduleRepository.userTodos().size)
        val id = container.moduleRepository.saveUserTodo(UserTodoDraft(title = "QA 条件写入", date = LocalDate.now().toString()))
        try {
            val original = container.moduleRepository.userTodos().first { it.id == id }
            container.moduleRepository.saveUserTodo(UserTodoDraft(id, "QA 已编辑", original.date), original.updatedAt)
            try {
                container.moduleRepository.deleteUserTodo(id, original.updatedAt)
                fail("Stale version must not delete")
            } catch (error: TodoMutationException) { assertEquals("todo_conflict", error.code) }
            assertEquals("QA 已编辑", container.moduleRepository.userTodos().first { it.id == id }.title)
        } finally { container.moduleRepository.deleteUserTodo(id) }
    }
}
