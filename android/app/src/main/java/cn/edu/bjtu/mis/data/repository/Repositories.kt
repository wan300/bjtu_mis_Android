package cn.edu.bjtu.mis.data.repository

import android.content.Context
import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.db.BjtuMisDao
import cn.edu.bjtu.mis.data.db.ModuleSnapshotEntity
import cn.edu.bjtu.mis.data.db.SyncRunEntity
import cn.edu.bjtu.mis.data.db.UserCourseEntity
import cn.edu.bjtu.mis.data.db.UserTodoEntity
import cn.edu.bjtu.mis.data.db.encodeSummary
import cn.edu.bjtu.mis.data.db.toEntity
import cn.edu.bjtu.mis.data.db.toModel
import cn.edu.bjtu.mis.data.db.toCourseEntry
import cn.edu.bjtu.mis.data.employment.EmploymentCalendarEvent
import cn.edu.bjtu.mis.data.employment.employmentCalendarEvents
import cn.edu.bjtu.mis.data.homework.homeworkMatchesStatusFilter
import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import cn.edu.bjtu.mis.data.network.SingleFlight
import cn.edu.bjtu.mis.data.perf.PerfTrace
import cn.edu.bjtu.mis.data.provider.AaProvider
import cn.edu.bjtu.mis.data.provider.CoremailProvider
import cn.edu.bjtu.mis.data.provider.EmploymentConsultationProvider
import cn.edu.bjtu.mis.data.provider.SessionExpiredException
import cn.edu.bjtu.mis.data.provider.SessionManager
import cn.edu.bjtu.mis.data.provider.SessionValidationPolicy
import cn.edu.bjtu.mis.data.provider.VeProvider
import cn.edu.bjtu.mis.data.provider.ZhixingProvider
import cn.edu.bjtu.mis.data.security.CredentialStore
import cn.edu.bjtu.mis.data.security.LoginCredentials
import cn.edu.bjtu.mis.model.AcademicProgressData
import cn.edu.bjtu.mis.model.AutoLoginResult
import cn.edu.bjtu.mis.model.CalendarData
import cn.edu.bjtu.mis.model.CoverageLevel
import cn.edu.bjtu.mis.model.CourseReplayData
import cn.edu.bjtu.mis.model.CourseReplayPlaybackInfo
import cn.edu.bjtu.mis.model.CourseResourceItem
import cn.edu.bjtu.mis.model.CourseSelectionAttemptResult
import cn.edu.bjtu.mis.model.CourseSelectionData
import cn.edu.bjtu.mis.model.CourseSelectionTarget
import cn.edu.bjtu.mis.model.CourseResourcesData
import cn.edu.bjtu.mis.model.EmptyRoomData
import cn.edu.bjtu.mis.model.EmploymentArticleDetail
import cn.edu.bjtu.mis.model.EmploymentConsultationData
import cn.edu.bjtu.mis.model.EmploymentFilterOption
import cn.edu.bjtu.mis.model.EmploymentFilterOptions
import cn.edu.bjtu.mis.model.EmploymentInfoDetail
import cn.edu.bjtu.mis.model.EmploymentInfoPage
import cn.edu.bjtu.mis.model.EmploymentInfoQuery
import cn.edu.bjtu.mis.model.EmploymentSectionType
import cn.edu.bjtu.mis.model.ExamData
import cn.edu.bjtu.mis.model.ExamItem
import cn.edu.bjtu.mis.model.HomeworkData
import cn.edu.bjtu.mis.model.HomeworkItem
import cn.edu.bjtu.mis.model.HomeworkSubmitResponse
import cn.edu.bjtu.mis.model.HomeworkUploadFile
import cn.edu.bjtu.mis.model.MailAttachmentUploadResponse
import cn.edu.bjtu.mis.model.MailComposeRequest
import cn.edu.bjtu.mis.model.MailComposeResponse
import cn.edu.bjtu.mis.model.MailContactsData
import cn.edu.bjtu.mis.model.MailDeleteResponse
import cn.edu.bjtu.mis.model.MailFoldersData
import cn.edu.bjtu.mis.model.MailMarkReadResponse
import cn.edu.bjtu.mis.model.MailMessageDetail
import cn.edu.bjtu.mis.model.MailMessagesData
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.model.ModuleKeys
import cn.edu.bjtu.mis.model.ProgressiveModuleState
import cn.edu.bjtu.mis.model.ScoreData
import cn.edu.bjtu.mis.model.ScoreDetailData
import cn.edu.bjtu.mis.model.SessionCaptcha
import cn.edu.bjtu.mis.model.SessionStatus
import cn.edu.bjtu.mis.model.StudentProfileData
import cn.edu.bjtu.mis.model.SyncModuleSummary
import cn.edu.bjtu.mis.model.SyncRun
import cn.edu.bjtu.mis.model.TeachingAssessmentData
import cn.edu.bjtu.mis.model.TeachingAssessmentForm
import cn.edu.bjtu.mis.model.TeachingAssessmentSubmitResult
import cn.edu.bjtu.mis.model.TimetableData
import cn.edu.bjtu.mis.model.UserCourseDraft
import cn.edu.bjtu.mis.model.UserTodoDraft
import cn.edu.bjtu.mis.model.UserTodoItem
import cn.edu.bjtu.mis.model.ZhixingAuthState
import cn.edu.bjtu.mis.model.ZhixingHomeData
import cn.edu.bjtu.mis.model.ZhixingLoginChallenge
import cn.edu.bjtu.mis.model.ZhixingLoginOutcome
import cn.edu.bjtu.mis.model.ZhixingLoginStatus
import cn.edu.bjtu.mis.model.ZhixingSearchData
import cn.edu.bjtu.mis.model.ZhixingThreadDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Cookie
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

enum class ModuleLoadStrategy {
    CacheOnly,
    CacheFirst,
    NetworkFirst,
}

class LocalSnapshotMissingException(moduleKey: String) : IllegalStateException(
    "暂无本地缓存，请手动刷新：$moduleKey"
)

class SessionRepository(
    private val sessionManager: SessionManager,
) {
    fun cachedStatus(): SessionStatus = sessionManager.cachedSessionStatus()

    suspend fun status(
        policy: SessionValidationPolicy = SessionValidationPolicy.Fresh,
    ): SessionStatus = sessionManager.validateSession(policy)

    suspend fun recoverSession(
        policy: SessionValidationPolicy = SessionValidationPolicy.UseRecentOrValidate,
    ): AutoLoginResult = sessionManager.recoverSession(policy)

    suspend fun captcha(): SessionCaptcha = sessionManager.fetchInlineLoginCaptcha()

    suspend fun login(loginName: String, password: String, captcha: String): SessionStatus =
        sessionManager.loginInline(loginName, password, captcha)

    suspend fun loginAuto(loginName: String? = null, password: String? = null): AutoLoginResult =
        sessionManager.loginAuto(loginName, password)

    fun logout() = sessionManager.logout()
}

