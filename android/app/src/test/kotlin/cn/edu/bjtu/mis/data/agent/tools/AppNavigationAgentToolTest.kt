package cn.edu.bjtu.mis.data.agent.tools

import cn.edu.bjtu.mis.model.HomeworkItem
import cn.edu.bjtu.mis.model.ModuleKeys
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AppNavigationAgentToolTest {
    @Test fun onlyWhitelistedPagesCanBePreparedWithoutWorkspace() = runBlocking {
        val gateway = Fake()
        val tool = AppNavigationAgentTool(gateway)
        assertFalse(tool.requiresWorkspace)
        assertEquals("command", tool.policy.operation)
        assertTrue(tool.policy.requiresConfirmation)
        assertTrue(tool.execute("", parseToolArguments("""{"page":"calendar"}""")).output["ok"]!!.jsonPrimitive.boolean)
        assertEquals(ModuleKeys.Calendar, gateway.target!!.route)
        assertEquals("invalid_arguments", tool.execute("", parseToolArguments("""{"page":"https://example.invalid"}""")).output["error"]!!.jsonPrimitive.content)
        assertEquals("invalid_arguments", tool.execute("", parseToolArguments("""{"page":"calendar","courseId":"2"}""")).output["error"]!!.jsonPrimitive.content)
    }

    @Test fun homeworkDetailNeedsExactCachedIdentity() = runBlocking {
        val gateway = Fake()
        val tool = AppNavigationAgentTool(gateway)
        assertEquals("navigation_target_missing", tool.execute("", parseToolArguments("""{"page":"homework_detail","courseId":"3","homeworkId":1}""")).output["error"]!!.jsonPrimitive.content)
        assertNull(gateway.target)
        val result = tool.execute("", parseToolArguments("""{"page":"homework_detail","courseId":"2","homeworkId":1}""")).output
        assertEquals("queued", result["status"]!!.jsonPrimitive.content)
        assertEquals(1, gateway.target!!.homework!!.homeworkId)
        assertFalse(result.toString().contains("PRIVATE_BODY"))
    }

    @Test fun resourceFolderRejectsPathsAndUncachedObjects() = runBlocking {
        val gateway = Fake()
        val tool = AppNavigationAgentTool(gateway)
        assertEquals("invalid_arguments", tool.execute("", parseToolArguments("""{"page":"course_resources","courseId":"2","folderId":"../1"}""")).output["error"]!!.jsonPrimitive.content)
        assertEquals("navigation_target_missing", tool.execute("", parseToolArguments("""{"page":"course_resources","courseId":"3","folderId":"4"}""")).output["error"]!!.jsonPrimitive.content)
        assertTrue(tool.execute("", parseToolArguments("""{"page":"course_resources","courseId":"2","folderId":"4"}""")).output["ok"]!!.jsonPrimitive.boolean)
        assertEquals("4", gateway.target!!.folderId)
    }

    @Test fun receiptMustBeReleasedBeforeUiCanConsumeAndCannotReplayNavigation() {
        val requests = AgentNavigationRequests()
        val id = requests.prepare(AgentPageTarget("calendar", ModuleKeys.Calendar))
        assertNull(requests.requests.value)
        requests.dispatch("wrong")
        assertNull(requests.requests.value)
        requests.dispatch(id)
        assertEquals(id, requests.requests.value!!.id)
        requests.consume("wrong")
        assertNotNull(requests.requests.value)
        requests.consume(id)
        requests.dispatch(id)
        assertNull(requests.requests.value)
    }

    private class Fake : AgentPageGateway {
        var target: AgentPageTarget? = null
        override suspend fun homework(courseId: Int, homeworkId: Int) = if (courseId == 2 && homeworkId == 1)
            HomeworkItem(1, "Example", 2, title = "Example", status = "open", subType = 0, requirementText = "PRIVATE_BODY") else null
        override suspend fun resourceFolderExists(courseId: String, folderId: String) = courseId == "2" && folderId == "4"
        override fun prepare(target: AgentPageTarget): String { this.target = target; return "request-1" }
    }
}
