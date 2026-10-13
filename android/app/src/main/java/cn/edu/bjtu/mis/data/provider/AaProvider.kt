package cn.edu.bjtu.mis.data.provider

import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import cn.edu.bjtu.mis.data.parser.CourseSelectionAction
import cn.edu.bjtu.mis.data.parser.parseAcademicProgress
import cn.edu.bjtu.mis.data.parser.parseAcademicProgressDetailPath
import cn.edu.bjtu.mis.data.parser.ParsedCourseSelectionPage
import cn.edu.bjtu.mis.data.parser.parseCourseSelectionCaptcha
import cn.edu.bjtu.mis.data.parser.parseCourseSelectionPage
import cn.edu.bjtu.mis.data.parser.parseEmptyRooms
import cn.edu.bjtu.mis.data.parser.parseExams
import cn.edu.bjtu.mis.data.parser.parseScoreDetail
import cn.edu.bjtu.mis.data.parser.parseScorecardProgress
import cn.edu.bjtu.mis.data.parser.parseScores
import cn.edu.bjtu.mis.data.parser.parseStudentStatusProfile
import cn.edu.bjtu.mis.data.parser.parseTeachingAssessmentForm
import cn.edu.bjtu.mis.data.parser.parseTeachingAssessmentList
import cn.edu.bjtu.mis.data.parser.parseTimetable
import cn.edu.bjtu.mis.model.AcademicProgressData
import cn.edu.bjtu.mis.model.CoverageLevel
import cn.edu.bjtu.mis.model.CourseSelectionAttemptResult
import cn.edu.bjtu.mis.model.CourseSelectionCaptchaChallenge
import cn.edu.bjtu.mis.model.CourseSelectionCourse
import cn.edu.bjtu.mis.model.CourseSelectionData
import cn.edu.bjtu.mis.model.CourseSelectionTarget
import cn.edu.bjtu.mis.model.DefaultCourseSelectionGroupNames
import cn.edu.bjtu.mis.model.EmptyRoomData
import cn.edu.bjtu.mis.model.ExamData
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.model.ProgressiveModuleState
import cn.edu.bjtu.mis.model.ScoreDetailData
import cn.edu.bjtu.mis.model.ScoreData
import cn.edu.bjtu.mis.model.ScoreItem
import cn.edu.bjtu.mis.model.StudentProfileData
import cn.edu.bjtu.mis.model.TeachingAssessmentData
import cn.edu.bjtu.mis.model.TeachingAssessmentForm
import cn.edu.bjtu.mis.model.TeachingAssessmentSubmitResult
import cn.edu.bjtu.mis.model.TimetableData
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

private const val HISTORY_ALL_TERMS = "all"
private const val AA_RETRY_ATTEMPTS = 3
private const val AA_COURSE_SELECTION_PATH = "/course_selection/courseselecttask/selects/"
private const val AA_COURSE_SELECTION_ACTION_PATH = "/course_selection/courseselecttask/selects_action/"
private const val AA_COURSE_SELECTION_MAX_PER_PAGE = 500
private const val AA_COURSE_SELECTION_MAX_PAGE_COUNT = 80
private const val AA_SCORE_DETAIL_INLINE_LIMIT = 20
private const val AA_TEACHING_ASSESSMENT_LIST_PATH = "/teaching_assessment/stu/list/"
private val AA_COURSE_SELECTION_FALLBACK_GROUP_OPTIONS = DefaultCourseSelectionGroupNames
    .mapIndexed { index, groupName -> CourseSelectionGroupOption((index + 1).toString(), groupName) }

internal fun mergeScoreItems(items: Iterable<ScoreItem>): List<ScoreItem> =
    items.distinctBy { item ->
        listOf(
            item.term,
            item.courseName,
            item.credit,
            item.score,
            item.bonusScore,
            item.teacher,
            item.detail,
            item.detailPath,
        ).joinToString("|")
    }

private data class PendingCourseSelectionCaptcha(
    val targets: List<CourseSelectionTarget>,
    val inputName: String,
    val actionUrl: String,
    val fields: List<Pair<String, String>>,
)

private data class CourseSelectionGroupOption(
    val value: String,
    val label: String,
)

private object CourseSelectionGroupOptionCache {
    private val lock = Any()
    private var options: List<CourseSelectionGroupOption> = emptyList()

    fun snapshot(): List<CourseSelectionGroupOption> = synchronized(lock) { options }

    fun remember(discovered: List<CourseSelectionGroupOption>) {
        if (discovered.isEmpty()) return
        synchronized(lock) {
            options = (discovered + options)
                .distinctBy { it.label.ifBlank { it.value } }
        }
    }
}

private data class CourseSelectionActionPages(
    val pages: List<ParsedCourseSelectionPage>,
    val groupOptions: List<CourseSelectionGroupOption>,
)

private data class CourseSelectionPageInfo(
    val currentPage: Int?,
    val totalPages: Int?,
    val totalRecords: Int?,
)

private data class CourseSelectionQueryContext(
    val groupName: String? = null,
    val courseQuery: String = "",
    val sectionQuery: String = "",
) {
    val hasFilters: Boolean
        get() = groupName != null || courseQuery.isNotBlank() || sectionQuery.isNotBlank()
}

private object CourseSelectionCaptchaStore {
    val values = mutableMapOf<String, PendingCourseSelectionCaptcha>()
}