class SyncRepository(
    @PublishedApi internal val dao: BjtuMisDao,
    private val sessionManager: SessionManager,
    private val beforeTodoChange: suspend (Long) -> Unit = {},
    private val todoMutex: Mutex = Mutex(),
) {
    private val mutex = Mutex()

    suspend fun runSync(): SyncRun = mutex.withLock {
        PerfTrace.measureSuspend("Sync.full") {
        val startedAt = nowIso()
        val runId = dao.insertSyncRun(SyncRunEntity(startedAt = startedAt, status = "running"))
        val summary = linkedMapOf<String, SyncModuleSummary>()
        val errors = mutableListOf<String>()

        try {
            sessionManager.withAuthenticatedClient { client ->
                val aa = AaProvider(client)
                val ve = VeProvider(client)

                val calendar = fetchAndStore(ModuleKeys.Calendar, summary, errors) { ve.fetchCalendar() }
                val currentWeek = calendar?.data?.currentWeek
                val currentTerm = calendar?.data?.currentTerm

                fetchAndStore(ModuleKeys.Profile, summary, errors) { aa.fetchStudentProfile() }
                fetchAndStore(ModuleKeys.Timetable, summary, errors) { aa.fetchTimetable() }
                fetchAndStore(ModuleKeys.Exams, summary, errors) { aa.fetchExams() }
                fetchAndStore(ModuleKeys.Scores, summary, errors) { aa.fetchScores(ctype = "lr") }
                fetchAndStore(ModuleKeys.HistoryScores, summary, errors) { aa.fetchHistoryScores() }
                fetchAndStore(ModuleKeys.AcademicProgress, summary, errors) { aa.fetchAcademicProgress() }
                fetchAndStore(ModuleKeys.Homework, summary, errors) {
                    ve.fetchHomework(term = currentTerm, includeAttachments = false)
                }
                summary[ModuleKeys.CourseResources] = SyncModuleSummary(
                    status = "skipped",
                    durationMs = 0,
                    error = "课程资源已改为按需刷新，避免全量同步耗时过长。",
                )
                fetchAndStore(ModuleKeys.EmptyRooms, summary, errors) { aa.fetchEmptyRooms(week = currentWeek) }
                fetchAndStore(ModuleKeys.Mail, summary, errors) {
                    val mail = CoremailProvider(client)
                    val folders = mail.fetchFolders().copy(syncedAt = nowIso())
                    dao.clearMailFolders()
                    dao.saveMailFolders(folders.data.folders.map { folder -> folder.toEntity(folders.syncedAt ?: nowIso()) })
                    val messages = mail.fetchMessages(folderId = "1", start = 0, limit = 20).copy(syncedAt = nowIso())
                    dao.clearMailMessageSummaries("1")
                    dao.saveMailMessageSummaries(messages.data.messages.map { message -> message.toEntity(messages.syncedAt ?: nowIso()) })
                    messages
                }
            }
        } catch (error: SessionExpiredException) {
            finish(runId, "session_expired", summary, error.message)
            throw error
        } catch (error: Throwable) {
            finish(runId, "failed", summary, error.message)
            throw error
        }

        val status = if (errors.isEmpty()) "success" else "partial_failure"
        finish(runId, status, summary, errors.joinToString(" | ").takeIf { it.isNotBlank() })
        latestStatus()
        }
    }

    suspend fun runQuickSync(): SyncRun = mutex.withLock {
        PerfTrace.measureSuspend("Sync.quick") {
            val startedAt = nowIso()
            val runId = dao.insertSyncRun(SyncRunEntity(startedAt = startedAt, status = "quick_running"))
            val summary = linkedMapOf<String, SyncModuleSummary>()
            val errors = mutableListOf<String>()

            try {
                sessionManager.withAuthenticatedClient { client ->
                    val aa = AaProvider(client)
                    val ve = VeProvider(client)

                    val calendar = fetchAndStore(ModuleKeys.Calendar, summary, errors) { ve.fetchCalendar() }
                    val currentTerm = calendar?.data?.currentTerm

                    fetchAndStore(ModuleKeys.Exams, summary, errors) { aa.fetchExams() }
                    fetchAndStore(ModuleKeys.Homework, summary, errors) {
                        ve.fetchHomework(term = currentTerm, includeAttachments = false)
                    }
                }
            } catch (error: SessionExpiredException) {
                finish(runId, "session_expired", summary, error.message)
                throw error
            } catch (error: Throwable) {
                finish(runId, "failed", summary, error.message)
                throw error
            }

            val status = if (errors.isEmpty()) "success" else "partial_failure"
            finish(runId, status, summary, errors.joinToString(" | ").takeIf { it.isNotBlank() })
            latestStatus()
        }
    }

    suspend fun latestStatus(): SyncRun =
        dao.getLatestSyncRun()?.toModel()
            ?: SyncRun(status = "idle")

    suspend fun snapshots(): List<ModuleSnapshotEntity> = dao.getSnapshots()

    suspend fun updateSummaries(): List<ModuleUpdateSummary> =
        dao.getModuleUpdateSummaries().map { it.toUpdateSummary() }

    @PublishedApi
    internal suspend inline fun <reified T> snapshot(moduleKey: String): ModuleEnvelope<T>? =
        dao.getSnapshot(moduleKey)?.payloadJson?.let { AppJson.decodeFromString<ModuleEnvelope<T>>(it) }

    private suspend inline fun <reified T> fetchAndStore(
        moduleKey: String,
        summary: MutableMap<String, SyncModuleSummary>,
        errors: MutableList<String>,
        crossinline fetcher: suspend () -> ModuleEnvelope<T>,
    ): ModuleEnvelope<T>? {
        val startedAt = PerfTrace.nowMillis()
        return runCatching {
            val envelope = fetcher().withSyncedAt(nowIso())
            saveSnapshot(moduleKey, envelope)
            val durationMs = PerfTrace.nowMillis() - startedAt
            summary[moduleKey] = SyncModuleSummary(
                status = "success",
                coverage = envelope.coverage,
                items = itemCount(envelope.data),
                durationMs = durationMs,
            )
            PerfTrace.mark("Sync.module.$moduleKey", "${durationMs}ms status=success")
            envelope
        }.getOrElse { error ->
            if (error is SessionExpiredException) throw error
            val durationMs = PerfTrace.nowMillis() - startedAt
            summary[moduleKey] = SyncModuleSummary(
                status = "error",
                durationMs = durationMs,
                error = error.message,
            )
            PerfTrace.mark("Sync.module.$moduleKey", "${durationMs}ms status=error")
            errors += "$moduleKey: ${error.message}"
            null
        }
    }

    @PublishedApi
    internal suspend inline fun <reified T> saveSnapshot(moduleKey: String, envelope: ModuleEnvelope<T>) {
        val syncedAt = envelope.syncedAt ?: nowIso()
        val payloadJson = AppJson.encodeToString(envelope)
        val oldSnapshot = dao.getSnapshot(moduleKey)
        buildModuleUpdateSummary(
            moduleKey = moduleKey,
            oldPayloadJson = oldSnapshot?.payloadJson,
            newPayloadJson = payloadJson,
            syncedAt = syncedAt,
        )?.let { summary ->
            dao.saveModuleUpdateSummary(summary.toEntity())
        }
        dao.saveSnapshot(
            ModuleSnapshotEntity(
                moduleKey = moduleKey,
                syncedAt = syncedAt,
                sourceSystem = envelope.sourceSystem,
                coverage = envelope.coverage.name.lowercase(),
                sourceParamsJson = envelope.sourceParams.toString(),
                payloadJson = payloadJson,
            )
        )
    }

    suspend fun userCourses(): List<UserCourseEntity> =
        dao.getUserCourses()

    suspend fun saveUserCourse(draft: UserCourseDraft): Long {
        val now = nowIso()
        val existing = draft.id?.let { dao.getUserCourse(it) }
        val startWeek = draft.startWeek.coerceAtLeast(1)
        val endWeek = draft.endWeek.coerceAtLeast(1)
        return dao.saveUserCourse(
            UserCourseEntity(
                id = draft.id ?: 0,
                courseName = draft.courseName.trim(),
                weekday = draft.weekday.trim(),
                weekdayIndex = draft.weekdayIndex.coerceIn(0, 6),
                period = draft.period.trim(),
                periodNumber = draft.periodNumber.coerceAtLeast(1),
                timeRange = draft.timeRange.blankToNull(),
                startWeek = minOf(startWeek, endWeek),
                endWeek = maxOf(startWeek, endWeek),
                weeksText = draft.weeksText.blankToNull(),
                durationType = draft.durationType.name,
                teacher = draft.teacher.blankToNull(),
                locationText = draft.locationText.blankToNull(),
                remark = draft.remark.blankToNull(),
                colorIndex = draft.colorIndex.coerceIn(0, 7),
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
        )
    }

    suspend fun deleteUserCourse(id: Long) {
        dao.deleteUserCourse(id)
    }

    suspend fun userTodos(): List<UserTodoItem> =
        dao.getUserTodos().map { it.toModel() }

    suspend fun saveUserTodo(draft: UserTodoDraft, expectedUpdatedAt: String? = null): Long = todoMutex.withLock {
        val existing = draft.id?.let { dao.getUserTodo(it) }
        val now = nextTodoVersion(existing?.updatedAt)
        if (draft.id != null && existing == null) throw TodoMutationException("todo_not_found", "待办不存在")
        if (expectedUpdatedAt != null && existing?.updatedAt != expectedUpdatedAt) {
            throw TodoMutationException("todo_conflict", "待办已修改，请重新查询")
        }
        val title = draft.title.trim()
        require(title.isNotBlank()) { "待办标题不能为空" }
        val date = LocalDate.parse(draft.date.trim()).toString()
        existing?.let { beforeTodoChange(it.id) }
        dao.saveUserTodo(
            UserTodoEntity(
                id = draft.id ?: 0,
                title = title,
                date = date,
                note = draft.note.blankToNull(),
                done = draft.done,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
        )
    }

    suspend fun setUserTodoDone(id: Long, done: Boolean) = todoMutex.withLock {
        val existing = dao.getUserTodo(id) ?: throw TodoMutationException("todo_not_found", "待办不存在")
        if (existing.done == done) return@withLock
        beforeTodoChange(id)
        dao.setUserTodoDone(id, done, nextTodoVersion(existing.updatedAt))
    }

    suspend fun deleteUserTodo(id: Long, expectedUpdatedAt: String? = null) = todoMutex.withLock {
        val existing = dao.getUserTodo(id) ?: throw TodoMutationException("todo_not_found", "待办不存在")
        if (expectedUpdatedAt != null && existing.updatedAt != expectedUpdatedAt) {
            throw TodoMutationException("todo_conflict", "待办已修改，请重新查询")
        }
        beforeTodoChange(id)
        dao.deleteUserTodo(id)
    }

    private suspend fun finish(
        runId: Long,
        status: String,
        summary: Map<String, SyncModuleSummary>,
        errorText: String?,
    ) {
        dao.finishSyncRun(
            id = runId,
            finishedAt = nowIso(),
            status = status,
            moduleSummaryJson = encodeSummary(summary),
            errorText = errorText,
        )
    }

    private fun <T> ModuleEnvelope<T>.withSyncedAt(value: String): ModuleEnvelope<T> =
        copy(syncedAt = value)

    private fun itemCount(data: Any?): Int = when (data) {
        is TimetableData -> data.entries.size
        is ExamData -> data.items.size
        is ScoreData -> data.items.size
        is CalendarData -> data.items.size
        is HomeworkData -> data.items.size
        is CourseResourcesData -> data.resources.size
        is CourseReplayData -> data.lessons.size
        is EmptyRoomData -> data.rooms.size
        is MailMessagesData -> data.messages.size
        is MailFoldersData -> data.folders.size
        is CourseSelectionData -> data.availableCourses.size
        is TeachingAssessmentData -> data.courses.size
        is StudentProfileData -> data.fields.size
        is ZhixingHomeData -> data.latestPosts.size + data.rankItems.size
        is AcademicProgressData -> if (data.buckets.isNotEmpty()) data.buckets.size else data.courses.size
        else -> 0
    }
}

data class OverviewDashboard(
    val snapshots: List<ModuleSnapshotEntity>,
    val latest: SyncRun,
    val homework: List<HomeworkItem>,
    val exams: List<ExamItem>,
    val calendar: CalendarData?,
    val highlights: List<DashboardHighlight>,
    val hiddenHighlightCount: Int,
    val hasCache: Boolean,
)

class OverviewRepository(
    private val syncRepository: SyncRepository,
) {
    suspend fun loadCached(): OverviewDashboard =
        PerfTrace.measureSuspend("Overview.cacheLoad") {
            val snapshots = syncRepository.snapshots()
            val latest = syncRepository.latestStatus()
            val homework = syncRepository.snapshot<HomeworkData>(ModuleKeys.Homework)?.data?.items.orEmpty()
            val exams = syncRepository.snapshot<ExamData>(ModuleKeys.Exams)?.data?.items.orEmpty()
            val calendar = syncRepository.snapshot<CalendarData>(ModuleKeys.Calendar)?.data
            val highlights = buildOverviewHighlights(
                homework = homework,
                summaries = syncRepository.updateSummaries(),
            )
            OverviewDashboard(
                snapshots = snapshots,
                latest = latest,
                homework = homework,
                exams = exams,
                calendar = calendar,
                highlights = highlights.items,
                hiddenHighlightCount = highlights.remainingCount,
                hasCache = snapshots.isNotEmpty() || homework.isNotEmpty() || exams.isNotEmpty() || calendar != null,
            )
        }
}

class ModuleRepository(
    private val syncRepository: SyncRepository,
    private val sessionManager: SessionManager,
    private val singleFlight: SingleFlight = SingleFlight(),
) {
    suspend fun calendarDashboard(strategy: ModuleLoadStrategy): CalendarDashboard {
        val errors = mutableListOf<String>()
        suspend fun <T> optionalItems(label: String, load: suspend () -> List<T>): List<T> = try {
            load()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            errors += "$label 不可用，请刷新或稍后重试"
            emptyList()
        }
        val calendar = try {
            calendar(strategy = strategy)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            errors += "校园日历不可用，当前仅展示本地待办和已取得的日程"
            ModuleEnvelope(module = ModuleKeys.Calendar, sourceSystem = "native_local",
                coverage = CoverageLevel.Provisional, data = CalendarData(month = LocalDate.now().toString().take(7)))
        }
        return CalendarDashboard(
            calendarEnvelope = calendar,
            homework = optionalItems("作业") { homework("all", strategy).data.items },
            exams = optionalItems("考试") { exams(strategy = strategy).data.items },
            todos = userTodos(),
            campusErrors = errors.toList(),
        )
    }

    suspend fun profile(
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    ): ModuleEnvelope<StudentProfileData> =
        fetchWithStrategy(ModuleKeys.Profile, strategy = strategy) { AaProvider(it).fetchStudentProfile() }

    suspend fun academicProgress(
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    ): ModuleEnvelope<AcademicProgressData> =
        fetchWithStrategy(ModuleKeys.AcademicProgress, strategy = strategy) { AaProvider(it).fetchAcademicProgress() }

    suspend fun historyScores(
        term: String? = null,
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    ): ModuleEnvelope<ScoreData> =
        fetchWithStrategy(
            ModuleKeys.HistoryScores,
            requestKey(ModuleKeys.HistoryScores, "term" to term),
            strategy,
            cacheParams = requestParams("term" to (term ?: "all"), "ctype" to "ln"),
        ) {
            AaProvider(it).fetchHistoryScores(term)
        }

    fun historyScoresProgressive(
        term: String? = null,
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    ): Flow<ProgressiveModuleState<ScoreData>> =
        progressiveLiveOrSnapshot(
            ModuleKeys.HistoryScores,
            strategy = strategy,
            cacheParams = requestParams("term" to (term ?: "all"), "ctype" to "ln"),
        ) { client ->
            AaProvider(client).fetchHistoryScoresProgressive(term)
        }

    suspend fun timetable(
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    ): ModuleEnvelope<TimetableData> =
        fetchWithStrategy(ModuleKeys.Timetable, strategy = strategy) { AaProvider(it).fetchTimetable() }
            .withUserCourses()

    suspend fun exams(
        term: String? = null,
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    ): ModuleEnvelope<ExamData> =
        fetchWithStrategy(
            ModuleKeys.Exams,
            requestKey(ModuleKeys.Exams, "term" to term),
            strategy,
            cacheParams = requestParams("term" to term),
        ) {
            AaProvider(it).fetchExams(term)
        }

    suspend fun scores(
        term: String? = null,
        ctype: String? = null,
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    ): ModuleEnvelope<ScoreData> =
        fetchWithStrategy(
            ModuleKeys.Scores,
            requestKey(ModuleKeys.Scores, "term" to term, "ctype" to ctype),
            strategy,
            cacheParams = requestParams("term" to term, "ctype" to ctype),
        ) {
            AaProvider(it).fetchScores(term, ctype)
        }

    suspend fun scoreDetail(detailPath: String): ModuleEnvelope<ScoreDetailData> =
        sessionManager.withAuthenticatedClient { AaProvider(it).fetchScoreDetail(detailPath) }.copy(syncedAt = nowIso())

    suspend fun calendar(
        month: String? = null,
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    ): ModuleEnvelope<CalendarData> =
        fetchWithStrategy(
            ModuleKeys.Calendar,
            requestKey(ModuleKeys.Calendar, "month" to month),
            strategy,
            cacheParams = requestParams("month" to (month ?: LocalDate.now().toString().substring(0, 7))),
        ) {
            VeProvider(it).fetchCalendar(month)
        }

    suspend fun homework(
        status: String = "all",
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    ): ModuleEnvelope<HomeworkData> {
        val envelope = fetchWithStrategy(ModuleKeys.Homework, requestKey(ModuleKeys.Homework, "status" to "all"), strategy) {
            VeProvider(it).fetchHomework()
        }
        if (status == "all") return envelope
        return envelope.copy(data = envelope.data.copy(items = envelope.data.items.filter {
            homeworkMatchesStatusFilter(it, status)
        }))
    }

    fun homeworkProgressive(
        status: String = "all",
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    ): Flow<ProgressiveModuleState<HomeworkData>> =
        progressiveLiveOrSnapshot(
            moduleKey = ModuleKeys.Homework,
            transform = { it.filteredHomework(status) },
            strategy = strategy,
        ) { client ->
            VeProvider(client).fetchHomeworkProgressive()
        }

    suspend fun submitHomework(
        homeworkId: Int,
        courseId: Int,
        content: String,
        files: List<HomeworkUploadFile>,
    ): HomeworkSubmitResponse =
        sessionManager.withAuthenticatedClient { VeProvider(it).submitHomework(homeworkId, courseId, content, files) }

    suspend fun emptyRooms(
        term: String? = null,
        week: String? = null,
        building: String? = null,
        room: String? = null,
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    ): ModuleEnvelope<EmptyRoomData> =
        fetchWithStrategy(
            ModuleKeys.EmptyRooms,
            requestKey(ModuleKeys.EmptyRooms, "term" to term, "week" to week, "building" to building, "room" to room),
            strategy,
            cacheParams = requestParams("term" to term, "week" to week, "building" to building, "room" to room),
        ) {
            AaProvider(it).fetchEmptyRooms(term, week, building, room)
        }

    suspend fun teachingAssessments(
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    ): ModuleEnvelope<TeachingAssessmentData> =
        fetchWithStrategy(ModuleKeys.TeachingAssessment, strategy = strategy) {
            AaProvider(it).fetchTeachingAssessmentList()
        }

    suspend fun teachingAssessmentForm(courseId: String): TeachingAssessmentForm =
        sessionManager.withAuthenticatedClient { AaProvider(it).fetchTeachingAssessmentForm(courseId) }

    suspend fun submitTeachingAssessment(
        form: TeachingAssessmentForm,
        answerValues: Map<String, String>,
        commentValues: Map<String, String>,
    ): TeachingAssessmentSubmitResult =
        sessionManager.withAuthenticatedClient { AaProvider(it).submitTeachingAssessment(form, answerValues, commentValues) }

    suspend fun courseResources(
        term: String? = null,
        courseId: String? = null,
        folderId: String = "0",
        search: String? = null,
        categoryKey: String? = null,
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    ): ModuleEnvelope<CourseResourcesData> =
        fetchWithStrategy(
            ModuleKeys.CourseResources,
            requestKey(
                ModuleKeys.CourseResources,
                "term" to term,
                "requested_course_id" to courseId.orEmpty(),
                "course_id" to courseId,
                "folder_id" to folderId,
                "search" to search,
                "category_key" to categoryKey,
            ),
            strategy,
            cacheParams = requestParams(
                "term" to term,
                "requested_course_id" to courseId.orEmpty(),
                "course_id" to courseId,
                "folder_id" to folderId,
                "search" to search,
                "category_key" to categoryKey,
            ),
        ) {
            VeProvider(it).fetchCourseResources(term, courseId, folderId, search, categoryKey)
        }

    fun courseResourcesProgressive(
        term: String? = null,
        courseId: String? = null,
        folderId: String = "0",
        search: String? = null,
        categoryKey: String? = null,
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    ): Flow<ProgressiveModuleState<CourseResourcesData>> =
        progressiveLiveOrSnapshot(
            ModuleKeys.CourseResources,
            strategy = strategy,
            cacheParams = requestParams(
                "term" to term,
                "course_id" to courseId,
                "folder_id" to folderId,
                "search" to search,
                "category_key" to categoryKey,
            ),
        ) { client ->
            VeProvider(client).fetchCourseResourcesProgressive(term, courseId, folderId, search, categoryKey)
        }

    fun courseReplayProgressive(
        term: String? = null,
        courseId: String? = null,
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    ): Flow<ProgressiveModuleState<CourseReplayData>> =
        progressiveLiveOrSnapshot(
            ModuleKeys.CourseReplay,
            strategy = strategy,
            cacheParams = requestParams("term" to term, "requested_course_id" to courseId.orEmpty()),
        ) { client ->
            VeProvider(client).fetchCourseReplaysProgressive(term, courseId)
        }

    suspend fun snapshots(): List<ModuleSnapshotEntity> = syncRepository.snapshots()

    suspend fun saveUserCourse(draft: UserCourseDraft): Long =
        syncRepository.saveUserCourse(draft)

    suspend fun deleteUserCourse(id: Long) {
        syncRepository.deleteUserCourse(id)
    }

    suspend fun userTodos(): List<UserTodoItem> =
        syncRepository.userTodos()

    suspend fun saveUserTodo(draft: UserTodoDraft, expectedUpdatedAt: String? = null): Long =
        syncRepository.saveUserTodo(draft, expectedUpdatedAt)

    suspend fun setUserTodoDone(id: Long, done: Boolean) {
        syncRepository.setUserTodoDone(id, done)
    }

    suspend fun deleteUserTodo(id: Long, expectedUpdatedAt: String? = null) {
        syncRepository.deleteUserTodo(id, expectedUpdatedAt)
    }

    private suspend fun ModuleEnvelope<TimetableData>.withUserCourses(): ModuleEnvelope<TimetableData> {
        val localEntries = syncRepository.userCourses().map { it.toCourseEntry() }
        if (localEntries.isEmpty()) return this
        return copy(data = data.copy(entries = data.entries + localEntries))
    }

    private suspend inline fun <reified T> fetchWithStrategy(
        moduleKey: String,
        requestKey: String = moduleKey,
        strategy: ModuleLoadStrategy,
        cacheParams: List<Pair<String, String?>> = emptyList(),
        crossinline fetcher: suspend (cn.edu.bjtu.mis.data.network.BjtuHttpClient) -> ModuleEnvelope<T>,
    ): ModuleEnvelope<T> =
        when (strategy) {
            ModuleLoadStrategy.CacheOnly -> syncRepository.snapshot<T>(moduleKey)
                ?.takeIf { it.matchesCacheParams(cacheParams) }
                ?: throw LocalSnapshotMissingException(moduleKey)
            ModuleLoadStrategy.CacheFirst -> syncRepository.snapshot<T>(moduleKey)
                ?.takeIf { it.matchesCacheParams(cacheParams) }
                ?: fetchLiveOrSnapshot(moduleKey, requestKey, cacheParams, fetcher)
            ModuleLoadStrategy.NetworkFirst -> fetchLiveOrSnapshot(moduleKey, requestKey, cacheParams, fetcher)
        }

    private suspend inline fun <reified T> fetchLiveOrSnapshot(
        moduleKey: String,
        requestKey: String = moduleKey,
        cacheParams: List<Pair<String, String?>> = emptyList(),
        crossinline fetcher: suspend (cn.edu.bjtu.mis.data.network.BjtuHttpClient) -> ModuleEnvelope<T>,
    ): ModuleEnvelope<T> {
        return singleFlight.run(requestKey) {
            runCatching {
                val envelope = sessionManager.withAuthenticatedClient { fetcher(it) }.copy(syncedAt = nowIso())
                syncRepository.saveSnapshot(moduleKey, envelope)
                envelope
            }.getOrElse {
                syncRepository.snapshot<T>(moduleKey)
                    ?.takeIf { cached -> cached.matchesCacheParams(cacheParams) }
                    ?: throw it
            }
        }
    }

    private fun requestKey(moduleKey: String, vararg params: Pair<String, String?>): String {
        val suffix = params.joinToString("&") { (key, value) -> "$key=${value?.trim().orEmpty()}" }
        return if (suffix.isBlank()) moduleKey else "$moduleKey:$suffix"
    }

    private fun requestParams(vararg params: Pair<String, String?>): List<Pair<String, String?>> =
        params.toList()

    private fun <T> ModuleEnvelope<T>.matchesCacheParams(params: List<Pair<String, String?>>): Boolean =
        params
            .mapNotNull { (key, value) -> value?.trim()?.let { key to it } }
            .all { (key, expected) ->
                sourceParams[key]?.jsonPrimitive?.contentOrNull?.trim() == expected
            }

    private inline fun <reified T> progressiveLiveOrSnapshot(
        moduleKey: String,
        noinline transform: (ModuleEnvelope<T>) -> ModuleEnvelope<T> = { it },
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
        cacheParams: List<Pair<String, String?>> = emptyList(),
        crossinline fetcher: (BjtuHttpClient) -> Flow<ProgressiveModuleState<T>>,
    ): Flow<ProgressiveModuleState<T>> = flow {
        val cached = syncRepository.snapshot<T>(moduleKey)
            ?.takeIf { it.matchesCacheParams(cacheParams) }
            ?.let(transform)
        var latestNetworkEnvelope: ModuleEnvelope<T>? = null
        if (strategy == ModuleLoadStrategy.CacheOnly || (strategy == ModuleLoadStrategy.CacheFirst && cached != null)) {
            emit(
                ProgressiveModuleState(
                    envelope = cached,
                    loading = false,
                    complete = true,
                    fromCache = cached != null,
                    loadedCount = cached?.data?.let { itemCount(it).takeIf { count -> count >= 0 } },
                    errors = if (cached == null) listOf(LocalSnapshotMissingException(moduleKey).message.orEmpty()) else emptyList(),
                )
            )
            return@flow
        }
        if (cached != null) {
            emit(
                ProgressiveModuleState(
                    envelope = cached,
                    loading = true,
                    complete = false,
                    fromCache = true,
                    loadedCount = itemCount(cached.data).takeIf { it >= 0 },
                )
            )
        }

        runCatching {
            sessionManager.withAuthenticatedClient { client ->
                fetcher(client).collect { state ->
                    val rawEnvelope = state.envelope
                    val envelopeWithSyncedAt = if (state.complete && rawEnvelope != null) {
                        rawEnvelope.copy(syncedAt = nowIso())
                    } else {
                        rawEnvelope
                    }
                    if (state.complete && envelopeWithSyncedAt != null) {
                        syncRepository.saveSnapshot(moduleKey, envelopeWithSyncedAt)
                    }
                    val displayEnvelope = envelopeWithSyncedAt?.let(transform)
                    latestNetworkEnvelope = displayEnvelope
                    emit(state.copy(envelope = displayEnvelope, fromCache = false))
                }
            }
        }.onFailure { error ->
            val fallback = latestNetworkEnvelope ?: cached
            emit(
                ProgressiveModuleState(
                    envelope = fallback,
                    loading = false,
                    complete = true,
                    fromCache = latestNetworkEnvelope == null && cached != null,
                    loadedCount = fallback?.data?.let { itemCount(it).takeIf { count -> count >= 0 } },
                    errors = listOf(error.message ?: "加载失败"),
                )
            )
        }
    }

    private fun ModuleEnvelope<HomeworkData>.filteredHomework(status: String): ModuleEnvelope<HomeworkData> {
        if (status == "all") return this
        return copy(data = data.copy(items = data.items.filter { homeworkMatchesStatusFilter(it, status) }))
    }

    private fun itemCount(data: Any?): Int = when (data) {
        is TimetableData -> data.entries.size
        is ExamData -> data.items.size
        is ScoreData -> data.items.size
        is CalendarData -> data.items.size
        is HomeworkData -> data.items.size
        is CourseResourcesData -> data.resources.size
        is CourseReplayData -> data.lessons.size
        is EmptyRoomData -> data.rooms.size
        is MailMessagesData -> data.messages.size
        is MailFoldersData -> data.folders.size
        is CourseSelectionData -> data.availableCourses.size
        is TeachingAssessmentData -> data.courses.size
        is StudentProfileData -> data.fields.size
        is ZhixingHomeData -> data.latestPosts.size + data.rankItems.size
        is EmploymentConsultationData -> data.articles.size
        else -> -1
    }
}

class CourseResourceRepository(
    private val context: Context,
    private val moduleRepository: ModuleRepository,
    private val sessionManager: SessionManager,
    private val singleFlight: SingleFlight = SingleFlight(),
) {
    suspend fun listing(
        term: String? = null,
        courseId: String? = null,
        folderId: String = "0",
        search: String? = null,
        categoryKey: String? = null,
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    ): ModuleEnvelope<CourseResourcesData> =
        moduleRepository.courseResources(term, courseId, folderId, search, categoryKey, strategy)

    fun listingProgressive(
        term: String? = null,
        courseId: String? = null,
        folderId: String = "0",
        search: String? = null,
        categoryKey: String? = null,
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    ): Flow<ProgressiveModuleState<CourseResourcesData>> =
        moduleRepository.courseResourcesProgressive(term, courseId, folderId, search, categoryKey, strategy)

    suspend fun download(rpId: String, filename: String, extension: String? = null): File =
        singleFlight.run("course_resource:download:${rpId.trim()}") {
            sessionManager.withAuthenticatedClient { client ->
                val targetDir = File(context.filesDir, "downloads").apply { mkdirs() }
                val target = File(targetDir, safeDownloadFileName(filename, extension, rpId))
                VeProvider(client).downloadCourseResource(rpId, target).file
            }
        }
    suspend fun preview(resource: CourseResourceItem): DocumentPreview {
        if (!courseResourcePreviewSupported(resource)) {
            throw IllegalStateException("该文件暂不支持在线预览，请下载后查看")
        }
        return singleFlight.run("course_resource:preview:${resource.rpId.trim()}") {
            sessionManager.withAuthenticatedClient { client ->
                DocumentPreview(
                    url = VeProvider(client).previewCourseResource(resource),
                    cookies = client.cookieJar.snapshot().map { it.toDocumentPreviewCookie() },
                )
            }
        }
    }

    private fun safeDownloadFileName(filename: String, extension: String?, rpId: String): String {
        val safeName = filename
            .trim()
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .ifBlank { "resource-$rpId" }
        val safeExtension = extension
            ?.trim()
            ?.trimStart('.')
            ?.replace(Regex("""[^A-Za-z0-9]+"""), "_")
            ?.trim('_')
            .orEmpty()

        return if (safeExtension.isBlank() || safeName.endsWith(".$safeExtension", ignoreCase = true)) {
            safeName
        } else {
            "$safeName.$safeExtension"
        }
    }
}

class HomeworkAttachmentRepository(
    private val context: Context,
    private val sessionManager: SessionManager,
) {
    suspend fun download(homeworkId: Int, attachmentId: String, filename: String): File =
        sessionManager.withAuthenticatedClient { client ->
            val targetDir = File(context.filesDir, "downloads/homework").apply { mkdirs() }
            val target = File(targetDir, safeHomeworkAttachmentFileName(filename, attachmentId))
            VeProvider(client).downloadHomeworkAttachment(homeworkId, attachmentId, target).file
        }

    suspend fun preview(homeworkId: Int, attachmentId: String, filename: String): HomeworkAttachmentPreview {
        if (!homeworkAttachmentPreviewSupported(filename)) {
            throw IllegalStateException("压缩文件暂不支持在线预览，请下载后查看")
        }
        return sessionManager.withAuthenticatedClient { client ->
            HomeworkAttachmentPreview(
                url = VeProvider(client).previewHomeworkAttachment(homeworkId, attachmentId),
                cookies = client.cookieJar.snapshot().map { it.toDocumentPreviewCookie() },
            )
        }
    }

    suspend fun previewUrl(homeworkId: Int, attachmentId: String, filename: String): String {
        if (!homeworkAttachmentPreviewSupported(filename)) {
            throw IllegalStateException("压缩文件暂不支持在线预览，请下载后查看")
        }
        return sessionManager.withAuthenticatedClient { client ->
            VeProvider(client).previewHomeworkAttachment(homeworkId, attachmentId)
        }
    }
}

data class DocumentPreview(
    val url: String,
    val cookies: List<DocumentPreviewCookie>,
)

data class DocumentPreviewCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
)

typealias HomeworkAttachmentPreview = DocumentPreview

typealias HomeworkAttachmentPreviewCookie = DocumentPreviewCookie

private fun Cookie.toDocumentPreviewCookie(): DocumentPreviewCookie =
    DocumentPreviewCookie(
        name = name,
        value = value,
        domain = domain,
        path = path,
        secure = secure,
        httpOnly = httpOnly,
        hostOnly = hostOnly,
    )

internal fun homeworkAttachmentPreviewSupported(filename: String): Boolean =
    filename.substringAfterLast('.', "").lowercase() !in setOf("zip", "rar", "7z")

internal fun courseResourcePreviewSupported(resource: CourseResourceItem): Boolean =
    courseResourcePreviewSupported(resource.extension?.takeIf { it.isNotBlank() } ?: resource.name)

internal fun courseResourcePreviewSupported(filenameOrExtension: String): Boolean {
    val extension = if (filenameOrExtension.contains('.')) {
        filenameOrExtension.substringAfterLast('.', "")
    } else {
        filenameOrExtension
    }.trim().trimStart('.').lowercase()
    return extension !in setOf("zip", "rar", "7z", "exe", "apk")
}

internal fun safeHomeworkAttachmentFileName(filename: String, attachmentId: String): String {
    val safeId = attachmentId
        .trim()
        .replace(Regex("""[^A-Za-z0-9._-]+"""), "_")
        .trim('.', '_')
        .ifBlank { "file" }
    val safeName = filename
        .trim()
        .replace(Regex("""[\u0000-\u001F\\/:*?"<>|]"""), "_")
        .trim()
        .trim('.')
        .ifBlank { "homework-attachment-$safeId" }
    return collapseRepeatedExtension(safeName)
}

private fun collapseRepeatedExtension(filename: String): String {
    var result = filename
    while (true) {
        val extension = result.substringAfterLast('.', missingDelimiterValue = "")
        if (extension.isBlank()) return result
        val suffix = ".$extension"
        val base = result.dropLast(suffix.length)
        if (!base.endsWith(suffix, ignoreCase = true)) return result
        result = base
    }
}

class CourseReplayRepository(
    private val syncRepository: SyncRepository,
    private val moduleRepository: ModuleRepository,
    private val sessionManager: SessionManager,
) {
    suspend fun listing(
        term: String? = null,
        courseId: String? = null,
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    ): ModuleEnvelope<CourseReplayData> =
        when (strategy) {
            ModuleLoadStrategy.CacheOnly -> syncRepository.snapshot<CourseReplayData>(ModuleKeys.CourseReplay)
                ?: throw LocalSnapshotMissingException(ModuleKeys.CourseReplay)
            ModuleLoadStrategy.CacheFirst -> syncRepository.snapshot<CourseReplayData>(ModuleKeys.CourseReplay)
                ?: listing(term = term, courseId = courseId, strategy = ModuleLoadStrategy.NetworkFirst)
            ModuleLoadStrategy.NetworkFirst -> runCatching {
                val envelope = sessionManager.withAuthenticatedClient {
                    VeProvider(it).fetchCourseReplays(term, courseId)
                }.copy(syncedAt = nowIso())
                syncRepository.saveSnapshot(ModuleKeys.CourseReplay, envelope)
                envelope
            }.getOrElse {
                syncRepository.snapshot<CourseReplayData>(ModuleKeys.CourseReplay)
                    ?: throw it
            }
        }

    fun listingProgressive(
        term: String? = null,
        courseId: String? = null,
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    ): Flow<ProgressiveModuleState<CourseReplayData>> =
        moduleRepository.courseReplayProgressive(term, courseId, strategy)

    suspend fun playback(
        term: String? = null,
        courseId: String? = null,
        courseSchedId: String,
        userId: String? = null,
        timeTableId: String? = null,
        videoId: String? = null,
    ): CourseReplayPlaybackInfo =
        sessionManager.withAuthenticatedClient {
            VeProvider(it).fetchCourseReplayPlayback(
                term = term,
                courseId = courseId,
                courseSchedId = courseSchedId,
                userId = userId,
                timeTableId = timeTableId,
                videoId = videoId,
            )
        }

    suspend fun reportListen(
        userId: String,
        timetableId: String,
        courseId: Int,
        listenTimeSeconds: Long,
    ): Boolean =
        sessionManager.withAuthenticatedClient {
            VeProvider(it).reportCourseReplayListen(userId, timetableId, courseId, listenTimeSeconds)
    }
}

class EmploymentConsultationRepository(
    private val syncRepository: SyncRepository,
    private val provider: EmploymentConsultationProvider,
) {
    constructor(syncRepository: SyncRepository, client: BjtuHttpClient) :
        this(syncRepository, EmploymentConsultationProvider(client))

    suspend fun home(
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.CacheFirst,
    ): ModuleEnvelope<EmploymentConsultationData> =
        loadSnapshot(ModuleKeys.EmploymentConsultation, strategy) { provider.fetchConsultationHome() }

    private suspend inline fun <reified T> loadSnapshot(
        key: String,
        strategy: ModuleLoadStrategy,
        fetch: () -> ModuleEnvelope<T>,
    ): ModuleEnvelope<T> {
        if (strategy != ModuleLoadStrategy.NetworkFirst) {
            syncRepository.snapshot<T>(key)?.let { return it }
            if (strategy == ModuleLoadStrategy.CacheOnly) throw LocalSnapshotMissingException(key)
        }
        return try {
            fetch().copy(syncedAt = nowIso()).also { syncRepository.saveSnapshot(key, it) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            syncRepository.snapshot<T>(key) ?: throw error
        }
    }

    suspend fun article(articleId: String): ModuleEnvelope<EmploymentArticleDetail> =
        loadSnapshot(articleSnapshotKey(articleId), ModuleLoadStrategy.NetworkFirst) { provider.fetchArticle(articleId) }

    suspend fun infoPage(
        query: EmploymentInfoQuery,
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.CacheFirst,
    ): ModuleEnvelope<EmploymentInfoPage> {
        val normalizedQuery = query.copy(
            pageNo = query.pageNo.coerceAtLeast(1),
            pageSize = query.pageSize.coerceAtLeast(1),
            title = query.title.trim(),
            city = query.city.trim(),
            cityName = query.cityName.trim(),
            corporationNature = query.corporationNature.trim(),
            corporationNatureLabel = query.corporationNatureLabel.trim(),
            industry = query.industry.trim(),
            industryLabel = query.industryLabel.trim(),
        )
        return loadSnapshot(infoPageSnapshotKey(normalizedQuery), strategy) {
            provider.fetchInfoPage(normalizedQuery)
        }
    }

    suspend fun filterOptions(
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.CacheFirst,
    ): ModuleEnvelope<EmploymentFilterOptions> =
        loadSnapshot("${ModuleKeys.EmploymentConsultation}:filters", strategy) {
            provider.fetchFilterOptions()
        }

    suspend fun cityOptions(parentId: String): ModuleEnvelope<List<EmploymentFilterOption>> =
        provider.fetchCityOptions(parentId).copy(syncedAt = nowIso())

    suspend fun calendarEvents(
        pageSize: Int = 50,
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.CacheFirst,
    ): List<EmploymentCalendarEvent> {
        val normalizedPageSize = pageSize.coerceAtLeast(1)
        val items = listOf(
            EmploymentSectionType.CareerTalk,
            EmploymentSectionType.JobFair,
        ).flatMap { type ->
            infoPage(
                query = EmploymentInfoQuery(
                    type = type,
                    pageNo = 1,
                    pageSize = normalizedPageSize,
                ),
                strategy = strategy,
            ).data.items
        }
        return employmentCalendarEvents(items)
    }

    suspend fun infoDetail(
        type: EmploymentSectionType,
        itemId: String,
    ): ModuleEnvelope<EmploymentInfoDetail> =
        loadSnapshot(infoDetailSnapshotKey(type, itemId), ModuleLoadStrategy.NetworkFirst) {
            provider.fetchInfoDetail(type, itemId)
        }

    private fun articleSnapshotKey(articleId: String): String =
        "${ModuleKeys.EmploymentConsultation}:article:${articleId.trim()}"

    private fun infoPageSnapshotKey(query: EmploymentInfoQuery): String =
        listOf(
            ModuleKeys.EmploymentConsultation,
            "page",
            query.type.name,
            query.pageNo.toString(),
            query.pageSize.toString(),
            query.title,
            query.city,
            query.corporationNature,
            query.industry,
        ).joinToString(":") { it.replace(":", "_") }

    private fun infoDetailSnapshotKey(type: EmploymentSectionType, itemId: String): String =
        "${ModuleKeys.EmploymentConsultation}:detail:${type.name}:${itemId.trim()}"
}

class ZhixingRepository(
    private val syncRepository: SyncRepository,
    private val sessionManager: SessionManager,
    private val credentialStore: CredentialStore,
) {
    private data class PendingLogin(
        val username: String,
        val password: String,
        val challenge: ZhixingLoginChallenge,
    )

    private var pendingLogin: PendingLogin? = null

    suspend fun home(
        forceRefresh: Boolean = false,
        strategy: ModuleLoadStrategy = if (forceRefresh) ModuleLoadStrategy.NetworkFirst else ModuleLoadStrategy.CacheFirst,
    ): ModuleEnvelope<ZhixingHomeData> {
        if (strategy == ModuleLoadStrategy.CacheOnly) {
            return syncRepository.snapshot<ZhixingHomeData>(ModuleKeys.Zhixing)
                ?: throw LocalSnapshotMissingException(ModuleKeys.Zhixing)
        }
        if (!forceRefresh && strategy == ModuleLoadStrategy.CacheFirst) {
            syncRepository.snapshot<ZhixingHomeData>(ModuleKeys.Zhixing)?.let { return it }
        }
        return runCatching {
            val envelope = sessionManager.withAuthenticatedClient { client ->
                val provider = ZhixingProvider(client)
                val home = provider.fetchHome()
                val state = if (home.data.authState.loggedIn) {
                    home.data.authState
                } else {
                    tryAutoLogin(provider) ?: home.data.authState
                }
                home.copy(data = home.data.copy(authState = state))
            }.copy(syncedAt = nowIso())
            syncRepository.saveSnapshot(ModuleKeys.Zhixing, envelope)
            envelope
        }.getOrElse { error ->
            syncRepository.snapshot<ZhixingHomeData>(ModuleKeys.Zhixing)
                ?: throw error
        }
    }

    suspend fun thread(threadId: String, page: Int = 1, url: String? = null): ModuleEnvelope<ZhixingThreadDetail> =
        sessionManager.withAuthenticatedClient { client ->
            val provider = ZhixingProvider(client)
            val first = if (url.isNullOrBlank()) {
                provider.fetchThread(threadId, page)
            } else {
                provider.fetchThreadUrl(url, threadId, page)
            }.copy(syncedAt = nowIso())
            if (!first.data.restricted) return@withAuthenticatedClient first
            val loggedIn = tryAutoLogin(provider)
            if (loggedIn?.loggedIn == true) {
                if (url.isNullOrBlank()) {
                    provider.fetchThread(threadId, page)
                } else {
                    provider.fetchThreadUrl(url, threadId, page)
                }.copy(syncedAt = nowIso())
            } else {
                first
            }
        }

    suspend fun imageBytes(url: String, referer: String): ByteArray =
        sessionManager.withAuthenticatedClient { client ->
            ZhixingProvider(client).fetchImage(url, referer)
        }

    suspend fun search(keyword: String, page: Int = 1): ModuleEnvelope<ZhixingSearchData> =
        sessionManager.withAuthenticatedClient { client ->
            ZhixingProvider(client).search(keyword, page).copy(syncedAt = nowIso())
        }

    suspend fun authState(
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    ): ZhixingAuthState =
        when (strategy) {
            ModuleLoadStrategy.CacheOnly -> syncRepository.snapshot<ZhixingHomeData>(ModuleKeys.Zhixing)?.data?.authState
                ?: throw LocalSnapshotMissingException(ModuleKeys.Zhixing)
            ModuleLoadStrategy.CacheFirst -> syncRepository.snapshot<ZhixingHomeData>(ModuleKeys.Zhixing)?.data?.authState
                ?: authState(ModuleLoadStrategy.NetworkFirst)
            ModuleLoadStrategy.NetworkFirst -> sessionManager.withAuthenticatedClient { client ->
                val provider = ZhixingProvider(client)
                val state = provider.authState()
                if (state.loggedIn) state else tryAutoLogin(provider) ?: state
            }
        }

    suspend fun login(username: String, password: String): ZhixingLoginOutcome =
        sessionManager.withAuthenticatedClient { client ->
            val outcome = ZhixingProvider(client).login(username, password)
            when (outcome.status) {
                ZhixingLoginStatus.Success -> {
                    pendingLogin = null
                    credentialStore.save(LoginCredentials(username.trim(), password))
                    outcome
                }
                ZhixingLoginStatus.CaptchaRequired -> {
                    val challenge = outcome.challenge
                    if (challenge != null) {
                        pendingLogin = PendingLogin(username.trim(), password, challenge)
                    }
                    outcome
                }
                ZhixingLoginStatus.Failure -> {
                    pendingLogin = null
                    outcome
                }
            }
        }

    suspend fun submitLoginCaptcha(challengeId: String, answer: String): ZhixingLoginOutcome =
        sessionManager.withAuthenticatedClient { client ->
            val pending = pendingLogin
                ?: return@withAuthenticatedClient ZhixingLoginOutcome(
                    status = ZhixingLoginStatus.Failure,
                    message = "验证码上下文已失效，请重新登录。",
                )
            if (pending.challenge.challengeId != challengeId) {
                return@withAuthenticatedClient ZhixingLoginOutcome(
                    status = ZhixingLoginStatus.Failure,
                    message = "验证码已刷新，请重新输入。",
                )
            }
            val outcome = ZhixingProvider(client).submitLoginCaptcha(pending.challenge, answer)
            when (outcome.status) {
                ZhixingLoginStatus.Success -> {
                    pendingLogin = null
                    credentialStore.save(LoginCredentials(pending.username, pending.password))
                    outcome.copy(authState = outcome.authState?.copy(username = pending.username))
                }
                ZhixingLoginStatus.CaptchaRequired -> {
                    outcome.challenge?.let { challenge ->
                        pendingLogin = pending.copy(challenge = challenge)
                    }
                    outcome
                }
                ZhixingLoginStatus.Failure -> outcome
            }
        }

    suspend fun logout() {
        pendingLogin = null
        credentialStore.clear()
        sessionManager.withAuthenticatedClient { client ->
            ZhixingProvider(client).logout()
        }
    }

    private suspend fun tryAutoLogin(provider: ZhixingProvider): ZhixingAuthState? {
        val credentials = credentialStore.load() ?: return null
        return runCatching {
            val outcome = provider.login(credentials.loginName, credentials.password)
            if (outcome.status == ZhixingLoginStatus.Success) {
                outcome.authState
            } else {
                null
            }
        }.getOrNull()
    }
}

data class MailUploadFile(
    val filename: String,
    val content: ByteArray,
    val contentType: String? = null,
)

class MailRepository(
    private val context: Context,
    private val dao: BjtuMisDao,
    private val sessionManager: SessionManager,
) {
    suspend fun folders(
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    ): ModuleEnvelope<MailFoldersData> =
        when (strategy) {
            ModuleLoadStrategy.CacheOnly -> cachedFolders() ?: throw LocalSnapshotMissingException(ModuleKeys.Mail)
            ModuleLoadStrategy.CacheFirst -> cachedFolders() ?: folders(ModuleLoadStrategy.NetworkFirst)
            ModuleLoadStrategy.NetworkFirst -> runCatching {
                val envelope = sessionManager.withAuthenticatedClient { CoremailProvider(it).fetchFolders() }.copy(syncedAt = nowIso())
                dao.clearMailFolders()
                dao.saveMailFolders(envelope.data.folders.map { it.toEntity(envelope.syncedAt ?: nowIso()) })
                envelope
            }.getOrElse { error ->
                cachedFolders() ?: throw error
            }
        }

    suspend fun messages(
        folderId: String = "1",
        start: Int = 0,
        limit: Int = 20,
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    ): ModuleEnvelope<MailMessagesData> =
        when (strategy) {
            ModuleLoadStrategy.CacheOnly -> cachedMessages(folderId, start, limit) ?: throw LocalSnapshotMissingException(ModuleKeys.Mail)
            ModuleLoadStrategy.CacheFirst -> cachedMessages(folderId, start, limit)
                ?: messages(folderId = folderId, start = start, limit = limit, strategy = ModuleLoadStrategy.NetworkFirst)
            ModuleLoadStrategy.NetworkFirst -> runCatching {
                val envelope = sessionManager.withAuthenticatedClient {
                    CoremailProvider(it).fetchMessages(folderId = folderId, start = start, limit = limit)
                }.copy(syncedAt = nowIso())
                if (start == 0) dao.clearMailMessageSummaries(folderId)
                dao.saveMailMessageSummaries(envelope.data.messages.map { it.toEntity(envelope.syncedAt ?: nowIso()) })
                envelope
            }.getOrElse { error ->
                cachedMessages(folderId, start, limit) ?: throw error
            }
        }

    suspend fun detail(
        messageId: String,
        mboxa: String = "",
        hydrateInlineImages: Boolean = false,
    ): ModuleEnvelope<MailMessageDetail> =
        sessionManager.withAuthenticatedClient {
            CoremailProvider(it).let { provider ->
                provider.fetchMessageDetail(messageId = messageId, mboxa = mboxa).let { envelope ->
                    if (hydrateInlineImages) provider.hydrateInlineImages(envelope) else envelope
                }
            }
        }.copy(syncedAt = nowIso())

    suspend fun delete(messageIds: List<String>, mboxa: String = ""): MailDeleteResponse =
        sessionManager.withAuthenticatedClient {
            CoremailProvider(it).deleteMessages(messageIds = messageIds, mboxa = mboxa)
        }.also {
            dao.deleteMailMessageSummaries(messageIds)
        }

    suspend fun markRead(messageIds: List<String>, mboxa: String = ""): MailMarkReadResponse =
        sessionManager.withAuthenticatedClient {
            CoremailProvider(it).markMessagesRead(messageIds = messageIds, mboxa = mboxa)
        }.also {
            val cachedUnread = dao.getMailMessageSummariesByIds(messageIds).filter { message -> !message.read }
            dao.markMailMessageSummariesRead(messageIds)
            cachedUnread
                .groupingBy { message -> message.folderId }
                .eachCount()
                .forEach { (folderId, unreadCount) ->
                    dao.decrementMailFolderUnreadCount(folderId, unreadCount)
                }
        }

    suspend fun download(messageId: String, part: String, filename: String?, contentType: String? = null): File =
        sessionManager.withAuthenticatedClient { client ->
            val targetDir = File(context.filesDir, "downloads/mail").apply { mkdirs() }
            val target = File(targetDir, safeMailFileName(filename, contentType, part))
            CoremailProvider(client).downloadAttachment(messageId, part, filename, target).file
        }

    suspend fun uploadAttachment(composeId: String?, file: MailUploadFile): MailAttachmentUploadResponse =
        sessionManager.withAuthenticatedClient {
            CoremailProvider(it).uploadAttachment(
                filename = file.filename,
                content = file.content,
                contentType = file.contentType,
                composeId = composeId,
            )
        }

    suspend fun send(request: MailComposeRequest): MailComposeResponse =
        sessionManager.withAuthenticatedClient { CoremailProvider(it).sendMessage(request) }

    suspend fun saveDraft(request: MailComposeRequest): MailComposeResponse =
        sessionManager.withAuthenticatedClient { CoremailProvider(it).saveDraft(request) }

    suspend fun contacts(keyword: String, limit: Int = 20): ModuleEnvelope<MailContactsData> =
        sessionManager.withAuthenticatedClient {
            CoremailProvider(it).autocompleteContacts(keyword = keyword, limit = limit)
        }.copy(syncedAt = nowIso())

    private suspend fun cachedFolders(): ModuleEnvelope<MailFoldersData>? {
        val folders = dao.getMailFolders()
        if (folders.isEmpty()) return null
        return ModuleEnvelope(
            module = "mail_folders",
            syncedAt = folders.maxOfOrNull { it.syncedAt },
            sourceSystem = "coremail_cache",
            coverage = cn.edu.bjtu.mis.model.CoverageLevel.Provisional,
            sourceParams = buildJsonObject { put("cache", true) },
            data = MailFoldersData(folders.map { it.toModel() }),
        )
    }

    private suspend fun cachedMessages(folderId: String, start: Int, limit: Int): ModuleEnvelope<MailMessagesData>? {
        val messages = dao.getMailMessageSummaries(folderId, start, limit)
        if (messages.isEmpty()) return null
        return ModuleEnvelope(
            module = "mail_messages",
            syncedAt = messages.maxOfOrNull { it.syncedAt },
            sourceSystem = "coremail_cache",
            coverage = cn.edu.bjtu.mis.model.CoverageLevel.Provisional,
            sourceParams = buildJsonObject {
                put("cache", true)
                put("folder_id", folderId)
                put("start", start)
                put("limit", limit)
            },
            data = MailMessagesData(
                folderId = folderId,
                start = start,
                limit = limit,
                total = dao.countMailMessageSummaries(folderId),
                messages = messages.map { it.toModel() },
            ),
        )
    }

    private fun safeMailFileName(filename: String?, contentType: String?, part: String): String {
        val safeName = filename
            ?.trim()
            ?.replace(Regex("""[\\/:*?"<>|]"""), "_")
            ?.ifBlank { null }
            ?: "mail-attachment-$part"
        if (safeName.contains('.')) return safeName
        val extension = when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
            "text/plain" -> "txt"
            "application/pdf" -> "pdf"
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            else -> ""
        }
        return if (extension.isBlank()) safeName else "$safeName.$extension"
    }
}

class CourseSelectionRepository(
    private val sessionManager: SessionManager,
    private val syncRepository: SyncRepository,
) {
    suspend fun listing(
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    ): ModuleEnvelope<CourseSelectionData> =
        when (strategy) {
            ModuleLoadStrategy.CacheOnly -> syncRepository.snapshot<CourseSelectionData>(ModuleKeys.CourseSelection)
                ?: throw LocalSnapshotMissingException(ModuleKeys.CourseSelection)
            ModuleLoadStrategy.CacheFirst -> syncRepository.snapshot<CourseSelectionData>(ModuleKeys.CourseSelection)
                ?: listing(ModuleLoadStrategy.NetworkFirst)
            ModuleLoadStrategy.NetworkFirst -> runCatching {
                val envelope = sessionManager.withAuthenticatedClient { AaProvider(it).fetchCourseSelection() }.copy(syncedAt = nowIso())
                syncRepository.saveSnapshot(ModuleKeys.CourseSelection, envelope)
                envelope
            }.getOrElse { error ->
                syncRepository.snapshot<CourseSelectionData>(ModuleKeys.CourseSelection) ?: throw error
            }
        }

    suspend fun select(courseKey: String, courseName: String? = null): CourseSelectionAttemptResult =
        sessionManager.withAuthenticatedClient { AaProvider(it).attemptCourseSelection(courseKey, courseName) }

    suspend fun select(target: CourseSelectionTarget): CourseSelectionAttemptResult =
        sessionManager.withAuthenticatedClient { AaProvider(it).attemptCourseSelections(listOf(target)) }

    suspend fun selectBatch(targets: List<CourseSelectionTarget>): CourseSelectionAttemptResult =
        sessionManager.withAuthenticatedClient { AaProvider(it).attemptCourseSelections(targets) }

    suspend fun listingGroup(groupName: String): ModuleEnvelope<CourseSelectionData> =
        sessionManager.withAuthenticatedClient { AaProvider(it).fetchCourseSelectionGroup(groupName) }

    suspend fun listingQuery(
        groupName: String?,
        courseQuery: String = "",
        sectionQuery: String = "",
    ): ModuleEnvelope<CourseSelectionData> =
        sessionManager.withAuthenticatedClient {
            AaProvider(it).fetchCourseSelectionQuery(
                groupName = groupName,
                courseQuery = courseQuery,
                sectionQuery = sectionQuery,
            )
        }

    suspend fun drop(courseKey: String, courseName: String? = null): CourseSelectionAttemptResult =
        sessionManager.withAuthenticatedClient { AaProvider(it).dropCourseSelection(courseKey, courseName) }

    suspend fun replace(
        targetCourseKey: String,
        dropCourseKey: String,
        targetCourseName: String? = null,
        dropCourseName: String? = null,
        targetGroupName: String? = null,
        targetCourseQuery: String = "",
        targetSectionQuery: String = "",
    ): CourseSelectionAttemptResult =
        sessionManager.withAuthenticatedClient {
            AaProvider(it).replaceCourseSelection(
                targetCourseKey = targetCourseKey,
                targetCourseName = targetCourseName,
                dropCourseKey = dropCourseKey,
                dropCourseName = dropCourseName,
                targetGroupName = targetGroupName,
                targetCourseQuery = targetCourseQuery,
                targetSectionQuery = targetSectionQuery,
            )
        }

    suspend fun submitCaptcha(challengeId: String, captcha: String): CourseSelectionAttemptResult =
        sessionManager.withAuthenticatedClient { AaProvider(it).submitCourseSelectionCaptcha(challengeId, captcha) }
}

@PublishedApi
internal fun nowIso(): String =
    OffsetDateTime.now(ZoneOffset.UTC).withNano(0).toString()

private fun nextTodoVersion(previous: String?): String {
    val now = OffsetDateTime.now(ZoneOffset.UTC)
    val last = previous?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
    return (if (last != null && !now.isAfter(last)) last.plusNanos(1) else now).toString()
}

private fun String?.blankToNull(): String? =
    this?.trim()?.takeIf { it.isNotBlank() }
