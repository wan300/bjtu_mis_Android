package cn.edu.bjtu.mis.data.agent.tools

import cn.edu.bjtu.mis.data.repository.LocalSnapshotMissingException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AcademicAgentToolTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-12T04:00:00Z"), ZoneId.of("Asia/Shanghai"))
    private class FakeGateway : AcademicAgentGateway {
        val requests = mutableListOf<Triple<String, LocalDate, Boolean>>()
        val sections = mutableMapOf<String, CampusSection>()
        val failures = mutableMapOf<String, Exception>()
        override suspend fun status() = buildJsonObject { put("sessionState", "ready") }
        override suspend fun load(module: String, start: LocalDate, end: LocalDate, refresh: Boolean): CampusSection {
            requests += Triple(module, start, refresh)
            failures[module]?.let { throw it }
            return sections[module] ?: CampusSection(emptyList(), "AA", "2026-10-12T03:00:00Z", !refresh)
        }
    }
    private fun item(date: String? = null, title: String = "test") = buildJsonObject {
        date?.let { put("date", it) }; put("title", title)
    }
    private suspend fun query(gateway: FakeGateway, kind: String = "agenda", args: JsonObject = buildJsonObject {}) =
        AcademicAgentTool(gateway, clock).tools().single { it.name == "academic.$kind" }.execute("", args).output

    @Test fun defaultWeekUsesCampusTimezoneAndCacheOnlyWithoutWorkspace() = runBlocking {
        val gateway = FakeGateway()
        val result = query(gateway)
        assertEquals("2026-10-12", result["meta"]!!.jsonObject["startDate"]!!.jsonPrimitive.content)
        assertEquals("2026-10-18", result["meta"]!!.jsonObject["endDate"]!!.jsonPrimitive.content)
        assertTrue(gateway.requests.all { !it.third })
        assertEquals(setOf("timetable", "exams", "homework", "calendar", "todos"), gateway.requests.map { it.first }.toSet())
        assertTrue(AcademicAgentTool(gateway).tools().all { !it.requiresWorkspace })
    }

    @Test fun missingCacheIsNotAnEmptyListAndPartialSuccessIsPreserved() = runBlocking {
        val gateway = FakeGateway()
        gateway.failures["homework"] = LocalSnapshotMissingException("homework")
        gateway.failures["exams"] = IllegalStateException("secret upstream URL or token")
        val result = query(gateway)
        val sections = result["data"]!!.jsonObject
        assertEquals("cache_missing", sections["homework"]!!.jsonObject["error"]!!.jsonPrimitive.content)
        assertFalse(sections["homework"]!!.jsonObject.containsKey("items"))
        assertTrue(sections["timetable"]!!.jsonObject["ok"]!!.jsonPrimitive.boolean)
        assertTrue(result["meta"]!!.jsonObject["partial"]!!.jsonPrimitive.boolean)
        assertFalse(result.toString().contains("secret"))
        gateway.failures.keys.toList().forEach { gateway.failures[it] = IllegalStateException() }
    }

    @Test fun filtersDatesRetainsUnknownDatesAndReportsTruncation() = runBlocking {
        val gateway = FakeGateway()
        gateway.sections["homework"] = CampusSection(listOf(item("2026-10-12"), item("2026-11-01"), item()), "VE", "2026-10-01T00:00:00Z", true)
        val result = query(gateway, "homework", buildJsonObject { put("limit", 1) })
        val section = result["data"]!!.jsonObject["homework"]!!.jsonObject
        val meta = section["meta"]!!.jsonObject
        assertEquals(1, section["items"]!!.jsonArray.size)
        assertEquals(2, meta["matchedCount"]!!.jsonPrimitive.int)
        assertEquals(1, meta["unresolvedDateCount"]!!.jsonPrimitive.int)
        assertTrue(meta["truncated"]!!.jsonPrimitive.boolean)
        assertTrue(meta["stale"]!!.jsonPrimitive.boolean)
    }

    @Test fun refreshFallbackIsNeverAdvertisedAsFreshNetworkData() = runBlocking {
        val gateway = FakeGateway()
        gateway.sections["exams"] = CampusSection(emptyList(), "AA", "2026-10-12T03:00:00Z", true)
        val result = query(gateway, "exams", buildJsonObject { put("refresh", true) })
        val meta = result["data"]!!.jsonObject["exams"]!!.jsonObject["meta"]!!.jsonObject
        assertTrue(gateway.requests.single().third)
        assertTrue(meta["fromCache"]!!.jsonPrimitive.boolean)
        assertTrue(meta["refreshFailed"]!!.jsonPrimitive.boolean)
    }

    @Test fun timetableFiltersWeekdayAndTeachingWeekWithoutInventingClassDates() = runBlocking {
        val gateway = FakeGateway()
        gateway.sections["timetable"] = CampusSection(listOf(
            buildJsonObject { put("weekdayIndex", 1); put("weeks", "1-16周(单)") },
            buildJsonObject { put("weekdayIndex", 2); put("weeks", "1-16周") },
            buildJsonObject { put("weekdayIndex", 1); put("weeks", "2-16周(双)") },
        ), "AA", "2026-10-12T03:00:00Z", true)
        val result = query(gateway, "timetable", buildJsonObject {
            put("startDate", "2026-10-12"); put("endDate", "2026-10-12"); put("week", 7)
        })
        val section = result["data"]!!.jsonObject["timetable"]!!.jsonObject
        assertEquals(1, section["items"]!!.jsonArray.size)
        assertFalse(section["items"]!!.jsonArray[0].jsonObject.containsKey("date"))
        assertTrue(section["meta"]!!.jsonObject["partial"]!!.jsonPrimitive.boolean)
    }

    @Test fun invalidWindowsFailBeforeRepositoryAccessAndCancellationPropagates() = runBlocking {
        val gateway = FakeGateway()
        for (args in listOf(buildJsonObject { put("startDate", "invalid") },
            buildJsonObject { put("startDate", "2026-10-12"); put("endDate", "2026-11-12") },
            buildJsonObject { put("startDate", "2026-10-12"); put("endDate", "2026-10-11") })) {
            try { query(gateway, args = args); fail("Invalid window") } catch (error: AgentToolPolicyException) { assertEquals("invalid_arguments", error.code) }
        }
        assertTrue(gateway.requests.isEmpty())
        gateway.failures["timetable"] = CancellationException("cancelled")
        try { query(gateway); fail("Cancellation swallowed") } catch (_: CancellationException) { }
    }

    @Test fun aggregateLimitAndTotalFailureAreExplicit() = runBlocking {
        val gateway = FakeGateway()
        listOf("timetable", "exams", "homework", "calendar", "todos").forEach {
            gateway.sections[it] = CampusSection(List(5) { item("2026-10-12") }, "local", null, true)
        }
        val result = query(gateway, args = buildJsonObject { put("limit", 3) })
        assertEquals(3, result["data"]!!.jsonObject.values.sumOf { it.jsonObject["items"]!!.jsonArray.size })
        gateway.sections.keys.forEach { gateway.failures[it] = LocalSnapshotMissingException(it) }
        val failure = query(gateway)
        assertFalse(failure["ok"]!!.jsonPrimitive.boolean)
        assertTrue(failure["meta"]!!.jsonObject["partial"]!!.jsonPrimitive.boolean)
    }
}