class AaProvider(
    private val client: BjtuHttpClient,
    private val aaBaseUrl: String = ProviderConstants.AA_BASE_URL,
) {
    private val courseSelectionUrl: String
        get() = "$aaBaseUrl$AA_COURSE_SELECTION_PATH"
    private val courseSelectionActionUrl: String
        get() = "$aaBaseUrl$AA_COURSE_SELECTION_ACTION_PATH"
    private val teachingAssessmentListUrl: String
        get() = "$aaBaseUrl$AA_TEACHING_ASSESSMENT_LIST_PATH"

    suspend fun fetchTimetable(term: String? = null, week: String? = null): ModuleEnvelope<TimetableData> {
        val html = getText("/course_selection/courseselect/stuschedule/")
        return ModuleEnvelope(
            module = "timetable",
            sourceSystem = "aa",
            coverage = CoverageLevel.Verified,
            sourceParams = buildJsonObject {
                term?.let { put("term", it) }
                week?.let { put("week", it) }
            },
            data = parseTimetable(html),
        )
    }

    suspend fun fetchCourseSelection(): ModuleEnvelope<CourseSelectionData> {
        val parsed = fetchCourseSelectionParsed(probeActionPages = false)
        return ModuleEnvelope(
            module = "course_selection",
            sourceSystem = "aa",
            coverage = CoverageLevel.Verified,
            data = parsed.data,
        )
    }

    suspend fun fetchCourseSelectionGroup(groupName: String): ModuleEnvelope<CourseSelectionData> =
        fetchCourseSelectionQuery(groupName = groupName)

    suspend fun fetchCourseSelectionQuery(
        groupName: String? = null,
        courseQuery: String = "",
        sectionQuery: String = "",
    ): ModuleEnvelope<CourseSelectionData> {
        val parsed = fetchCourseSelectionQueryParsed(
            groupName = groupName,
            courseQuery = courseQuery,
            sectionQuery = sectionQuery,
        )
        return ModuleEnvelope(
            module = "course_selection",
            sourceSystem = "aa",
            coverage = CoverageLevel.Verified,
            sourceParams = buildJsonObject {
                groupName?.trim()?.takeIf { it.isNotBlank() }?.let { put("group_name", it) }
                courseQuery.trim().takeIf { it.isNotBlank() }?.let { put("course_query", it) }
                sectionQuery.trim().takeIf { it.isNotBlank() }?.let { put("section_query", it) }
            },
            data = parsed.data,
        )
    }

    suspend fun fetchTeachingAssessmentList(): ModuleEnvelope<TeachingAssessmentData> {
        val html = getText(AA_TEACHING_ASSESSMENT_LIST_PATH)
        val parsed = parseTeachingAssessmentList(html, teachingAssessmentListUrl)
        return ModuleEnvelope(
            module = "teaching_assessment",
            sourceSystem = "aa",
            coverage = CoverageLevel.Verified,
            data = parsed,
        )
    }

    suspend fun fetchTeachingAssessmentForm(courseId: String): TeachingAssessmentForm {
        val cleanedId = courseId.trim()
        require(cleanedId.isNotBlank()) { "评教课程 ID 不能为空。" }
        val path = "/teaching_assessment/stu/$cleanedId/update/"
        val html = getText(path)
        return parseTeachingAssessmentForm(html, "$aaBaseUrl$path")
    }

    suspend fun submitTeachingAssessment(
        form: TeachingAssessmentForm,
        answerValues: Map<String, String>,
        commentValues: Map<String, String>,
    ): TeachingAssessmentSubmitResult {
        if (form.unsupportedMultiCount > 0) {
            return TeachingAssessmentSubmitResult(
                courseId = form.courseId,
                status = "unsupported",
                message = "该评教表包含暂不支持的多选题，请到原系统提交。",
                success = false,
            )
        }
        if (form.questions.isEmpty() && form.comments.isEmpty()) {
            return TeachingAssessmentSubmitResult(
                courseId = form.courseId,
                status = "empty_form",
                message = "未解析到可提交的评教题目。",
                success = false,
            )
        }
        val missing = form.questions.filter { question ->
            answerValues[question.name].isNullOrBlank()
        }
        if (missing.isNotEmpty()) {
            return TeachingAssessmentSubmitResult(
                courseId = form.courseId,
                status = "missing_answers",
                message = "还有 ${missing.size} 道单选题未选择。",
                success = false,
            )
        }

        val fields = form.fields.toMutableMap()
        form.questions.forEach { question ->
            fields[question.name] = answerValues.getValue(question.name)
        }
        form.comments.forEach { comment ->
            fields[comment.name] = commentValues[comment.name].orEmpty()
        }

        val response = if (form.method.lowercase() == "get") {
            client.getText(form.actionUrl, fields, headers = mapOf("Referer" to form.referer))
        } else {
            client.postForm(
                form.actionUrl,
                form = fields,
                headers = mapOf(
                    "Referer" to form.referer,
                    "Origin" to aaBaseUrl,
                ),
            )
        }
        val head = response.body.take(4096)
        if (response.url.contains("/client/login/") || (head.contains("用户登录") && head.contains("教学"))) {
            throw SessionExpiredException("教学支撑平台未登录，请重新登录。")
        }
        val list = parseTeachingAssessmentList(response.body, response.url)
        val refreshed = list.courses.firstOrNull { it.id == form.courseId }
        val success = response.body.contains("评教成功") ||
            refreshed?.canEvaluate == false ||
            refreshed?.status?.contains("已") == true
        return TeachingAssessmentSubmitResult(
            courseId = form.courseId,
            status = if (success) "success" else "submitted",
            message = if (success) "评教成功。" else "已提交评教请求，请刷新列表确认结果。",
            success = success,
            course = refreshed,
        )
    }

    suspend fun attemptCourseSelection(courseKey: String? = null, courseName: String? = null): CourseSelectionAttemptResult {
        val request = CourseSelectionTarget(courseKey?.trim().orEmpty(), courseName?.trim().orEmpty())
        return attemptCourseSelections(listOf(request))
    }

    suspend fun attemptCourseSelections(targets: List<CourseSelectionTarget>): CourseSelectionAttemptResult {
        val requested = targets.filter { it.key.isNotBlank() || it.courseName.isNotBlank() }
        if (requested.isEmpty()) {
            return CourseSelectionAttemptResult(status = "not_found", message = "未找到匹配课程。")
        }
        val queryContext = commonCourseSelectionQueryContext(requested)
            ?: return CourseSelectionAttemptResult(
                status = "unparseable",
                message = "批量目标来自不同课组或查询条件，无法合并提交入口。",
            )
        val parsed = fetchCourseSelectionParsedForContext(queryContext)
        val allCourses = parsed.data.availableCourses + parsed.data.selectedCourses
        val resolved = requested.map { target ->
            findCourseSelectionTarget(allCourses, target.key, target.courseName)
                ?: return CourseSelectionAttemptResult(
                    status = "not_found",
                    message = "未找到匹配课程：${target.courseName.ifBlank { target.key }}。",
                )
        }.distinctBy { it.key }
        val initiallySelected = resolved.filter { it.selected }
        val initiallySelectedKeys = initiallySelected.map { it.key }
        if (resolved.all { it.selected }) {
            return CourseSelectionAttemptResult(
                status = "already_selected",
                message = courseSelectionBatchMessage("课程已选中。", resolved),
                course = resolved.firstOrNull(),
                completedCourseKeys = resolved.map { it.key },
            )
        }
        val pending = resolved.filterNot { it.selected }
        val selectable = pending.filterNot { it.remaining != null && it.remaining <= 0 }
        val fullCourses = pending.filter { it.remaining != null && it.remaining <= 0 }
        if (selectable.isEmpty()) {
            return CourseSelectionAttemptResult(
                status = "no_remaining",
                message = courseSelectionSkippedMessage("课程余量为 0。", fullCourses),
                course = fullCourses.firstOrNull() ?: pending.firstOrNull(),
                completedCourseKeys = initiallySelectedKeys,
            )
        }
        val actions = selectable.map { course ->
            parsed.actions[course.key]
                ?: return CourseSelectionAttemptResult(
                    status = "unparseable",
                    message = parsed.data.submitError ?: "无法解析选课提交入口。",
                    course = course,
                    completedCourseKeys = initiallySelectedKeys,
                )
        }
        val action = combineCourseSelectionActions(actions)
            ?: return CourseSelectionAttemptResult(
                status = "unparseable",
                message = "无法合并多门课程的提交入口。",
                course = selectable.firstOrNull(),
                completedCourseKeys = initiallySelectedKeys,
            )
        val pendingTargets = selectable.map {
            CourseSelectionTarget(
                key = it.key,
                courseName = it.courseName,
                groupName = queryContext.groupName,
                courseQuery = queryContext.courseQuery,
                sectionQuery = queryContext.sectionQuery,
            )
        }
        if (requiresCourseSelectionCaptchaBeforeSubmit(action.actionUrl)) {
            val captcha = buildCourseSelectionCaptchaFromRefresh(action, pendingTargets)
                ?: return CourseSelectionAttemptResult(
                    status = "unparseable",
                    message = "无法获取选课验证码。",
                    course = selectable.firstOrNull(),
                    completedCourseKeys = initiallySelectedKeys,
                )
            return CourseSelectionAttemptResult(
                status = "captcha_required",
                message = courseSelectionSkippedMessage("需要输入验证码后继续提交。", fullCourses),
                course = selectable.firstOrNull(),
                captchaChallenge = captcha,
                completedCourseKeys = initiallySelectedKeys,
            )
        }
        val response = submitCourseSelectionAction(action.actionUrl, action.method, action.fieldPairs)
        buildCourseSelectionCaptcha(response.body, response.url, pendingTargets)?.let { captcha ->
            return CourseSelectionAttemptResult(
                status = "captcha_required",
                message = courseSelectionSkippedMessage("需要输入验证码后继续提交。", fullCourses),
                course = selectable.firstOrNull(),
                captchaChallenge = captcha,
                completedCourseKeys = initiallySelectedKeys,
            )
        }
        val refreshed = fetchCourseSelectionParsedForContext(queryContext).data
        val refreshedTargets = selectable.mapNotNull { target ->
            findCourseSelectionTarget(refreshed.selectedCourses + refreshed.availableCourses, target.key, target.courseName)
        }
        val completedAfterSubmit = selectable.filter { target ->
            findCourseSelectionTarget(refreshed.selectedCourses + refreshed.availableCourses, target.key, target.courseName)?.selected == true
        }
        val completedAfterSubmitKeys = initiallySelectedKeys + completedAfterSubmit.map { it.key }
        if (completedAfterSubmit.size == selectable.size) {
            return CourseSelectionAttemptResult(
                status = "success",
                message = courseSelectionSkippedMessage(courseSelectionBatchMessage("选课成功。", selectable), fullCourses),
                course = refreshedTargets.firstOrNull() ?: selectable.firstOrNull(),
                completedCourseKeys = completedAfterSubmitKeys,
            )
        }
        return CourseSelectionAttemptResult(
            status = "submitted",
            message = courseSelectionSkippedMessage("已提交选课请求，请刷新列表确认结果。", fullCourses),
            course = refreshedTargets.firstOrNull() ?: selectable.firstOrNull(),
            completedCourseKeys = completedAfterSubmitKeys,
        )
    }

    suspend fun submitCourseSelectionCaptcha(challengeId: String, captcha: String): CourseSelectionAttemptResult {
        val state = CourseSelectionCaptchaStore.values.remove(challengeId)
            ?: return CourseSelectionAttemptResult(status = "captcha_expired", message = "验证码上下文已失效，请重新尝试选课。")
        val fields = state.fields
            .filterNot { (key, _) -> key == state.inputName }
            .toMutableList()
            .also { it += state.inputName to captcha.trim() }
        val response = submitCourseSelectionAction(state.actionUrl, "post", fields)
        val queryContext = commonCourseSelectionQueryContext(state.targets)
            ?: CourseSelectionQueryContext()
        val refreshed = fetchCourseSelectionParsedForContext(queryContext).data
        val refreshedTargets = state.targets.mapNotNull { target ->
            findCourseSelectionTarget(refreshed.selectedCourses + refreshed.availableCourses, target.key, target.courseName)
        }
        val completedTargets = state.targets.filter { target ->
            findCourseSelectionTarget(refreshed.selectedCourses + refreshed.availableCourses, target.key, target.courseName)?.selected == true
        }
        val completedKeys = completedTargets.map { it.key }
        if (completedTargets.size == state.targets.size) {
            return CourseSelectionAttemptResult(
                status = "success",
                message = courseSelectionBatchMessage("选课成功。", refreshedTargets.ifEmpty { state.targets.map { CourseSelectionCourse(it.key, "selected", true, it.courseName) } }),
                course = refreshedTargets.firstOrNull(),
                completedCourseKeys = completedKeys,
            )
        }
        buildCourseSelectionCaptcha(response.body, response.url, state.targets)?.let { next ->
            return CourseSelectionAttemptResult(
                status = "captcha_required",
                message = "验证码未通过或需要再次输入。",
                course = refreshedTargets.firstOrNull(),
                captchaChallenge = next,
                completedCourseKeys = completedKeys,
            )
        }
        return CourseSelectionAttemptResult(
            status = "submitted",
            message = "验证码已提交，请刷新列表确认结果。",
            course = refreshedTargets.firstOrNull(),
            completedCourseKeys = completedKeys,
        )
    }

    suspend fun dropCourseSelection(courseKey: String? = null, courseName: String? = null): CourseSelectionAttemptResult {
        val parsed = fetchCourseSelectionParsed(probeActionPages = false)
        return dropCourseSelectionFromParsed(parsed, courseKey, courseName)
    }

    suspend fun replaceCourseSelection(
        targetCourseKey: String? = null,
        targetCourseName: String? = null,
        dropCourseKey: String? = null,
        dropCourseName: String? = null,
        targetGroupName: String? = null,
        targetCourseQuery: String = "",
        targetSectionQuery: String = "",
    ): CourseSelectionAttemptResult {
        val queryContext = CourseSelectionQueryContext(
            groupName = targetGroupName?.trim()?.takeIf { it.isNotBlank() },
            courseQuery = targetCourseQuery.trim(),
            sectionQuery = targetSectionQuery.trim(),
        )
        val parsed = fetchCourseSelectionParsedForContext(queryContext)
        val target = findCourseSelectionTarget(
            parsed.data.availableCourses + parsed.data.selectedCourses,
            targetCourseKey,
            targetCourseName,
        ) ?: return CourseSelectionAttemptResult(status = "not_found", message = "未找到目标课程。")
        if (target.selected) {
            return CourseSelectionAttemptResult(status = "replace_success", message = "目标课程已在已选列表中。", course = target)
        }
        if (target.remaining != null && target.remaining <= 0) {
            return CourseSelectionAttemptResult(status = "target_no_remaining", message = "目标课程余量为 0。", course = target)
        }

        val dropResult = dropCourseSelectionFromParsed(parsed, dropCourseKey, dropCourseName)
        if (dropResult.status != "drop_success") {
            return CourseSelectionAttemptResult(
                status = "drop_failed",
                message = dropResult.message ?: "退课失败，未继续抢目标课程。",
                course = dropResult.course,
            )
        }

        val selectResult = attemptCourseSelection(target.key, target.courseName)
        if (selectResult.status in setOf("success", "already_selected")) {
            return CourseSelectionAttemptResult(
                status = "replace_success",
                message = "换课成功。",
                course = selectResult.course ?: target,
            )
        }
        if (selectResult.status == "captcha_required") {
            return selectResult
        }

        val rollbackResult = attemptCourseSelection(
            courseKey = dropResult.course?.key ?: dropCourseKey,
            courseName = dropResult.course?.courseName ?: dropCourseName,
        )
        if (rollbackResult.status in setOf("success", "already_selected")) {
            return CourseSelectionAttemptResult(
                status = "rollback_success",
                message = "目标课程选课失败，已尝试把原课程选回。${selectResult.message ?: selectResult.status}",
                course = rollbackResult.course ?: dropResult.course,
            )
        }
        return CourseSelectionAttemptResult(
            status = "rollback_failed",
            message = "目标课程选课失败，且原课程回滚失败。选课结果：${selectResult.message ?: selectResult.status}；回滚结果：${rollbackResult.message ?: rollbackResult.status}",
            course = rollbackResult.course ?: dropResult.course,
        )
    }

    suspend fun fetchExams(term: String? = null): ModuleEnvelope<ExamData> {
        val html = getText("/examine/examplanstudent/stulist/", mapOf("zxjxjhh" to term))
        val parsed = parseExams(html, term)
        return ModuleEnvelope(
            module = "exams",
            sourceSystem = "aa",
            coverage = CoverageLevel.Verified,
            sourceParams = buildJsonObject { parsed.currentTerm?.let { put("term", it) } },
            data = parsed,
        )
    }

    suspend fun fetchScores(term: String? = null, ctype: String? = null, includeDetails: Boolean = true): ModuleEnvelope<ScoreData> {
        val requestedTerm = term?.takeIf { it.isNotBlank() }
        val params = mapOf("zxjxjhh" to requestedTerm, "ctype" to ctype)
        val html = runCatching { getText("/score/scores/stu/view/", params) }
            .getOrElse { error ->
                if (!isRetryableAaFailure(error)) throw error
                return ModuleEnvelope(
                    module = "scores",
                    sourceSystem = "aa",
                    coverage = CoverageLevel.Provisional,
                    sourceParams = buildJsonObject {
                        requestedTerm?.let { put("term", it) }
                        ctype?.let { put("ctype", it) }
                        put("fallback_reason", error.message.orEmpty())
                    },
                    data = ScoreData(currentTerm = requestedTerm),
                )
            }
        val retryErrors = mutableListOf<String>()
        var parsed = parseScores(html, requestedTerm)
        if (parsed.items.isEmpty() && requestedTerm == null && parsed.availableTerms.isNotEmpty()) {
            for (option in parsed.availableTerms.take(10)) {
                val candidate = option.value
                if (candidate.isBlank() || candidate == parsed.currentTerm) continue
                val retryParams = mapOf("zxjxjhh" to candidate, "ctype" to ctype)
                runCatching {
                    val retryHtml = getText("/score/scores/stu/view/", retryParams)
                    parseScores(retryHtml, candidate)
                }.onSuccess { retryParsed ->
                    if (retryParsed.items.isNotEmpty()) {
                        parsed = retryParsed
                        return@onSuccess
                    }
                }.onFailure { error ->
                    if (!isRetryableAaFailure(error)) throw error
                    retryErrors += "term=$candidate ${error.message.orEmpty()}".trim()
                }
                if (parsed.items.isNotEmpty()) break
            }
        }
        val detailErrors = if (includeDetails) {
            val (enriched, errors) = enrichScoreDetails(parsed)
            parsed = enriched
            errors
        } else {
            0
        }
        return ModuleEnvelope(
            module = "scores",
            sourceSystem = "aa",
            coverage = if ((retryErrors.isEmpty() || parsed.items.isNotEmpty()) && detailErrors == 0) {
                CoverageLevel.Verified
            } else {
                CoverageLevel.Provisional
            },
            sourceParams = buildJsonObject {
                parsed.currentTerm?.let { put("term", it) }
                ctype?.let { put("ctype", it) }
                if (retryErrors.isNotEmpty()) put("partial_error_count", retryErrors.size)
                if (detailErrors > 0) put("detail_error_count", detailErrors)
            },
            data = parsed,
        )
    }

    suspend fun fetchHistoryScores(term: String? = null): ModuleEnvelope<ScoreData> {
        val requestedTerm = term?.takeIf { it.isNotBlank() && it != HISTORY_ALL_TERMS }
        if (requestedTerm != null) {
            return fetchScores(term = requestedTerm, ctype = "ln", includeDetails = false).let { envelope ->
                envelope.copy(
                    module = "history_scores",
                    sourceParams = buildJsonObject {
                        put("term", requestedTerm)
                        put("ctype", "ln")
                    },
                )
            }
        }

        val termIndex = runCatching { fetchScoreIndex() }.getOrNull()
        val availableTerms = termIndex?.availableTerms.orEmpty()
        val termValues = availableTerms.map { it.value }.filter { it.isNotBlank() }.distinct()
        val errors = mutableListOf<String>()
        if (termValues.isNotEmpty()) {
            val combinedItems = mergeScoreItems(termValues
                .flatMap { termValue ->
                    runCatching { fetchScores(term = termValue, ctype = "ln", includeDetails = false) }
                        .getOrElse { error ->
                            if (!isRetryableAaFailure(error)) throw error
                            errors += "term=$termValue ${error.message.orEmpty()}".trim()
                            null
                        }
                        ?.let { envelope ->
                            if (envelope.coverage == CoverageLevel.Provisional && envelope.data.items.isEmpty()) {
                                errors += "term=$termValue unavailable"
                            }
                            envelope.data.items
                        }
                        .orEmpty()
                }
            )
            return ModuleEnvelope(
                module = "history_scores",
                sourceSystem = "aa",
                coverage = if (errors.isEmpty()) CoverageLevel.Verified else CoverageLevel.Provisional,
                sourceParams = buildJsonObject {
                    put("term", HISTORY_ALL_TERMS)
                    put("ctype", "ln")
                    if (errors.isNotEmpty()) put("partial_error_count", errors.size)
                },
                data = ScoreData(currentTerm = null, availableTerms = availableTerms, items = combinedItems),
            )
        }

        val seed = fetchScores(ctype = "ln", includeDetails = false)
        return seed.copy(
            module = "history_scores",
            coverage = seed.coverage,
            sourceParams = buildJsonObject {
                put("term", HISTORY_ALL_TERMS)
                put("ctype", "ln")
            },
            data = seed.data.copy(currentTerm = null),
        )
    }

    fun fetchHistoryScoresProgressive(term: String? = null): Flow<ProgressiveModuleState<ScoreData>> = flow {
        val requestedTerm = term?.takeIf { it.isNotBlank() && it != HISTORY_ALL_TERMS }
        if (requestedTerm != null) {
            val envelope = fetchHistoryScores(requestedTerm)
            emit(
                ProgressiveModuleState(
                    envelope = envelope,
                    loading = false,
                    complete = true,
                    loadedCount = envelope.data.items.size,
                    totalCount = envelope.data.items.size,
                )
            )
            return@flow
        }

        val termIndex = runCatching { fetchScoreIndex() }.getOrNull()
        val availableTerms = termIndex?.availableTerms.orEmpty()
        val termValues = availableTerms.map { it.value }.filter { it.isNotBlank() }.distinct()
        val errors = mutableListOf<String>()

        if (termValues.isEmpty()) {
            val seedSource = fetchScores(ctype = "ln", includeDetails = false)
            val seed = seedSource.copy(
                module = "history_scores",
                sourceParams = buildJsonObject {
                    put("term", HISTORY_ALL_TERMS)
                    put("ctype", "ln")
                },
                data = seedSource.data.copy(currentTerm = null),
            )
            emit(
                ProgressiveModuleState(
                    envelope = seed,
                    loading = false,
                    complete = true,
                    loadedCount = seed.data.items.size,
                    totalCount = seed.data.items.size,
                )
            )
            return@flow
        }

        var items = emptyList<ScoreItem>()

        fun currentEnvelope(): ModuleEnvelope<ScoreData> =
            ModuleEnvelope(
                module = "history_scores",
                sourceSystem = "aa",
                coverage = if (errors.isEmpty()) CoverageLevel.Verified else CoverageLevel.Provisional,
                sourceParams = buildJsonObject {
                    put("term", HISTORY_ALL_TERMS)
                    put("ctype", "ln")
                    if (errors.isNotEmpty()) put("partial_error_count", errors.size)
                },
                data = ScoreData(currentTerm = null, availableTerms = availableTerms, items = items),
            )

        emit(
            ProgressiveModuleState(
                envelope = currentEnvelope(),
                loading = true,
                loadedCount = 0,
                totalCount = termValues.size,
                errors = errors.toList(),
            )
        )

        termValues.forEachIndexed { index, termValue ->
            runCatching { fetchScores(term = termValue, ctype = "ln", includeDetails = false) }
                .onSuccess { envelope ->
                    if (envelope.coverage == CoverageLevel.Provisional && envelope.data.items.isEmpty()) {
                        errors += "term=$termValue unavailable"
                    }
                    items = mergeScoreItems(items + envelope.data.items)
                }
                .onFailure { error ->
                    if (!isRetryableAaFailure(error)) throw error
                    errors += "term=$termValue ${error.message.orEmpty()}".trim()
                }

            emit(
                ProgressiveModuleState(
                    envelope = currentEnvelope(),
                    loading = index < termValues.lastIndex,
                    complete = index == termValues.lastIndex,
                    loadedCount = index + 1,
                    totalCount = termValues.size,
                    errors = errors.toList(),
                )
            )
        }
    }

    suspend fun fetchScoreDetail(detailPath: String): ModuleEnvelope<ScoreDetailData> {
        val requestPath = aaRequestPath(detailPath)
        val html = getText(requestPath)
        return ModuleEnvelope(
            module = "score_detail",
            sourceSystem = "aa",
            coverage = CoverageLevel.Verified,
            sourceParams = buildJsonObject { put("detail_path", requestPath) },
            data = parseScoreDetail(html),
        )
    }

    private suspend fun enrichScoreDetails(data: ScoreData): Pair<ScoreData, Int> {
        var errorCount = 0
        var requestCount = 0
        val items = data.items.map { item ->
            val detailPath = item.detailPath
            if (detailPath.isNullOrBlank()) {
                item
            } else if (requestCount >= AA_SCORE_DETAIL_INLINE_LIMIT) {
                item
            } else {
                requestCount += 1
                runCatching { fetchScoreDetail(detailPath).data }
                    .fold(
                        onSuccess = { detail ->
                            if (detail.hasDisplayableScoreDetail()) item.copy(detailData = detail) else item
                        },
                        onFailure = {
                            errorCount += 1
                            item
                        },
                    )
            }
        }
        return data.copy(items = items) to errorCount
    }

    private fun ScoreDetailData.hasDisplayableScoreDetail(): Boolean =
        fields.isNotEmpty() || tables.isNotEmpty() || !rawText.isNullOrBlank()

    suspend fun fetchStudentProfile(): ModuleEnvelope<StudentProfileData> {
        val html = getText("/school_census/schoolcensus/stuview/")
        val parsed = parseStudentStatusProfile(html).let { profile ->
            if (profile.avatarUrl != null && !profile.avatarUrl.startsWith("http") && !profile.avatarUrl.startsWith("data:")) {
                profile.copy(avatarUrl = "${ProviderConstants.AA_BASE_URL}/${profile.avatarUrl.trimStart('/')}")
            } else {
                profile
            }
        }
        return ModuleEnvelope(
            module = "profile",
            sourceSystem = "aa",
            coverage = if (parsed.fields.isNotEmpty()) CoverageLevel.Verified else CoverageLevel.Provisional,
            data = parsed,
        )
    }

    suspend fun fetchAcademicProgress(): ModuleEnvelope<AcademicProgressData> {
        val listHtml = runCatching { getText("/school_census/schooltraininfo/studylist/") }
            .getOrElse { error ->
                if (!isRetryableAaFailure(error)) throw error
                return fetchAcademicProgressFallback(error)
            }
        val detailPath = parseAcademicProgressDetailPath(listHtml)
        val parsed = if (detailPath.isNullOrBlank()) {
            parseAcademicProgress("")
        } else {
            runCatching { parseAcademicProgress(getText(detailPath)) }
                .getOrElse { error ->
                    if (!isRetryableAaFailure(error)) throw error
                    return fetchAcademicProgressFallback(error)
                }
        }
        return ModuleEnvelope(
            module = "academic_progress",
            sourceSystem = "aa",
            coverage = if (parsed.buckets.isNotEmpty() || parsed.courses.isNotEmpty()) CoverageLevel.Verified else CoverageLevel.Provisional,
            sourceParams = buildJsonObject { detailPath?.let { put("detail_path", it) } },
            data = parsed,
        )
    }

    private suspend fun fetchAcademicProgressFallback(error: Throwable): ModuleEnvelope<AcademicProgressData> {
        val scores = runCatching { fetchHistoryScores().data }.getOrNull()
        val scorecardHtml = runCatching { getText("/score/scorecard/stu/") }.getOrDefault("")
        return ModuleEnvelope(
            module = "academic_progress",
            sourceSystem = "aa",
            coverage = CoverageLevel.Provisional,
            sourceParams = buildJsonObject {
                put("fallback_reason", error.message.orEmpty())
                if (scores != null) put("fallback_source", "scores")
            },
            data = parseScorecardProgress(scorecardHtml, scores),
        )
    }

    suspend fun fetchEmptyRooms(
        term: String? = null,
        week: String? = null,
        building: String? = null,
        room: String? = null,
    ): ModuleEnvelope<EmptyRoomData> {
        val params = mutableMapOf<String, String?>()
        term?.let { params["zxjxjhh"] = it }
        week?.takeIf { it.isNotBlank() }?.let { params["zc"] = it }
        building?.let { params["jxlh"] = it }
        room?.let { params["jash"] = it }
        if (params.isNotEmpty()) {
            params["has_advance_query"] = ""
        }
        // The shared HTTP client's params helper omits blank values. This form needs
        // explicit empty values to clear building/room filters and enable advance query.
        val url = (aaBaseUrl + "/classroom/timeholdresult/room_view/").toHttpUrl().newBuilder().apply {
            params.forEach { (key, value) -> if (value != null) addQueryParameter(key, value) }
        }.build()
        val html = getText(url.toString())
        val parsed = parseEmptyRooms(
            html,
            mapOf("term" to term, "week" to params["zc"], "building" to building, "room" to room)
                .filterValues { it != null },
        )
        return ModuleEnvelope(
            module = "empty_rooms",
            sourceSystem = "aa",
            coverage = CoverageLevel.Verified,
            sourceParams = buildJsonObject {
                parsed.query.forEach { (key, value) -> value?.let { put(key, it) } }
            },
            data = parsed,
        )
    }

    private suspend fun fetchScoreIndex(): ScoreData =
        parseScores(getText("/score/scores/stu/view/"), null)

    private suspend fun fetchCourseSelectionParsed(probeActionPages: Boolean = true): ParsedCourseSelectionPage {
        val shellHtml = getText(AA_COURSE_SELECTION_PATH)
        val shell = parseCourseSelectionPage(shellHtml, courseSelectionUrl)
        val shellGroupOptions = courseSelectionGroupOptions(shellHtml)

        val pages = mutableListOf(shell)
        val knownGroupOptions = mutableListOf<CourseSelectionGroupOption>()
        knownGroupOptions += shellGroupOptions
        var baseAvailableCount = shell.data.availableCourses.size
        if (probeActionPages || shell.needsCourseSelectionActionProbe()) {
            val actionParams = courseSelectionQueryParams()
            val actionPages = runCatching { fetchCourseSelectionActionPages(actionParams) }.getOrNull()
            if (actionPages != null) {
                pages += actionPages.pages
                baseAvailableCount = maxOf(
                    baseAvailableCount,
                    mergeCourseSelectionPages(actionPages.pages).data.availableCourses.size,
                )
                knownGroupOptions += actionPages.groupOptions
            }
        }
        val discovered = knownGroupOptions.distinctBy { it.value.ifBlank { it.label } }
        CourseSelectionGroupOptionCache.remember(discovered)
        val fallbackOptions = if (discovered.isEmpty() && baseAvailableCount > 0) {
            AA_COURSE_SELECTION_FALLBACK_GROUP_OPTIONS
        } else {
            emptyList()
        }
        return mergeCourseSelectionPages(
            pages = pages,
            knownGroupOptions = (
                discovered +
                    fallbackOptions
                ).distinctBy { it.value.ifBlank { it.label } },
        )
    }

    private suspend fun fetchCourseSelectionQueryParsed(
        groupName: String? = null,
        courseQuery: String = "",
        sectionQuery: String = "",
    ): ParsedCourseSelectionPage {
        val groupOption = groupName
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { courseSelectionKnownGroupOption(it) ?: CourseSelectionGroupOption(it, it) }
        val actionPages = fetchCourseSelectionActionPages(
            courseSelectionQueryParams(
                groupOption = groupOption,
                courseQuery = courseQuery,
                sectionQuery = sectionQuery,
            ),
            groupOption,
        )
        val discoveredGroupOptions = actionPages.groupOptions
        CourseSelectionGroupOptionCache.remember(discoveredGroupOptions)
        return mergeCourseSelectionPages(actionPages.pages)
            .withCourseSelectionGroupNames(
                (discoveredGroupOptions + AA_COURSE_SELECTION_FALLBACK_GROUP_OPTIONS)
                    .distinctBy { it.value.ifBlank { it.label } },
            )
    }

    private suspend fun fetchCourseSelectionParsedForContext(
        context: CourseSelectionQueryContext,
    ): ParsedCourseSelectionPage =
        if (context.hasFilters) {
            fetchCourseSelectionQueryParsed(
                groupName = context.groupName,
                courseQuery = context.courseQuery,
                sectionQuery = context.sectionQuery,
            )
        } else {
            fetchCourseSelectionParsed(probeActionPages = false)
        }

    private fun CourseSelectionTarget.courseSelectionQueryContext(): CourseSelectionQueryContext =
        CourseSelectionQueryContext(
            groupName = groupName?.trim()?.takeIf { it.isNotBlank() },
            courseQuery = courseQuery.trim(),
            sectionQuery = sectionQuery.trim(),
        )

    private fun commonCourseSelectionQueryContext(
        targets: List<CourseSelectionTarget>,
    ): CourseSelectionQueryContext? {
        val contexts = targets.map { it.courseSelectionQueryContext() }.distinct()
        return contexts.singleOrNull()
    }

    private fun ParsedCourseSelectionPage.needsCourseSelectionActionProbe(): Boolean =
        (data.availableCourses.isEmpty() && data.selectedCourses.isEmpty()) ||
            (data.availableCourses.isNotEmpty() && actions.isEmpty())

    private fun courseSelectionAjaxHeaders(): Map<String, String> = mapOf(
        "Referer" to courseSelectionUrl,
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "text/html, */*; q=0.01",
    )

    private fun courseSelectionQueryParams(
        groupOption: CourseSelectionGroupOption? = null,
        courseQuery: String = "",
        sectionQuery: String = "",
    ): LinkedHashMap<String, String> =
        linkedMapOf(
            "gname" to (groupOption?.value?.ifBlank { groupOption.label }).orEmpty(),
            "kch" to courseQuery.trim(),
            "kxh" to sectionQuery.trim(),
            "action" to "load",
            "order" to "",
            "iframe" to "school",
            "submit" to "",
            "has_advance_query" to "",
            "perpage" to AA_COURSE_SELECTION_MAX_PER_PAGE.toString(),
        )

    private fun courseSelectionPageParams(
        params: Map<String, String>,
        page: Int,
    ): LinkedHashMap<String, String> =
        linkedMapOf<String, String>().apply {
            putAll(params)
            put("page", page.coerceAtLeast(1).toString())
            putIfAbsent("perpage", AA_COURSE_SELECTION_MAX_PER_PAGE.toString())
        }

    private suspend fun fetchCourseSelectionActionPages(
        params: LinkedHashMap<String, String>,
        groupOption: CourseSelectionGroupOption? = null,
    ): CourseSelectionActionPages {
        val pages = mutableListOf<ParsedCourseSelectionPage>()
        val groupOptions = mutableListOf<CourseSelectionGroupOption>()

        fun parseActionPage(html: String, pageParams: Map<String, String>): ParsedCourseSelectionPage {
            val parsed = parseCourseSelectionPage(html, courseSelectionActionUrlWith(pageParams))
            return groupOption?.let { parsed.withCourseSelectionGroupName(it.label) } ?: parsed
        }

        val firstHtml = getText(
            courseSelectionActionUrlWith(params),
            headers = courseSelectionAjaxHeaders(),
        )
        groupOptions += courseSelectionGroupOptions(firstHtml)
        pages += parseActionPage(firstHtml, params)

        val firstInfo = courseSelectionPageInfo(firstHtml)
        val totalPages = firstInfo.totalPages
            ?.coerceAtLeast(1)
            ?.coerceAtMost(AA_COURSE_SELECTION_MAX_PAGE_COUNT)
            ?: 1
        if (totalPages <= 1) {
            return CourseSelectionActionPages(
                pages = pages,
                groupOptions = groupOptions.distinctBy { it.value.ifBlank { it.label } },
            )
        }

        for (page in 2..totalPages) {
            val pageParams = courseSelectionPageParams(params, page)
            val html = runCatching {
                getText(
                    courseSelectionActionUrlWith(pageParams),
                    headers = courseSelectionAjaxHeaders(),
                )
            }.getOrNull() ?: continue
            groupOptions += courseSelectionGroupOptions(html)
            pages += parseActionPage(html, pageParams)
            val merged = mergeCourseSelectionPages(pages)
            val totalRecords = firstInfo.totalRecords
            if (totalRecords != null && merged.data.availableCourses.size + merged.data.selectedCourses.size >= totalRecords) {
                break
            }
        }

        return CourseSelectionActionPages(
            pages = pages,
            groupOptions = groupOptions.distinctBy { it.value.ifBlank { it.label } },
        )
    }

    private fun courseSelectionPageInfo(html: String): CourseSelectionPageInfo {
        val document = Jsoup.parse(html)
        val text = document.text()
        val pageFromControl = document.selectFirst("#thepage, input[name=page]")
            ?.attr("value")
            ?.trim()
            ?.toIntOrNull()
        val currentAndTotal = Regex("""\u9875\u6b21\s*[:\uff1a]\s*(\d+)\s*/\s*(\d+)""")
            .find(text)
        val totalRecords = Regex("""\u5171\u8ba1\s*(\d+)\s*\u6761""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        return CourseSelectionPageInfo(
            currentPage = currentAndTotal
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: pageFromControl,
            totalPages = currentAndTotal
                ?.groupValues
                ?.getOrNull(2)
                ?.toIntOrNull(),
            totalRecords = totalRecords,
        )
    }

    private fun courseSelectionKnownGroupOption(groupName: String): CourseSelectionGroupOption? {
        val normalized = groupName.trim()
        return (AA_COURSE_SELECTION_FALLBACK_GROUP_OPTIONS + CourseSelectionGroupOptionCache.snapshot())
            .firstOrNull { option ->
                option.label == normalized || option.value == normalized
            }
    }

    private fun courseSelectionActionUrlWith(params: Map<String, String>): String {
        val query = params.entries.joinToString("&") { (key, value) ->
            "${courseSelectionQueryPart(key)}=${courseSelectionQueryPart(value)}"
        }
        return "$courseSelectionActionUrl?$query"
    }

    private fun courseSelectionQueryPart(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun mergeCourseSelectionPages(
        pages: List<ParsedCourseSelectionPage>,
        knownGroupOptions: List<CourseSelectionGroupOption> = emptyList(),
    ): ParsedCourseSelectionPage {
        val selected = pages
            .flatMap { it.data.selectedCourses }
            .mergeCourseSelectionCourses()
        val selectedKeys = selected.map { it.key }.toSet()
        val available = pages
            .flatMap { it.data.availableCourses }
            .filterNot { it.key in selectedKeys }
            .mergeCourseSelectionCourses()
        val actions = linkedMapOf<String, CourseSelectionAction>()
        pages.forEach { page ->
            page.actions.forEach { (key, action) ->
                if (key !in selectedKeys && key !in actions) actions[key] = action
            }
        }
        val dropActions = linkedMapOf<String, CourseSelectionAction>()
        pages.forEach { page ->
            page.dropActions.forEach { (key, action) ->
                if (key !in dropActions) dropActions[key] = action
            }
        }
        return ParsedCourseSelectionPage(
            data = CourseSelectionData(
                selectedCourses = selected,
                availableCourses = available,
                courseGroupNames = courseSelectionGroupNames(knownGroupOptions, selected + available),
                canSubmit = actions.isNotEmpty(),
                submitError = pages.firstNotNullOfOrNull { it.data.submitError },
            ),
            actions = actions,
            dropActions = dropActions,
        )
    }

    private fun courseSelectionGroupOptions(html: String): List<CourseSelectionGroupOption> {
        val document = Jsoup.parse(html)
        val selectOptions = document
            .select("select")
            .filter(::isCourseSelectionGroupSelect)
            .flatMap { select -> select.select("option") }
            .mapNotNull { option ->
                val label = cleanCourseSelectionGroupName(option.text())
                label?.let { CourseSelectionGroupOption(option.attr("value").trim().ifBlank { label }, label) }
            }
        val dropdownOptions = document
            .select(".bootstrap-select, .btn-group, .dropdown")
            .filter { dropdown ->
                containsCourseSelectionGroupName(dropdown.text()) ||
                    dropdown.select("button[title*=\u8bfe\u7ec4\u540d\u79f0], button[data-id*=gname], button[aria-label*=\u8bfe\u7ec4\u540d\u79f0]").isNotEmpty()
            }
            .flatMap { dropdown -> dropdown.select("li") }
            .mapNotNull { item ->
                val label = cleanCourseSelectionGroupName(
                    item.attr("data-value")
                        .ifBlank { item.selectFirst(".text")?.text().orEmpty() }
                        .ifBlank { item.text() },
                )
                label?.let {
                    CourseSelectionGroupOption(
                        item.attr("data-value").trim().ifBlank { label },
                        label,
                    )
                }
            }
        return (selectOptions + dropdownOptions)
            .distinctBy { it.value.ifBlank { it.label } }
    }

    private fun isCourseSelectionGroupSelect(select: Element): Boolean {
        val markerAttributes = listOf(
            select.attr("name"),
            select.id(),
            select.attr("title"),
            select.attr("data-id"),
            select.attr("data-none-selected-text"),
            select.attr("data-live-search-placeholder"),
            select.attr("aria-label"),
            select.attr("placeholder"),
        )
        if (markerAttributes.any(::isCourseSelectionGroupMarker)) return true
        if (select.select("option").any { option ->
                containsCourseSelectionGroupName(option.text()) ||
                    containsCourseSelectionGroupName(option.attr("value"))
            }
        ) {
            return true
        }
        return listOfNotNull(select.previousElementSibling(), select.parent())
            .any { element -> containsCourseSelectionGroupName(element.ownText()) }
    }

    private fun isCourseSelectionGroupMarker(value: String): Boolean =
        value.contains("gname", ignoreCase = true) || containsCourseSelectionGroupName(value)

    private fun containsCourseSelectionGroupName(value: String): Boolean =
        value.contains("\u8bfe\u7ec4\u540d\u79f0")

    private fun cleanCourseSelectionGroupName(value: String): String? {
        val cleaned = value.trim()
        return cleaned.takeIf { candidate ->
            candidate.isNotBlank() &&
                candidate != "--" &&
                !containsCourseSelectionGroupName(candidate) &&
                !candidate.contains("course group", ignoreCase = true)
        }
    }

    private fun ParsedCourseSelectionPage.withCourseSelectionGroupName(groupName: String): ParsedCourseSelectionPage {
        val cleanedGroupName = groupName.trim().takeIf { it.isNotBlank() } ?: return this
        fun CourseSelectionCourse.withGroupName(): CourseSelectionCourse =
            if (this.groupName.isNullOrBlank()) copy(groupName = cleanedGroupName) else this
        return copy(
            data = data.copy(
                courseGroupNames = courseSelectionGroupNames(
                    listOf(CourseSelectionGroupOption(cleanedGroupName, cleanedGroupName)),
                    data.selectedCourses + data.availableCourses,
                ),
                selectedCourses = data.selectedCourses.map { it.withGroupName() },
                availableCourses = data.availableCourses.map { it.withGroupName() },
            ),
        )
    }

    private fun ParsedCourseSelectionPage.withCourseSelectionGroupNames(
        groupOptions: List<CourseSelectionGroupOption>,
    ): ParsedCourseSelectionPage =
        copy(data = data.copy(courseGroupNames = courseSelectionGroupNames(groupOptions, data.selectedCourses + data.availableCourses)))

    private fun courseSelectionGroupNames(
        groupOptions: List<CourseSelectionGroupOption>,
        courses: List<CourseSelectionCourse>,
    ): List<String> =
        (groupOptions.map { it.label } + courses.mapNotNull { it.groupName })
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    private fun Iterable<CourseSelectionCourse>.mergeCourseSelectionCourses(): List<CourseSelectionCourse> {
        val merged = linkedMapOf<String, CourseSelectionCourse>()
        forEach { course ->
            val existing = merged[course.key]
            merged[course.key] = if (existing == null) {
                course
            } else {
                mergeCourseSelectionCourse(existing, course)
            }
        }
        return merged.values.toList()
    }

    private fun mergeCourseSelectionCourse(
        existing: CourseSelectionCourse,
        candidate: CourseSelectionCourse,
    ): CourseSelectionCourse {
        val candidateGroupName = candidate.groupName?.takeIf { it.isNotBlank() }
        return if (existing.groupName.isNullOrBlank() && candidateGroupName != null) {
            existing.copy(groupName = candidateGroupName)
        } else {
            existing
        }
    }

    private fun combineCourseSelectionActions(actions: List<CourseSelectionAction>): CourseSelectionAction? {
        val first = actions.firstOrNull() ?: return null
        if (actions.any { it.actionUrl != first.actionUrl || !it.method.equals(first.method, ignoreCase = true) }) {
            return null
        }
        val fieldPairs = mutableListOf<Pair<String, String>>()
        actions.forEach { action ->
            action.fieldPairs.forEach { pair ->
                if (pair !in fieldPairs) fieldPairs += pair
            }
        }
        return CourseSelectionAction(
            actionUrl = first.actionUrl,
            method = first.method,
            fields = fieldPairs.toMap(),
            fieldPairs = fieldPairs,
        )
    }

    private fun requiresCourseSelectionCaptchaBeforeSubmit(actionUrl: String): Boolean =
        actionUrl.contains("/course_selection/courseselecttask/selects_action/", ignoreCase = true) &&
            actionUrl.contains("action=submit", ignoreCase = true)

    private fun courseSelectionBatchMessage(prefix: String, courses: List<CourseSelectionCourse>): String {
        if (courses.size <= 1) return prefix
        val names = courses.take(3).joinToString("、") { it.courseName.ifBlank { it.key } }
        val suffix = if (courses.size > 3) " 等" else ""
        return "$prefix 共 ${courses.size} 门课程：$names$suffix。"
    }

    private fun courseSelectionSkippedMessage(message: String, skippedFullCourses: List<CourseSelectionCourse>): String {
        if (skippedFullCourses.isEmpty()) return message
        val names = skippedFullCourses.take(3).joinToString("、") { it.courseName.ifBlank { it.key } }
        val suffix = if (skippedFullCourses.size > 3) " 等" else ""
        return "$message 已跳过 ${skippedFullCourses.size} 门满员课程：$names$suffix。"
    }

    private suspend fun dropCourseSelectionFromParsed(
        parsed: ParsedCourseSelectionPage,
        courseKey: String?,
        courseName: String?,
    ): CourseSelectionAttemptResult {
        val target = findCourseSelectionTarget(parsed.data.selectedCourses, courseKey, courseName)
            ?: return CourseSelectionAttemptResult(status = "not_selected", message = "未找到要退的已选课程。")
        val action = parsed.dropActions[target.key]
            ?: return CourseSelectionAttemptResult(status = "unparseable", message = "无法解析退课入口。", course = target)
        val response = submitCourseSelectionAction(action.actionUrl, action.method, action.fieldPairs)
        val refreshed = fetchCourseSelection().data
        val refreshedTarget = findCourseSelectionTarget(
            refreshed.selectedCourses + refreshed.availableCourses,
            target.key,
            target.courseName,
        )
        if (refreshedTarget?.selected != true) {
            return CourseSelectionAttemptResult(status = "drop_success", message = "退课成功。", course = target)
        }
        return CourseSelectionAttemptResult(
            status = "drop_failed",
            message = response.body.takeIf { it.isNotBlank() }?.take(200) ?: "已提交退课请求，但课程仍在已选列表中。",
            course = refreshedTarget,
        )
    }

    private fun findCourseSelectionTarget(
        courses: List<CourseSelectionCourse>,
        courseKey: String?,
        courseName: String?,
    ): CourseSelectionCourse? {
        val key = courseKey?.trim().orEmpty()
        if (key.isNotBlank()) {
            courses.firstOrNull { it.key == key }?.let { return it }
        }
        val name = courseName?.trim().orEmpty()
        if (name.isBlank()) return null
        val normalized = name.normalizedCourseSelectionText()
        return courses.firstOrNull { course ->
            val candidate = course.courseName.normalizedCourseSelectionText()
            candidate == normalized || candidate.contains(normalized)
        }
    }

    private suspend fun submitCourseSelectionAction(
        actionUrl: String,
        method: String,
        fields: List<Pair<String, String>>,
    ): cn.edu.bjtu.mis.data.network.TextResponse {
        val response = if (method.lowercase() == "get") {
            client.getText(actionUrl, fields.toMap(), headers = mapOf("Referer" to courseSelectionUrl))
        } else {
            client.postForm(
                actionUrl,
                form = fields,
                headers = mapOf(
                    "Referer" to courseSelectionUrl,
                    "Origin" to aaBaseUrl,
                ),
            )
        }
        val head = response.body.take(4096)
        if (response.url.contains("/client/login/") || (head.contains("用户登录") && head.contains("教学"))) {
            throw SessionExpiredException("教学支撑平台未登录，请重新登录。")
        }
        return response
    }

    private suspend fun buildCourseSelectionCaptcha(
        html: String,
        pageUrl: String,
        targets: List<CourseSelectionTarget>,
    ): CourseSelectionCaptchaChallenge? {
        val form = parseCourseSelectionCaptcha(html, pageUrl)
        val imageUrl = form.imageUrl ?: return null
        val inputName = form.inputName ?: return null
        val actionUrl = form.fields["__action__"] ?: pageUrl
        val fields = form.fields.filterKeys { it != "__action__" }.toList()
        return buildCourseSelectionCaptchaChallenge(
            imageUrl = imageUrl,
            referer = pageUrl,
            inputName = inputName,
            actionUrl = actionUrl,
            fields = fields,
            targets = targets,
            prompt = form.prompt,
        )
    }

    private suspend fun buildCourseSelectionCaptchaFromRefresh(
        action: CourseSelectionAction,
        targets: List<CourseSelectionTarget>,
    ): CourseSelectionCaptchaChallenge? {
        val refreshUrl = resolveUrl("$aaBaseUrl/", "/captcha/refresh/")
        val response = client.getText(
            refreshUrl,
            headers = mapOf(
                "Referer" to courseSelectionUrl,
                "X-Requested-With" to "XMLHttpRequest",
            ),
        )
        val captcha = parseCourseSelectionCaptchaRefresh(response.body) ?: return null
        val key = captcha.first ?: return null
        val imageUrl = captcha.second ?: "/captcha/image/$key/"
        val fields = action.fieldPairs
            .filterNot { (name, _) -> name == "hashkey" || name == "answer" }
            .toMutableList()
            .also { it += "hashkey" to key }
        return buildCourseSelectionCaptchaChallenge(
            imageUrl = resolveUrl(response.url, imageUrl),
            referer = courseSelectionUrl,
            inputName = "answer",
            actionUrl = action.actionUrl,
            fields = fields,
            targets = targets,
            prompt = "请输入验证码后继续提交选课。",
        )
    }

    private suspend fun buildCourseSelectionCaptchaChallenge(
        imageUrl: String,
        referer: String,
        inputName: String,
        actionUrl: String,
        fields: List<Pair<String, String>>,
        targets: List<CourseSelectionTarget>,
        prompt: String?,
    ): CourseSelectionCaptchaChallenge {
        val imageDataUrl = if (imageUrl.startsWith("data:")) {
            imageUrl
        } else {
            val image = client.getBytes(imageUrl, headers = mapOf("Referer" to referer))
            val mimeType = image.headers["Content-Type"]?.substringBefore(";")?.trim().orEmpty()
                .ifBlank { "image/png" }
            "data:$mimeType;base64," + Base64.getEncoder().encodeToString(image.body)
        }
        val challengeId = UUID.randomUUID().toString()
        CourseSelectionCaptchaStore.values[challengeId] = PendingCourseSelectionCaptcha(
            targets = targets,
            inputName = inputName,
            actionUrl = actionUrl,
            fields = fields,
        )
        return CourseSelectionCaptchaChallenge(
            challengeId = challengeId,
            imageDataUrl = imageDataUrl,
            prompt = prompt,
            fetchedAt = OffsetDateTime.now(ZoneOffset.UTC).withNano(0).toString(),
        )
    }

    private fun parseCourseSelectionCaptchaRefresh(body: String): Pair<String?, String?>? {
        val obj = runCatching { AppJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        fun stringValue(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { key ->
                obj[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            }
        val key = stringValue("key", "hashkey", "captcha_key")
        val imageUrl = stringValue("image_url", "image", "url", "src")
        return key to imageUrl
    }

    private fun resolveUrl(base: String, value: String): String =
        URI(base).resolve(value).toString()

    private suspend fun getText(
        path: String,
        params: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): String {
        var lastError: IOException? = null
        repeat(AA_RETRY_ATTEMPTS) { attempt ->
            try {
                return getTextOnce(path, params, headers)
            } catch (error: IOException) {
                lastError = error
                if (!isRetryableAaFailure(error) || attempt == AA_RETRY_ATTEMPTS - 1) throw error
                delay(400L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("AA request failed")
    }

    private suspend fun getTextOnce(
        path: String,
        params: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): String {
        val url = if (path.startsWith("http")) path else aaBaseUrl + path
        val response = client.getText(url, params, headers)
        val head = response.body.take(4096)
        if (response.url.contains("/client/login/") || (head.contains("用户登录") && head.contains("教学支撑平台"))) {
            throw SessionExpiredException("教学支撑平台未登录，请重新登录。")
        }
        return response.body
    }

    private fun isRetryableAaFailure(error: Throwable): Boolean {
        if (error !is IOException) return false
        val code = Regex("""HTTP\s+(\d{3})""")
            .find(error.message.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        return code == null || code >= 500
    }

    private fun aaRequestPath(pathOrUrl: String): String {
        val resolved = URI(ProviderConstants.AA_BASE_URL + "/").resolve(pathOrUrl.trim())
        if (resolved.scheme !in setOf("http", "https") || resolved.host != "aa.bjtu.edu.cn") {
            throw IllegalArgumentException("成绩详情链接不是 AA 教学支撑平台地址。")
        }
        return buildString {
            append(resolved.rawPath.ifBlank { "/" })
            if (!resolved.rawQuery.isNullOrBlank()) {
                append("?")
                append(resolved.rawQuery)
            }
        }
    }
}

private fun String.normalizedCourseSelectionText(): String =
    trim().lowercase().replace(Regex("""\s+"""), "")
