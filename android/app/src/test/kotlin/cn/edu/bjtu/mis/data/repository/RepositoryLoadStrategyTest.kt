package cn.edu.bjtu.mis.data.repository

import android.content.ContextWrapper
import cn.edu.bjtu.mis.data.agent.tools.RepositoryAcademicAgentGateway
import cn.edu.bjtu.mis.data.agent.tools.RepositoryAgentPageGateway
import cn.edu.bjtu.mis.data.agent.tools.AgentNavigationRequests
import cn.edu.bjtu.mis.model.HomeworkAttachment
import cn.edu.bjtu.mis.model.TimetableData
import cn.edu.bjtu.mis.model.CourseEntry
import cn.edu.bjtu.mis.model.UserCourseDraft
import cn.edu.bjtu.mis.model.UserTodoDraft
import cn.edu.bjtu.mis.model.UserCourseDurationType
import java.time.LocalDate
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.captcha.CaptchaAnswerSolver
import cn.edu.bjtu.mis.data.captcha.CaptchaSolveResult
import cn.edu.bjtu.mis.data.db.BjtuMisDao
import cn.edu.bjtu.mis.data.db.MailFolderEntity
import cn.edu.bjtu.mis.data.db.MailMessageSummaryEntity
import cn.edu.bjtu.mis.data.db.ModuleSnapshotEntity
import cn.edu.bjtu.mis.data.db.ModuleUpdateSummaryEntity
import cn.edu.bjtu.mis.data.db.SyncRunEntity
import cn.edu.bjtu.mis.data.db.UserCourseEntity
import cn.edu.bjtu.mis.data.db.UserTodoEntity
import cn.edu.bjtu.mis.data.network.AppCookieJar
import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import cn.edu.bjtu.mis.data.provider.SessionEndpoints
import cn.edu.bjtu.mis.data.provider.EmploymentConsultationProvider
import cn.edu.bjtu.mis.data.provider.SessionManager
import cn.edu.bjtu.mis.data.security.CredentialStore
import cn.edu.bjtu.mis.data.security.LoginCredentials
import cn.edu.bjtu.mis.data.security.SessionCookieStore
import cn.edu.bjtu.mis.model.CalendarData
import cn.edu.bjtu.mis.model.EmptyRoomData
import cn.edu.bjtu.mis.model.EmptyRoomRow
import cn.edu.bjtu.mis.model.EmploymentFilterOptions
import cn.edu.bjtu.mis.model.EmploymentConsultationData
import cn.edu.bjtu.mis.model.CoverageLevel
import cn.edu.bjtu.mis.model.CourseResourcesData
import cn.edu.bjtu.mis.model.HomeworkData
import cn.edu.bjtu.mis.model.HomeworkItem
import cn.edu.bjtu.mis.model.MailFoldersData
import cn.edu.bjtu.mis.model.MailMessagesData
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.model.ModuleKeys
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryLoadStrategyTest {
    @Test
    fun nativeTodosUseConditionalWritesAndCleanRemindersForEveryMutation() = runBlocking {
        MockWebServer().use { server ->
            val revoked = mutableListOf<Long>()
            val manager = sessionManager(server)
            val sync = SyncRepository(FakeBjtuMisDao(), manager, beforeTodoChange = { revoked += it })
            val modules = ModuleRepository(sync, manager)
            val id = modules.saveUserTodo(UserTodoDraft(title = "Example", date = "2026-10-13", note = "Note"))
            val first = modules.userTodos().single()
            assertTrue(revoked.isEmpty())
            modules.saveUserTodo(UserTodoDraft(id, "Edited", first.date, first.note), first.updatedAt)
            val second = modules.userTodos().single()
            assertTrue(first.updatedAt != second.updatedAt)
            assertEquals(first.createdAt, second.createdAt)
            try {
                modules.saveUserTodo(UserTodoDraft(id, "Stale", first.date), first.updatedAt)
                org.junit.Assert.fail("Expected conflict")
            } catch (error: TodoMutationException) { assertEquals("todo_conflict", error.code) }
            assertEquals(listOf(id), revoked)
            assertEquals("Edited", modules.userTodos().single().title)
            modules.setUserTodoDone(id, true)
            val done = modules.userTodos().single()
            assertTrue(done.done)
            assertTrue(done.updatedAt != second.updatedAt)
            try { modules.deleteUserTodo(id, second.updatedAt); org.junit.Assert.fail() }
            catch (error: TodoMutationException) { assertEquals("todo_conflict", error.code) }
            modules.deleteUserTodo(id, done.updatedAt)
            assertEquals(listOf(id, id, id), revoked)
            assertTrue(modules.userTodos().isEmpty())
            try { modules.saveUserTodo(UserTodoDraft(id, "Missing", first.date)); org.junit.Assert.fail() }
            catch (error: TodoMutationException) { assertEquals("todo_not_found", error.code) }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun reminderCleanupFailurePreventsNativeTodoMutation() = runBlocking {
        MockWebServer().use { server ->
            val manager = sessionManager(server)
            val sync = SyncRepository(FakeBjtuMisDao(), manager, beforeTodoChange = { error("cleanup unavailable") })
            val id = sync.saveUserTodo(UserTodoDraft(title = "Example", date = "2026-10-13"))
            try { sync.setUserTodoDone(id, true); org.junit.Assert.fail() } catch (_: IllegalStateException) { }
            assertFalse(sync.userTodos().single().done)
            try { sync.deleteUserTodo(id); org.junit.Assert.fail() } catch (_: IllegalStateException) { }
            assertEquals(1, sync.userTodos().size)
        }
    }

    @Test
    fun calendarKeepsNativeTodosVisibleWithoutCampusCacheOrNetwork() = runBlocking {
        MockWebServer().use { server ->
            val manager = sessionManager(server)
            val sync = SyncRepository(FakeBjtuMisDao(), manager)
            val modules = ModuleRepository(sync, manager)
            val id = modules.saveUserTodo(UserTodoDraft(title = "Example", date = "2026-10-13"))
            val dashboard = modules.calendarDashboard(ModuleLoadStrategy.CacheOnly)
            assertEquals(id, dashboard.todos.single().id)
            assertEquals(3, dashboard.campusErrors.size)
            assertEquals("native_local", dashboard.calendarEnvelope.sourceSystem)
            assertTrue(dashboard.calendarEnvelope.data.items.isEmpty())
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun agentNavigationRequiresExactCachedHomeworkAndResourceListingWithoutNetwork() = runBlocking {
        MockWebServer().use { server ->
            val manager = sessionManager(server)
            val sync = SyncRepository(FakeBjtuMisDao(), manager)
            val modules = ModuleRepository(sync, manager)
            val gateway = RepositoryAgentPageGateway(modules, AgentNavigationRequests())
            sync.saveSnapshot(ModuleKeys.Homework, ModuleEnvelope(
                module = ModuleKeys.Homework, sourceSystem = "test", coverage = CoverageLevel.Verified,
                data = HomeworkData(items = listOf(HomeworkItem(1, "Example", 2, title = "Example", status = "open", subType = 0))),
            ))
            assertEquals(1, gateway.homework(2, 1)?.homeworkId)
            org.junit.Assert.assertNull(gateway.homework(3, 1))
            sync.saveSnapshot(ModuleKeys.CourseResources, ModuleEnvelope(
                module = ModuleKeys.CourseResources, sourceSystem = "test", coverage = CoverageLevel.Verified,
                sourceParams = buildJsonObject {
                    put("requested_course_id", "2"); put("course_id", "2")
                    put("folder_id", "4"); put("category_key", "all")
                },
                data = CourseResourcesData(selectedCourseId = 2, folderId = "4"),
            ))
            assertTrue(gateway.resourceFolderExists("2", "4"))
            for ((courseId, folderId) in listOf("2" to "0", "3" to "4")) {
                try { gateway.resourceFolderExists(courseId, folderId); org.junit.Assert.fail() }
                catch (_: LocalSnapshotMissingException) { }
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun campusAgentCacheQueriesNeverRequestNetworkOrExposeRawHomeworkContent() = runBlocking {
        MockWebServer().use { server ->
            val manager = sessionManager(server)
            val sync = SyncRepository(FakeBjtuMisDao(), manager)
            val gateway = RepositoryAcademicAgentGateway(ModuleRepository(sync, manager), SessionRepository(manager))
            sync.saveSnapshot(ModuleKeys.Homework, ModuleEnvelope(
                module = ModuleKeys.Homework, sourceSystem = "VE", coverage = CoverageLevel.Verified,
                syncedAt = "2026-10-12T03:00:00Z",
                data = HomeworkData(items = listOf(HomeworkItem(
                    homeworkId = 1, courseId = 2, course = "Example course", title = "Example homework",
                    contentExcerpt = "UNTRUSTED_BODY_SENTINEL", requirementText = "PRIVATE_REQUIREMENTS_SENTINEL",
                    dueAt = "2026-10-13 12:00:00", status = "open", subType = 0,
                    attachments = listOf(HomeworkAttachment("attachment-1", "example.pdf", "https://example.invalid/private")),
                ))),
            ))
            val section = gateway.load(ModuleKeys.Homework, LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-18"), false)
            assertTrue(section.fromCache)
            assertEquals("2026-10-12T03:00:00Z", section.syncedAt)
            assertEquals("2026-10-13", section.items.single()["date"]!!.jsonPrimitive.content)
            assertFalse(section.items.toString().contains("SENTINEL"))
            assertFalse(section.items.toString().contains("https://"))
            assertEquals(0, server.requestCount)
            val status = gateway.status()
            assertEquals(4, status["modules"]!!.jsonArray.size)
            assertFalse(status.containsKey("detail"))
            assertFalse(status.toString().contains("payload"))
        }
    }

    @Test
    fun campusAgentTimetableRetainsNativeCustomCoursesAndOneBasedWeekdays() = runBlocking {
        MockWebServer().use { server ->
            val manager = sessionManager(server)
            val sync = SyncRepository(FakeBjtuMisDao(), manager)
            sync.saveSnapshot(ModuleKeys.Timetable, ModuleEnvelope(
                module = ModuleKeys.Timetable, sourceSystem = "AA", coverage = CoverageLevel.Verified,
                data = TimetableData(entries = listOf(CourseEntry("周二", "1", courseCode = "example", courseName = "Campus course"))),
            ))
            sync.saveUserCourse(UserCourseDraft(
                courseName = "Local course", weekday = "周一", weekdayIndex = 0,
                period = "1", periodNumber = 1, startWeek = 1, endWeek = 16,
                durationType = UserCourseDurationType.LongTerm,
            ))
            val gateway = RepositoryAcademicAgentGateway(ModuleRepository(sync, manager), SessionRepository(manager))
            val section = gateway.load(ModuleKeys.Timetable, LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-18"), false)
            assertEquals(2, section.items.size)
            assertEquals(2, section.items.first()["weekdayIndex"]!!.jsonPrimitive.int)
            assertEquals(1, section.items.last()["weekdayIndex"]!!.jsonPrimitive.int)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun emptyRoomCacheRequiresTheSameBuildingWeekAndRoomFilters() = runBlocking {
        MockWebServer().use { server ->
            val sync = SyncRepository(FakeBjtuMisDao(), sessionManager(server))
            val repository = ModuleRepository(sync, sessionManager(server))
            val envelope = ModuleEnvelope(
                module = ModuleKeys.EmptyRooms, sourceSystem = "test", coverage = CoverageLevel.Verified,
                sourceParams = buildJsonObject {
                    put("week", "8")
                    put("building", "SX")
                    put("room", "")
                },
                data = EmptyRoomData(rooms = listOf(EmptyRoomRow("SX101"))),
            )
            sync.saveSnapshot(ModuleKeys.EmptyRooms, envelope)

            assertEquals(envelope.data, repository.emptyRooms(
                week = "8", building = "SX", room = "", strategy = ModuleLoadStrategy.CacheOnly,
            ).data)
            for ((week, building, room) in listOf(
                Triple("8", "SY", ""), Triple("8", "", ""), Triple("9", "SX", ""), Triple("8", "SX", "101"),
            )) {
                val error = runCatching {
                    repository.emptyRooms(week = week, building = building, room = room, strategy = ModuleLoadStrategy.CacheOnly)
                }.exceptionOrNull()
                assertTrue(error is LocalSnapshotMissingException)
            }
            sync.saveSnapshot(ModuleKeys.EmptyRooms, envelope.copy(sourceParams = buildJsonObject {
                put("week", "8")
                put("building", "")
                put("room", "")
            }))
            assertEquals(envelope.data, repository.emptyRooms(
                week = "8", building = "", room = "", strategy = ModuleLoadStrategy.CacheOnly,
            ).data)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun employmentStrategiesShareCacheAndNetworkFallback() = runBlocking {
        MockWebServer().use { server ->
            val sync = SyncRepository(FakeBjtuMisDao(), sessionManager(server))
            val repository = EmploymentConsultationRepository(
                sync, EmploymentConsultationProvider(BjtuHttpClient(AppCookieJar()), server.url("/").toString().trimEnd('/')),
            )
            val home = ModuleEnvelope(
                module = ModuleKeys.EmploymentConsultation, sourceSystem = "test",
                coverage = CoverageLevel.Verified,
                data = EmploymentConsultationData(appointmentUrl = "https://example.com/appointment", sourceUrl = "https://example.com"),
            )
            sync.saveSnapshot(ModuleKeys.EmploymentConsultation, home)
            assertEquals(home.data, repository.home(ModuleLoadStrategy.CacheOnly).data)
            assertEquals(home.data, repository.home(ModuleLoadStrategy.CacheFirst).data)
            val missing = runCatching { repository.filterOptions(ModuleLoadStrategy.CacheOnly) }.exceptionOrNull()
            assertTrue(missing is LocalSnapshotMissingException)
            assertEquals(0, server.requestCount)

            server.enqueue(MockResponse().setResponseCode(503))
            assertEquals(home.data, repository.home(ModuleLoadStrategy.NetworkFirst).data)
            assertEquals(1, server.requestCount)

            val filtersKey = "${ModuleKeys.EmploymentConsultation}:filters"
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"state":1,"data":"TOKEN"}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"state":1,"data":{}}"""))
            val filters = repository.filterOptions(ModuleLoadStrategy.CacheFirst)
            assertEquals(3, server.requestCount)
            assertEquals(filters, sync.snapshot<EmploymentFilterOptions>(filtersKey))
            assertEquals(filters, repository.filterOptions(ModuleLoadStrategy.CacheOnly))
            assertEquals(filters, repository.filterOptions(ModuleLoadStrategy.CacheFirst))
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun calendarDashboardCombinesSnapshotsAndKeepsMissingOptionalModulesEmpty() = runBlocking {
        MockWebServer().use { server ->
            val sync = SyncRepository(FakeBjtuMisDao(), sessionManager(server))
            sync.saveSnapshot(ModuleKeys.Calendar, ModuleEnvelope(
                module = ModuleKeys.Calendar, sourceSystem = "test", coverage = CoverageLevel.Verified,
                sourceParams = buildJsonObject { put("month", java.time.YearMonth.now().toString()) },
                data = CalendarData(month = java.time.YearMonth.now().toString(), currentWeek = "8"),
            ))
            val repository = ModuleRepository(sync, sessionManager(server))
            val empty = repository.calendarDashboard(ModuleLoadStrategy.CacheOnly)
            assertEquals("8", empty.calendarEnvelope.data.currentWeek)
            assertTrue(empty.homework.isEmpty())
            assertTrue(empty.exams.isEmpty())
            val item = homework(1, "Lab", "2026-06-30 23:59")
            sync.saveSnapshot(ModuleKeys.Homework, homeworkEnvelope(item))
            val populated = repository.calendarDashboard(ModuleLoadStrategy.CacheOnly)
            assertEquals(listOf(item), populated.homework)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun saveSnapshotStoresAndReplacesUpdateSummary() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeBjtuMisDao()
            val syncRepository = SyncRepository(dao, sessionManager(server))
            val baseline = homeworkEnvelope(homework(1, "Lab", dueAt = "2026-06-30 23:59"))
            val changed = homeworkEnvelope(homework(1, "Lab", dueAt = "2026-07-01 23:59"))
            val unchanged = homeworkEnvelope(homework(1, "Lab", dueAt = "2026-07-01 23:59"))

            syncRepository.saveSnapshot(ModuleKeys.Homework, baseline)
            assertTrue(syncRepository.updateSummaries().single().items.isEmpty())

            syncRepository.saveSnapshot(ModuleKeys.Homework, changed)
            val changedSummary = syncRepository.updateSummaries().single()
            assertEquals(ModuleKeys.Homework, changedSummary.moduleKey)
            assertEquals(listOf("modified"), changedSummary.items.map { it.changeType })

            syncRepository.saveSnapshot(ModuleKeys.Homework, unchanged)
            assertTrue(syncRepository.updateSummaries().single().items.isEmpty())
        }
    }

    @Test
    fun cacheOnlyReturnsModuleSnapshotWithoutNetwork() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeBjtuMisDao()
            val syncRepository = SyncRepository(dao, sessionManager(server))
            syncRepository.saveSnapshot(
                ModuleKeys.Calendar,
                ModuleEnvelope(
                    module = ModuleKeys.Calendar,
                    sourceSystem = "test",
                    coverage = CoverageLevel.Verified,
                    sourceParams = buildJsonObject { put("month", "2026-06") },
                    data = CalendarData(month = "2026-06", currentWeek = "8"),
                ),
            )

            val repository = ModuleRepository(syncRepository, sessionManager(server))
            val envelope = repository.calendar(month = "2026-06", strategy = ModuleLoadStrategy.CacheOnly)

            assertEquals("8", envelope.data.currentWeek)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun cacheFirstReturnsModuleSnapshotWithoutNetworkWhenCacheExists() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeBjtuMisDao()
            val syncRepository = SyncRepository(dao, sessionManager(server))
            syncRepository.saveSnapshot(
                ModuleKeys.Calendar,
                ModuleEnvelope(
                    module = ModuleKeys.Calendar,
                    sourceSystem = "test",
                    coverage = CoverageLevel.Verified,
                    sourceParams = buildJsonObject { put("month", "2026-06") },
                    data = CalendarData(month = "2026-06", currentWeek = "8"),
                ),
            )

            val repository = ModuleRepository(syncRepository, sessionManager(server))
            val envelope = repository.calendar(month = "2026-06", strategy = ModuleLoadStrategy.CacheFirst)

            assertEquals("8", envelope.data.currentWeek)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun cacheOnlyDoesNotReturnSnapshotWhenRequestParamsDiffer() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeBjtuMisDao()
            val syncRepository = SyncRepository(dao, sessionManager(server))
            syncRepository.saveSnapshot(
                ModuleKeys.Calendar,
                ModuleEnvelope(
                    module = ModuleKeys.Calendar,
                    sourceSystem = "test",
                    coverage = CoverageLevel.Verified,
                    sourceParams = buildJsonObject { put("month", "2026-06") },
                    data = CalendarData(month = "2026-06", currentWeek = "8"),
                ),
            )

            val repository = ModuleRepository(syncRepository, sessionManager(server))
            val error = runCatching {
                repository.calendar(month = "2026-07", strategy = ModuleLoadStrategy.CacheOnly)
            }.exceptionOrNull()

            assertTrue(error is LocalSnapshotMissingException)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun historyScoresDefaultDoesNotReturnSpecificTermSnapshot() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeBjtuMisDao()
            val syncRepository = SyncRepository(dao, sessionManager(server))
            syncRepository.saveSnapshot(
                ModuleKeys.HistoryScores,
                ModuleEnvelope(
                    module = ModuleKeys.HistoryScores,
                    sourceSystem = "test",
                    coverage = CoverageLevel.Verified,
                    sourceParams = buildJsonObject {
                        put("term", "2025-2026-2-2")
                        put("ctype", "ln")
                    },
                    data = cn.edu.bjtu.mis.model.ScoreData(currentTerm = "2025-2026-2-2"),
                ),
            )

            val repository = ModuleRepository(syncRepository, sessionManager(server))
            val error = runCatching {
                repository.historyScores(strategy = ModuleLoadStrategy.CacheOnly)
            }.exceptionOrNull()

            assertTrue(error is LocalSnapshotMissingException)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun courseResourcesDefaultDoesNotReturnSpecificCourseSnapshot() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeBjtuMisDao()
            val syncRepository = SyncRepository(dao, sessionManager(server))
            syncRepository.saveSnapshot(
                ModuleKeys.CourseResources,
                ModuleEnvelope(
                    module = ModuleKeys.CourseResources,
                    sourceSystem = "test",
                    coverage = CoverageLevel.Verified,
                    sourceParams = buildJsonObject {
                        put("requested_course_id", "42")
                        put("course_id", "42")
                        put("folder_id", "0")
                    },
                    data = CourseResourcesData(),
                ),
            )

            val repository = ModuleRepository(syncRepository, sessionManager(server))
            val error = runCatching {
                repository.courseResources(strategy = ModuleLoadStrategy.CacheOnly)
            }.exceptionOrNull()

            assertTrue(error is LocalSnapshotMissingException)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun cacheOnlyMissingModuleSnapshotDoesNotUseNetwork() = runBlocking {
        MockWebServer().use { server ->
            val syncRepository = SyncRepository(FakeBjtuMisDao(), sessionManager(server))
            val repository = ModuleRepository(syncRepository, sessionManager(server))

            val error = runCatching {
                repository.calendar(strategy = ModuleLoadStrategy.CacheOnly)
            }.exceptionOrNull()

            assertTrue(error is LocalSnapshotMissingException)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun progressiveCacheOnlyEmitsCachedStateOnly() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeBjtuMisDao()
            val syncRepository = SyncRepository(dao, sessionManager(server))
            syncRepository.saveSnapshot(
                ModuleKeys.Homework,
                ModuleEnvelope(
                    module = ModuleKeys.Homework,
                    sourceSystem = "test",
                    coverage = CoverageLevel.Verified,
                    data = HomeworkData(),
                ),
            )

            val repository = ModuleRepository(syncRepository, sessionManager(server))
            val states = repository.homeworkProgressive(strategy = ModuleLoadStrategy.CacheOnly).toList()

            assertEquals(1, states.size)
            assertTrue(states.single().complete)
            assertTrue(states.single().fromCache)
            assertFalse(states.single().loading)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun mailCacheOnlyReadsCachedFoldersAndMessagesWithoutNetwork() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeBjtuMisDao()
            dao.saveMailFolders(
                listOf(
                    MailFolderEntity(
                        folderId = "1",
                        name = "Inbox",
                        messageCount = 1,
                        unreadCount = 1,
                        syncedAt = "2026-06-16T00:00:00Z",
                    ),
                ),
            )
            dao.saveMailMessageSummaries(
                listOf(
                    MailMessageSummaryEntity(
                        messageId = "m1",
                        folderId = "1",
                        subject = "Cached mail",
                        fromText = "sender",
                        syncedAt = "2026-06-16T00:00:00Z",
                    ),
                ),
            )

            val repository = MailRepository(
                context = ContextWrapper(null),
                dao = dao,
                sessionManager = sessionManager(server),
            )

            val folders = repository.folders(ModuleLoadStrategy.CacheOnly)
            val messages = repository.messages(strategy = ModuleLoadStrategy.CacheOnly)

            assertEquals(1, folders.data.folders.size)
            assertEquals("Cached mail", messages.data.messages.single().subject)
            assertEquals(0, server.requestCount)
        }
    }

    private fun sessionManager(server: MockWebServer): SessionManager {
        val base = server.url("/").toString().trimEnd('/')
        val cookieJar = AppCookieJar()
        return SessionManager(
            cookieStore = MemoryCookieStore(),
            credentialStore = MemoryCredentialStore(),
            cookieJar = cookieJar,
            httpClient = BjtuHttpClient(cookieJar),
            captchaSolver = FakeCaptchaSolver(),
            endpoints = SessionEndpoints(
                misHomeUrl = "$base/home/",
                misAaBridgeUrl = "$base/module/module/10/",
                aaTimetableUrl = "$base/aa/timetable/",
                bksyVeBridgeUrl = "$base/ve/bridge/",
                casOrigin = base,
                misReferer = "$base/",
                bksyReferer = "$base/",
            ),
        )
    }

    private class MemoryCookieStore : SessionCookieStore {
        private var payload: String? = null

        override fun save(plainText: String) {
            payload = plainText
        }

        override fun load(): String? = payload

        override fun clear() {
            payload = null
        }
    }

    private class MemoryCredentialStore : CredentialStore {
        private var credentials: LoginCredentials? = null

        override fun save(credentials: LoginCredentials) {
            this.credentials = credentials
        }

        override fun load(): LoginCredentials? = credentials

        override fun clear() {
            credentials = null
        }
    }

    private class FakeCaptchaSolver : CaptchaAnswerSolver {
        override fun solve(imageBytes: ByteArray): CaptchaSolveResult =
            CaptchaSolveResult(expression = "1+1=", answer = "2")
    }

    private fun homeworkEnvelope(item: HomeworkItem): ModuleEnvelope<HomeworkData> =
        ModuleEnvelope(
            module = ModuleKeys.Homework,
            syncedAt = "2026-06-23T10:00:00Z",
            sourceSystem = "test",
            coverage = CoverageLevel.Verified,
            data = HomeworkData(currentTerm = "2025-2026-2-2", items = listOf(item)),
            sourceParams = buildJsonObject {
                put("term", "2025-2026-2-2")
            },
        )

    private fun homework(id: Int, title: String, dueAt: String): HomeworkItem =
        HomeworkItem(
            homeworkId = id,
            course = "Software",
            courseId = 10,
            title = title,
            openedAt = "2026-06-20 08:00",
            dueAt = dueAt,
            status = "open",
            subType = 0,
        )

    private class FakeBjtuMisDao : BjtuMisDao {
        private val snapshots = linkedMapOf<String, ModuleSnapshotEntity>()
        private val updateSummaries = linkedMapOf<String, ModuleUpdateSummaryEntity>()
        private val mailFolders = linkedMapOf<String, MailFolderEntity>()
        private val mailMessages = linkedMapOf<String, MailMessageSummaryEntity>()
        private val userCourses = linkedMapOf<Long, UserCourseEntity>()
        private val userTodos = linkedMapOf<Long, UserTodoEntity>()
        private var nextSyncRunId = 1L
        private var latestSyncRun: SyncRunEntity? = null

        override suspend fun insertSyncRun(entity: SyncRunEntity): Long {
            val id = nextSyncRunId++
            latestSyncRun = entity.copy(id = id)
            return id
        }

        override suspend fun finishSyncRun(
            id: Long,
            finishedAt: String,
            status: String,
            moduleSummaryJson: String,
            errorText: String?,
        ) {
            latestSyncRun = latestSyncRun?.copy(
                id = id,
                finishedAt = finishedAt,
                status = status,
                moduleSummaryJson = moduleSummaryJson,
                errorText = errorText,
            )
        }

        override suspend fun saveSnapshot(entity: ModuleSnapshotEntity) {
            snapshots[entity.moduleKey] = entity
        }

        override suspend fun getSnapshot(moduleKey: String): ModuleSnapshotEntity? = snapshots[moduleKey]

        override suspend fun getSnapshots(): List<ModuleSnapshotEntity> = snapshots.values.toList()

        override suspend fun saveModuleUpdateSummary(entity: ModuleUpdateSummaryEntity) {
            updateSummaries[entity.moduleKey] = entity
        }

        override suspend fun getModuleUpdateSummary(moduleKey: String): ModuleUpdateSummaryEntity? =
            updateSummaries[moduleKey]

        override suspend fun getModuleUpdateSummaries(): List<ModuleUpdateSummaryEntity> =
            updateSummaries.values.toList()

        override suspend fun saveUserCourse(entity: UserCourseEntity): Long {
            val id = entity.id.takeIf { it != 0L } ?: ((userCourses.keys.maxOrNull() ?: 0L) + 1L)
            userCourses[id] = entity.copy(id = id)
            return id
        }

        override suspend fun getUserCourse(id: Long): UserCourseEntity? = userCourses[id]

        override suspend fun getUserCourses(): List<UserCourseEntity> = userCourses.values.toList()

        override suspend fun deleteUserCourse(id: Long) {
            userCourses.remove(id)
        }

        override suspend fun saveUserTodo(entity: UserTodoEntity): Long {
            val id = entity.id.takeIf { it != 0L } ?: ((userTodos.keys.maxOrNull() ?: 0L) + 1L)
            userTodos[id] = entity.copy(id = id)
            return id
        }

        override suspend fun getUserTodo(id: Long): UserTodoEntity? = userTodos[id]

        override suspend fun getUserTodos(): List<UserTodoEntity> = userTodos.values.toList()

        override suspend fun setUserTodoDone(id: Long, done: Boolean, updatedAt: String) {
            userTodos[id]?.let { userTodos[id] = it.copy(done = done, updatedAt = updatedAt) }
        }

        override suspend fun deleteUserTodo(id: Long) {
            userTodos.remove(id)
        }

        override suspend fun saveMailFolders(folders: List<MailFolderEntity>) {
            folders.forEach { mailFolders[it.folderId] = it }
        }

        override suspend fun getMailFolders(): List<MailFolderEntity> =
            mailFolders.values.sortedWith(compareByDescending<MailFolderEntity> { it.system }.thenBy { it.folderId })

        override suspend fun clearMailFolders() {
            mailFolders.clear()
        }

        override suspend fun saveMailMessageSummaries(messages: List<MailMessageSummaryEntity>) {
            messages.forEach { mailMessages[it.messageId] = it }
        }

        override suspend fun clearMailMessageSummaries(folderId: String) {
            mailMessages.values.removeAll { it.folderId == folderId }
        }

        override suspend fun deleteMailMessageSummaries(messageIds: List<String>) {
            messageIds.forEach { mailMessages.remove(it) }
        }

        override suspend fun getMailMessageSummariesByIds(messageIds: List<String>): List<MailMessageSummaryEntity> =
            messageIds.mapNotNull { mailMessages[it] }

        override suspend fun markMailMessageSummariesRead(messageIds: List<String>) {
            messageIds.forEach { id ->
                mailMessages[id]?.let { mailMessages[id] = it.copy(read = true) }
            }
        }

        override suspend fun decrementMailFolderUnreadCount(folderId: String, delta: Int) {
            mailFolders[folderId]?.let {
                mailFolders[folderId] = it.copy(unreadCount = (it.unreadCount - delta).coerceAtLeast(0))
            }
        }

        override suspend fun getMailMessageSummaries(folderId: String, start: Int, limit: Int): List<MailMessageSummaryEntity> =
            mailMessages.values
                .filter { it.folderId == folderId }
                .sortedWith(compareByDescending<MailMessageSummaryEntity> {
                    it.receivedAt ?: it.sentAt ?: it.modifiedAt ?: ""
                }.thenByDescending { it.messageId })
                .drop(start)
                .take(limit)

        override suspend fun countMailMessageSummaries(folderId: String): Int =
            mailMessages.values.count { it.folderId == folderId }

        override suspend fun getLatestSyncRun(): SyncRunEntity? = latestSyncRun

        override suspend fun clearSnapshots() {
            snapshots.clear()
        }

        override suspend fun clearSyncRuns() {
            latestSyncRun = null
        }
    }
}
