package cn.edu.bjtu.mis.data.parser

import cn.edu.bjtu.mis.model.AcademicProgressCourse
import cn.edu.bjtu.mis.model.AcademicProgressData
import cn.edu.bjtu.mis.model.CourseEntry
import cn.edu.bjtu.mis.model.CourseSelectionCourse
import cn.edu.bjtu.mis.model.CourseSelectionData
import cn.edu.bjtu.mis.model.CreditBucket
import cn.edu.bjtu.mis.model.CreditSummary
import cn.edu.bjtu.mis.model.EmptyRoomData
import cn.edu.bjtu.mis.model.EmptyRoomRow
import cn.edu.bjtu.mis.model.EmptyRoomSlotHeader
import cn.edu.bjtu.mis.model.ExamData
import cn.edu.bjtu.mis.model.ExamItem
import cn.edu.bjtu.mis.model.ProfileField
import cn.edu.bjtu.mis.model.ProfileSection
import cn.edu.bjtu.mis.model.ScoreData
import cn.edu.bjtu.mis.model.ScoreDetailData
import cn.edu.bjtu.mis.model.ScoreDetailField
import cn.edu.bjtu.mis.model.ScoreDetailTable
import cn.edu.bjtu.mis.model.ScoreItem
import cn.edu.bjtu.mis.model.StudentProfileData
import cn.edu.bjtu.mis.model.TermOption
import cn.edu.bjtu.mis.model.TeachingAssessmentComment
import cn.edu.bjtu.mis.model.TeachingAssessmentCourse
import cn.edu.bjtu.mis.model.TeachingAssessmentData
import cn.edu.bjtu.mis.model.TeachingAssessmentForm
import cn.edu.bjtu.mis.model.TeachingAssessmentOption
import cn.edu.bjtu.mis.model.TeachingAssessmentQuestion
import cn.edu.bjtu.mis.model.TimetableData
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

fun parseSelectOptions(
    document: Element,
    fieldName: String,
    includeBlank: Boolean = false,
): Pair<List<TermOption>, String?> {
    val select = document.selectFirst("select[name=$fieldName], select#$fieldName")
        ?: return emptyList<TermOption>() to null
    val options = select.select("option").mapNotNull { option ->
        val value = normalizeSpace(option.attr("value"))
        if (value.isBlank() && !includeBlank) return@mapNotNull null
        TermOption(value = value, label = normalizeSpace(option.text()).ifBlank { value }, selected = option.hasAttr("selected"))
    }
    return options to (options.firstOrNull { it.selected }?.value ?: options.firstOrNull()?.value)
}

fun parseInputValue(document: Element, fieldName: String): String? =
    document.selectFirst("[name=$fieldName]")?.attr("value")?.let(::normalizeSpace)

data class CourseSelectionAction(
    val actionUrl: String,
    val method: String,
    val fields: Map<String, String>,
    val fieldPairs: List<Pair<String, String>> = fields.toList(),
)

data class ParsedCourseSelectionPage(
    val data: CourseSelectionData,
    val actions: Map<String, CourseSelectionAction>,
    val dropActions: Map<String, CourseSelectionAction>,
)

fun parseCourseSelectionPage(
    html: String,
    pageUrl: String = "https://aa.bjtu.edu.cn/course_selection/courseselecttask/selects/",
): ParsedCourseSelectionPage {
    val document = Jsoup.parse(html, pageUrl)
    val selectedCourses = mutableListOf<CourseSelectionCourse>()
    val availableCourses = mutableListOf<CourseSelectionCourse>()
    val actions = mutableMapOf<String, CourseSelectionAction>()
    val dropActions = mutableMapOf<String, CourseSelectionAction>()
    val submit = submitAction(document, pageUrl)

    val allTables = document.select("table.table-bordered, table.table, table")
        .distinctBy { it.cssSelector() }
    val selectedTables = allTables.filter(::isSelectedCourseSelectionTable)
    selectedTables.forEach { table ->
        val columns = courseSelectionColumns(table)
        courseSelectionBodyRows(table).forEach { cells ->
            courseSelectionCourse(cells, selected = true, index = selectedCourses.size, columns = columns)?.let { course ->
                if (selectedCourses.none { it.key == course.key }) {
                    selectedCourses += course
                }
                dropAction(cells, pageUrl, submit.fieldPairs)?.let { action ->
                    dropActions[course.key] = action
                }
            }
        }
    }

    allTables
        .filterNot { table -> table in selectedTables }
        .filter(::isAvailableCourseSelectionTable)
        .forEach { table ->
            val columns = courseSelectionColumns(table)
            courseSelectionBodyRows(table).forEach { cells ->
                val texts = cells.map { normalizeSpace(it.text()) }
                val rowSelected = isSelectedCourseSelectionRow(cells, texts, columns)
                val course = courseSelectionCourse(
                    cells = cells,
                    selected = rowSelected,
                    index = selectedCourses.size + availableCourses.size,
                    columns = columns,
                ) ?: return@forEach
                if (course.selected) {
                    if (selectedCourses.none { it.key == course.key }) {
                        selectedCourses += course
                    }
                    dropAction(cells, pageUrl, submit.fieldPairs)?.let { action ->
                        dropActions[course.key] = action
                    }
                    return@forEach
                }
                if (availableCourses.none { it.key == course.key }) {
                    availableCourses += course
                }
                val (checkboxName, checkboxValue) = checkboxPayload(cells)
                if (submit.actionUrl != null && checkboxName != null) {
                    val fieldPairs = submit.fieldPairs + (checkboxName to (checkboxValue ?: "on"))
                    actions[course.key] = CourseSelectionAction(
                        actionUrl = submit.actionUrl,
                        method = submit.method,
                        fields = fieldPairs.toMap(),
                        fieldPairs = fieldPairs,
                    )
                }
            }
        }
    val submitError = if (
        availableCourses.isNotEmpty() &&
        actions.isEmpty() &&
        availableCourses.any { it.remaining == null || it.remaining > 0 }
    ) {
        submit.error
    } else {
        null
    }

    return ParsedCourseSelectionPage(
        data = CourseSelectionData(
            selectedCourses = selectedCourses,
            availableCourses = availableCourses,
            canSubmit = actions.isNotEmpty(),
            submitError = submitError,
        ),
        actions = actions,
        dropActions = dropActions,
    )
}

data class CourseSelectionCaptchaForm(
    val imageUrl: String?,
    val inputName: String?,
    val fields: Map<String, String>,
    val prompt: String?,
)

fun parseCourseSelectionCaptcha(html: String, pageUrl: String): CourseSelectionCaptchaForm {
    val document = Jsoup.parse(html, pageUrl)
    val modal = document.select(".modal, .bootbox").firstOrNull { it.selectFirst("img") != null } ?: document
    val image = modal.selectFirst("img")
    val form = image?.parents()?.firstOrNull { it.tagName().equals("form", ignoreCase = true) }
        ?: document.selectFirst("form")
    val action = form?.attr("action")?.takeIf { it.isNotBlank() }?.let { resolveUrl(pageUrl, it) } ?: pageUrl
    val fieldRoot = form ?: modal
    val inputName = fieldRoot.select("input")
        .firstOrNull { input ->
            val type = input.attr("type").ifBlank { "text" }.lowercase()
            val name = normalizeSpace(input.attr("name"))
            name.isNotBlank() && type !in setOf("hidden", "submit", "button", "checkbox", "radio")
        }
        ?.attr("name")
        ?.let(::normalizeSpace)
    return CourseSelectionCaptchaForm(
        imageUrl = image?.attr("src")?.takeIf { it.isNotBlank() }?.let { resolveUrl(pageUrl, it) },
        inputName = inputName,
        fields = formFields(fieldRoot) + ("__action__" to action),
        prompt = normalizeSpace(modal.text()).takeIf { it.isNotBlank() },
    )
}

fun parseTeachingAssessmentList(
    html: String,
    pageUrl: String = "https://aa.bjtu.edu.cn/teaching_assessment/stu/list/",
): TeachingAssessmentData {
    val document = Jsoup.parse(html, pageUrl)
    val title = normalizeSpace(document.selectFirst(".widget-title")?.text())
        .takeIf { it.isNotBlank() }
    val table = document.select("table.table-bordered, table.table, table")
        .firstOrNull { table ->
            val header = table.selectFirst("tr")?.text().orEmpty()
            header.contains("课程号") && header.contains("操作")
        }
        ?: return TeachingAssessmentData(title = title)

    val rows = table.select("> tbody > tr, > tr")
    val courses = rows.drop(1).mapNotNull { row ->
        val cells = row.select("> th, > td")
        if (cells.size < 6) return@mapNotNull null
        val actionCell = cells.getOrNull(cells.size - 1) ?: return@mapNotNull null
        val links = actionCell.select("a[href]")
        val evaluateLink = links.firstOrNull { normalizeSpace(it.text()).contains("评教") }
        val viewLink = links.firstOrNull { normalizeSpace(it.text()).contains("查看") }
        val actionLink = evaluateLink ?: viewLink ?: links.firstOrNull()
        val href = actionLink?.attr("href")?.takeIf { it.isNotBlank() }
        val id = href?.let(::teachingAssessmentIdFromPath)
            ?: "${normalizeSpace(cells[0].text())}_${normalizeSpace(cells[1].text())}".trim('_')
                .ifBlank { return@mapNotNull null }
        val status = when {
            evaluateLink != null -> "待评教"
            viewLink != null -> "已评教"
            else -> normalizeSpace(actionCell.text()).ifBlank { "未知" }
        }
        TeachingAssessmentCourse(
            id = id,
            courseCode = normalizeSpace(cells[0].text()),
            section = normalizeSpace(cells[1].text()),
            courseName = normalizeSpace(cells[2].text()),
            teacher = normalizeSpace(cells[3].text()),
            assessmentType = normalizeSpace(cells[4].text()),
            status = status,
            actionPath = evaluateLink?.attr("href")?.takeIf { it.isNotBlank() },
            viewPath = viewLink?.attr("href")?.takeIf { it.isNotBlank() },
            canEvaluate = evaluateLink != null,
        )
    }
    return TeachingAssessmentData(title = title, courses = courses)
}

fun parseTeachingAssessmentForm(
    html: String,
    pageUrl: String,
): TeachingAssessmentForm {
    val document = Jsoup.parse(html, pageUrl)
    val form = document.selectFirst("form.teaching-assessment-form") ?: document.selectFirst("form")
    val actionUrl = form?.attr("action")
        ?.takeIf { it.isNotBlank() }
        ?.let { resolveUrl(pageUrl, it) }
        ?: pageUrl
    val method = form?.attr("method")?.let(::normalizeSpace)?.lowercase()?.ifBlank { null } ?: "post"
    val referer = pageUrl
    val baseFields = formFields(form).let { fields ->
        if ("refer" in fields) {
            fields
        } else {
            fields + ("refer" to resolveUrl(pageUrl, "/teaching_assessment/stu/list/"))
        }
    }

    val radios = form?.select("input[type=radio][name]")?.filter { radio ->
        normalizeSpace(radio.attr("name")).endsWith("-select_result")
    }.orEmpty()
    val radiosByName = linkedMapOf<String, MutableList<Element>>()
    radios.forEach { radio ->
        val name = normalizeSpace(radio.attr("name"))
        radiosByName.getOrPut(name) { mutableListOf() } += radio
    }
    val questions = radiosByName.entries.mapIndexed { index, (name, group) ->
        val prefix = name.removeSuffix("-select_result")
        val options = group.map { radio ->
            TeachingAssessmentOption(
                value = radio.attr("value"),
                label = teachingAssessmentRadioLabel(form, radio),
                selected = radio.hasAttr("checked"),
            )
        }
        TeachingAssessmentQuestion(
            index = teachingAssessmentIndex(prefix) ?: index,
            prompt = teachingAssessmentQuestionPrompt(group.firstOrNull()),
            name = name,
            resultId = formFieldValue(form, "$prefix-id"),
            options = options,
            selectedValue = options.firstOrNull { it.selected }?.value,
            recommendedValue = chooseTeachingAssessmentPositiveOption(options)?.value,
        )
    }

    val comments = form?.select("textarea[name]")?.filter { textarea ->
        normalizeSpace(textarea.attr("name")).endsWith("-comment_result")
    }?.mapIndexed { index, textarea ->
        val name = normalizeSpace(textarea.attr("name"))
        val prefix = name.removeSuffix("-comment_result")
        TeachingAssessmentComment(
            index = teachingAssessmentIndex(prefix) ?: index,
            prompt = teachingAssessmentQuestionPrompt(textarea),
            name = name,
            resultId = formFieldValue(form, "$prefix-id"),
            value = normalizeSpace(textarea.text()).takeIf { it.isNotBlank() },
        )
    }.orEmpty()

    return TeachingAssessmentForm(
        courseId = teachingAssessmentIdFromPath(pageUrl) ?: "",
        actionUrl = actionUrl,
        method = method,
        referer = referer,
        fields = baseFields,
        questions = questions.sortedBy { it.index },
        comments = comments.sortedBy { it.index },
        unsupportedMultiCount = baseFields["multi-TOTAL_FORMS"]?.toIntOrNull() ?: 0,
    )
}

fun chooseTeachingAssessmentPositiveOption(options: List<TeachingAssessmentOption>): TeachingAssessmentOption? {
    if (options.isEmpty()) return null
    val exactPriority = listOf(
        "非常符合",
        "优秀",
        "非常满意",
        "满意",
        "很好",
        "好",
        "符合",
    )
    exactPriority.forEach { label ->
        options.firstOrNull { it.label == label }?.let { return it }
    }
    val containsPriority = listOf("非常", "优秀", "满意", "很好")
    containsPriority.forEach { needle ->
        options.firstOrNull { it.label.contains(needle) }?.let { return it }
    }
    return options.last()
}

private data class SubmitAction(
    val actionUrl: String?,
    val method: String,
    val fields: Map<String, String>,
    val fieldPairs: List<Pair<String, String>>,
    val error: String?,
)

private data class CourseSelectionColumns(
    val actionIndex: Int = 0,
    val courseIndex: Int = 1,
    val remainingIndex: Int? = 2,
    val creditIndex: Int? = 3,
    val courseTypeIndex: Int? = 4,
    val examTypeIndex: Int? = 5,
    val teacherIndex: Int? = 6,
    val timeLocationIndex: Int? = 7,
    val noteIndex: Int? = 8,
    val statusIndex: Int? = null,
    val matchedHeader: Boolean = false,
)

private fun submitAction(document: Element, pageUrl: String): SubmitAction {
    val actionPageSubmitUrl = courseSelectionActionSubmitUrl(pageUrl)
    val submit = document.select("a.btn-primary, button.btn-primary, input[type=submit], button[type=submit]")
        .firstOrNull(::isCourseSelectionSubmitControl)
        ?: document.selectFirst("a.btn-primary, button.btn-primary, input[type=submit], button[type=submit]")
    val form = submit?.parents()?.firstOrNull { it.tagName().equals("form", ignoreCase = true) }
        ?: document.selectFirst("form")
    val method = if (actionPageSubmitUrl != null) {
        "post"
    } else {
        form?.attr("method")?.let(::normalizeSpace)?.lowercase()?.ifBlank { null } ?: "post"
    }
    val candidates = mutableListOf<String>()
    if (submit != null) {
        listOf("data-url", "data-href", "data-action", "formaction", "href").forEach { attr ->
            val value = normalizeSpace(submit.attr(attr))
            if (value.isNotBlank() && value != "#" && !value.startsWith("javascript:", ignoreCase = true)) {
                candidates += value
            }
        }
        onclickActionCandidate(normalizeSpace(submit.attr("onclick")))?.let(candidates::add)
    }
    if (form != null) {
        candidates += form.attr("action").takeIf { it.isNotBlank() } ?: pageUrl
    }
    val actionUrl = actionPageSubmitUrl ?: candidates.firstOrNull()?.let { resolveUrl(pageUrl, it) }
    val fieldPairs = formFieldPairs(form)
    return SubmitAction(
        actionUrl = actionUrl,
        method = method,
        fields = fieldPairs.toMap(),
        fieldPairs = fieldPairs,
        error = if (actionUrl == null) "无法解析选课提交入口：页面没有暴露 form/action/data-url。" else null,
    )
}

private fun courseSelectionActionSubmitUrl(pageUrl: String): String? =
    pageUrl
        .takeIf { it.contains("/course_selection/courseselecttask/selects_action/", ignoreCase = true) }
        ?.let { resolveUrl(pageUrl, "/course_selection/courseselecttask/selects_action/?action=submit") }

private fun isCourseSelectionSubmitControl(element: Element): Boolean {
    val text = normalizeSpace(
        listOf(
            element.text(),
            element.attr("value"),
            element.attr("title"),
            element.attr("aria-label"),
        ).joinToString(" ")
    )
    if (text.isBlank()) return element.tagName().equals("input", ignoreCase = true)
    val lower = text.lowercase()
    return listOf("submit", "select", "confirm").any { lower.contains(it) } ||
        listOf("\u63d0\u4ea4", "\u9009\u8bfe", "\u786e\u8ba4", "\u62a2\u8bfe").any { text.contains(it) }
}

private fun formFields(form: Element?): Map<String, String> {
    if (form == null) return emptyMap()
    return formFieldPairs(form).toMap()
}

private fun formFieldPairs(form: Element?): List<Pair<String, String>> {
    if (form == null) return emptyList()
    return form.select("input[name]").mapNotNull { input ->
        val name = normalizeSpace(input.attr("name"))
        val type = input.attr("type").lowercase()
        if (name.isBlank() || type in setOf("checkbox", "radio", "submit", "button", "image", "file")) {
            null
        } else {
            name to input.attr("value")
        }
    }
}

private fun formFieldValue(form: Element?, name: String): String? =
    form?.select("input[name], textarea[name], select[name]")
        ?.firstOrNull { normalizeSpace(it.attr("name")) == name }
        ?.let { field ->
            when (field.tagName().lowercase()) {
                "textarea" -> field.text()
                else -> field.attr("value")
            }
        }
        ?.let(::normalizeSpace)
        ?.takeIf { it.isNotBlank() }

private fun teachingAssessmentIdFromPath(value: String): String? =
    Regex("""/teaching_assessment/stu/([^/?#]+)/""")
        .find(value)
        ?.groupValues
        ?.getOrNull(1)
        ?.let(::normalizeSpace)
        ?.takeIf { it.isNotBlank() }

private fun teachingAssessmentIndex(prefix: String): Int? =
    Regex("""-(\d+)$""").find(prefix)?.groupValues?.getOrNull(1)?.toIntOrNull()

private fun teachingAssessmentQuestionPrompt(element: Element?): String {
    val paragraph = element?.parents()?.firstOrNull { it.tagName().equals("p", ignoreCase = true) }
    val label = paragraph?.select("> label")?.firstOrNull { !it.hasAttr("for") }
        ?: element?.previousElementSiblings()?.firstOrNull { it.tagName().equals("label", ignoreCase = true) }
    return normalizeSpace(label?.text()).trimEnd(':', '：')
}

private fun teachingAssessmentRadioLabel(form: Element?, radio: Element): String {
    val parentLabel = radio.parent()?.takeIf { it.tagName().equals("label", ignoreCase = true) }
    val explicitLabel = radio.id().takeIf { it.isNotBlank() }?.let { id ->
        form?.select("label[for=$id]")?.firstOrNull()
    }
    val text = normalizeSpace((parentLabel ?: explicitLabel)?.text())
    return text.ifBlank { radio.attr("value") }
}

private fun checkboxPayload(cell: Element?): Pair<String?, String?> {
    val checkbox = cell?.selectFirst("input[type=checkbox]") ?: return null to null
    val name = normalizeSpace(checkbox.attr("name"))
    if (name.isBlank()) return null to null
    return name to checkbox.attr("value").ifBlank { "on" }
}

private fun checkboxPayload(cells: List<Element>): Pair<String?, String?> {
    val checkbox = cells.asSequence()
        .mapNotNull { it.selectFirst("input[type=checkbox][name]") }
        .firstOrNull()
        ?: return null to null
    val name = normalizeSpace(checkbox.attr("name"))
    if (name.isBlank()) return null to null
    return name to checkbox.attr("value").ifBlank { "on" }
}

private fun onclickActionCandidate(value: String): String? =
    Regex("""["']([^"']*(?:delete|courseselecttask)[^"']*)["']""", RegexOption.IGNORE_CASE)
        .find(value)
        ?.groupValues
        ?.getOrNull(1)
        ?.let(::normalizeSpace)
        ?.takeIf { it.isNotBlank() && !it.startsWith("javascript:", ignoreCase = true) }

private fun dropAction(cell: Element?, pageUrl: String, baseFields: List<Pair<String, String>>): CourseSelectionAction? {
    val trigger = cell?.selectFirst(".select-delete-btn, [data-pk], [data-url], [data-href], [data-action], [formaction], a, button, input")
        ?: return null
    val dataPk = listOf("data-pk", "pk", "value")
        .firstNotNullOfOrNull { attr -> normalizeSpace(trigger.attr(attr)).takeIf { it.isNotBlank() } }
    val candidates = mutableListOf<String>()
    listOf("href", "data-url", "data-href", "data-action", "formaction").forEach { attr ->
        val value = normalizeSpace(trigger.attr(attr))
        if (value.isNotBlank() && value != "#" && !value.startsWith("javascript:", ignoreCase = true)) {
            candidates += value
        }
    }
    onclickActionCandidate(normalizeSpace(trigger.attr("onclick")))?.let(candidates::add)
    if (candidates.isEmpty() && dataPk != null) {
        candidates += defaultCourseSelectionDeletePath(pageUrl)
    }
    val actionUrl = candidates.firstOrNull()?.let { resolveUrl(pageUrl, it) } ?: return null
    val method = listOf("data-method", "method")
        .firstNotNullOfOrNull { attr -> normalizeSpace(trigger.attr(attr)).lowercase().takeIf { it.isNotBlank() } }
        ?: "post"
    val fieldPairs = if (dataPk == null) baseFields else baseFields + ("pk" to dataPk)
    return CourseSelectionAction(actionUrl = actionUrl, method = method, fields = fieldPairs.toMap(), fieldPairs = fieldPairs)
}

private fun dropAction(cells: List<Element>, pageUrl: String, baseFields: List<Pair<String, String>>): CourseSelectionAction? {
    val trigger = cells.asSequence()
        .mapNotNull {
            it.selectFirst(".select-delete-btn, [data-pk], [data-url], [data-href], [data-action], [formaction], a, button, input")
        }
        .firstOrNull(::isCourseSelectionDropControl)
        ?: return null
    val dataPk = listOf("data-pk", "pk", "value")
        .firstNotNullOfOrNull { attr -> normalizeSpace(trigger.attr(attr)).takeIf { it.isNotBlank() } }
    val candidates = mutableListOf<String>()
    listOf("href", "data-url", "data-href", "data-action", "formaction").forEach { attr ->
        val value = normalizeSpace(trigger.attr(attr))
        if (value.isNotBlank() && value != "#" && !value.startsWith("javascript:", ignoreCase = true)) {
            candidates += value
        }
    }
    onclickActionCandidate(normalizeSpace(trigger.attr("onclick")))?.let(candidates::add)
    if (candidates.isEmpty() && dataPk != null) {
        candidates += defaultCourseSelectionDeletePath(pageUrl)
    }
    val actionUrl = candidates.firstOrNull()?.let { resolveUrl(pageUrl, it) } ?: return null
    val method = listOf("data-method", "method")
        .firstNotNullOfOrNull { attr -> normalizeSpace(trigger.attr(attr)).lowercase().takeIf { it.isNotBlank() } }
        ?: "post"
    val fieldPairs = if (dataPk == null) baseFields else baseFields + ("pk" to dataPk)
    return CourseSelectionAction(actionUrl = actionUrl, method = method, fields = fieldPairs.toMap(), fieldPairs = fieldPairs)
}

private fun defaultCourseSelectionDeletePath(pageUrl: String): String =
    if (pageUrl.contains("/course_selection/courseselecttask/selects_action/", ignoreCase = true)) {
        "/course_selection/courseselecttask/selects_action/?action=delete"
    } else {
        "/course_selection/courseselecttask/delete/"
    }

private fun isCourseSelectionDropControl(element: Element): Boolean {
    if (normalizeSpace(element.attr("data-pk")).isNotBlank()) return true
    val text = normalizeSpace("${element.text()} ${element.attr("title")} ${element.attr("aria-label")}")
    val lower = text.lowercase()
    return lower.contains("delete") ||
        listOf("\u9000\u8bfe", "\u5220\u9664", "\u53d6\u6d88").any { text.contains(it) }
}

private fun isSelectedCourseSelectionRow(
    cells: List<Element>,
    texts: List<String>,
    columns: CourseSelectionColumns,
): Boolean {
    val status = columns.statusIndex
        ?.let { texts.getOrNull(it) }
        .orEmpty()
        .ifBlank { texts.getOrNull(columns.actionIndex).orEmpty() }
    return isSelectedStatusText(status) ||
        cells.any { cell ->
            cell.selectFirst(".select-delete-btn, [data-pk]") != null ||
                isCourseSelectionDropControl(cell)
        }
}

private fun isSelectedStatusText(text: String): Boolean =
    text.contains("\u5df2\u9009") ||
        text.contains("\u9009\u4e2d") ||
        text.contains("selected", ignoreCase = true)

private fun courseSelectionCourse(cells: List<Element>, selected: Boolean, index: Int): CourseSelectionCourse? {
    val texts = cells.map { normalizeSpace(it.text()) }
    if (texts.size < 2 || texts[1].isBlank()) return null
    val (key, code, section) = courseSelectionKey(texts[1])
    val status = texts[0].ifBlank { if (selected) "selected" else "available" }
    val remainingText = texts.getOrNull(2)?.takeIf { it.isNotBlank() }
    return CourseSelectionCourse(
        key = key.ifBlank { "course_$index" },
        status = status,
        selected = selected || isSelectedStatusText(status),
        courseName = texts[1],
        courseCode = code,
        section = section,
        remaining = remainingText?.let { Regex("""-?\d+""").find(it)?.value?.toIntOrNull() },
        remainingText = remainingText,
        credit = texts.getOrNull(3)?.takeIf { it.isNotBlank() },
        courseType = texts.getOrNull(4)?.takeIf { it.isNotBlank() },
        examType = texts.getOrNull(5)?.takeIf { it.isNotBlank() },
        teacher = texts.getOrNull(6)?.takeIf { it.isNotBlank() },
        timeLocation = texts.getOrNull(7)?.takeIf { it.isNotBlank() },
        note = texts.getOrNull(8)?.takeIf { it.isNotBlank() },
    )
}

private fun courseSelectionCourse(
    cells: List<Element>,
    selected: Boolean,
    index: Int,
    columns: CourseSelectionColumns,
): CourseSelectionCourse? {
    val texts = cells.map { normalizeSpace(it.text()) }
    val courseIndex = courseCellIndex(cells, texts, columns) ?: return null
    val courseName = texts.getOrNull(courseIndex).orEmpty()
    if (!isCourseSelectionRow(cells, texts, courseIndex, selected, columns)) return null
    val (key, code, section) = courseSelectionKey(courseName)
    val status = columns.statusIndex
        ?.let { texts.getOrNull(it) }
        .orEmpty()
        .ifBlank { texts.getOrNull(columns.actionIndex).orEmpty() }
        .ifBlank { if (selected) "selected" else "available" }
    val remainingText = columns.remainingIndex?.let { texts.getOrNull(it) }?.takeIf { it.isNotBlank() }
    return CourseSelectionCourse(
        key = key.ifBlank { "course_$index" },
        status = status,
        selected = selected || isSelectedStatusText(status),
        courseName = courseName,
        courseCode = code,
        section = section,
        remaining = remainingText?.let { Regex("""-?\d+""").find(it)?.value?.toIntOrNull() },
        remainingText = remainingText,
        credit = columns.creditIndex?.let { texts.getOrNull(it) }?.takeIf { it.isNotBlank() },
        courseType = columns.courseTypeIndex?.let { texts.getOrNull(it) }?.takeIf { it.isNotBlank() },
        examType = columns.examTypeIndex?.let { texts.getOrNull(it) }?.takeIf { it.isNotBlank() },
        teacher = columns.teacherIndex?.let { texts.getOrNull(it) }?.takeIf { it.isNotBlank() },
        timeLocation = columns.timeLocationIndex?.let { texts.getOrNull(it) }?.takeIf { it.isNotBlank() },
        note = columns.noteIndex?.let { texts.getOrNull(it) }?.takeIf { it.isNotBlank() },
    )
}

private fun isSelectedCourseSelectionTable(table: Element): Boolean =
    table.parents().any { it.id() == "selected-container" }

private fun isAvailableCourseSelectionTable(table: Element): Boolean =
    courseSelectionTableScore(table) >= 4

private fun courseSelectionTableScore(table: Element): Int {
    val headerText = courseSelectionHeaderCells(table).joinToString(" ")
    val bodyText = courseSelectionBodyRows(table).take(3).flatten().joinToString(" ") { normalizeSpace(it.text()) }
    var score = 0
    if (table.selectFirst("input[type=checkbox][name]") != null) score += 3
    if (containsAny(headerText, "course", "\u8bfe\u7a0b", "\u8bfe\u5802")) score += 2
    if (containsAny(headerText, "remaining", "\u4f59\u91cf", "\u8bfe\u4f59")) score += 2
    if (containsAny(headerText, "teacher", "\u6559\u5e08", "\u4efb\u8bfe")) score += 1
    if (containsAny(headerText, "credit", "\u5b66\u5206")) score += 1
    if (Regex("""[A-Za-z]\d+[A-Za-z]?""").containsMatchIn(bodyText)) score += 1
    return score
}

private fun courseSelectionColumns(table: Element): CourseSelectionColumns {
    val headers = courseSelectionHeaderCells(table)
    if (headers.isEmpty()) return CourseSelectionColumns()

    fun index(vararg needles: String): Int? =
        headers.indexOfFirst { header -> containsAny(header, *needles) }
            .takeIf { it >= 0 }

    val actionIndex = index(
        "select",
        "action",
        "operation",
        "\u64cd\u4f5c",
        "\u9009\u62e9",
        "\u9009\u8bfe",
        "\u9000\u8bfe",
        "\u5220\u9664",
    ) ?: 0
    val courseIndex = index("course", "\u8bfe\u7a0b", "\u8bfe\u5802", "\u540d\u79f0") ?: 1
    val typeIndex = headers.indexOfFirst { header ->
        containsAny(header, "attribute", "\u5c5e\u6027", "\u7c7b\u578b") &&
            !containsAny(header, "exam", "\u8003\u6838", "\u8003\u8bd5")
    }.takeIf { it >= 0 }
    val teacherIndex = index("teacher", "\u6559\u5e08", "\u4efb\u8bfe")
    val timeLocationIndex = headers.indexOfFirst { header ->
        containsAny(header, "time", "location", "\u65f6\u95f4", "\u5730\u70b9") ||
            (containsAny(header, "\u4e0a\u8bfe") && !containsAny(header, "teacher", "\u6559\u5e08", "\u4efb\u8bfe"))
    }.takeIf { it >= 0 }
    return CourseSelectionColumns(
        actionIndex = actionIndex,
        courseIndex = courseIndex,
        remainingIndex = index("remaining", "\u4f59\u91cf", "\u8bfe\u4f59"),
        creditIndex = index("credit", "\u5b66\u5206", "\u5b66\u65f6"),
        courseTypeIndex = typeIndex,
        examTypeIndex = index("exam", "assessment", "\u8003\u6838", "\u8003\u8bd5"),
        teacherIndex = teacherIndex,
        timeLocationIndex = timeLocationIndex,
        noteIndex = index("note", "remark", "\u5907\u6ce8", "\u9650\u5236", "\u8bf4\u660e"),
        statusIndex = index("status", "\u72b6\u6001", "\u9009\u8bfe\u72b6\u6001"),
        matchedHeader = true,
    )
}

private fun courseSelectionHeaderCells(table: Element): List<String> {
    val firstRow = directRows(table).firstOrNull() ?: return emptyList()
    if (firstRow.none { it.tagName().equals("th", ignoreCase = true) }) return emptyList()
    return firstRow.map { normalizeSpace(it.text()) }
}

private fun courseSelectionBodyRows(table: Element): List<List<Element>> {
    val rows = directRows(table)
    if (rows.isEmpty()) return emptyList()
    val hasHeader = rows.first().any { it.tagName().equals("th", ignoreCase = true) }
    return if (hasHeader) rows.drop(1) else rows
}

private fun courseCellIndex(
    cells: List<Element>,
    texts: List<String>,
    columns: CourseSelectionColumns,
): Int? {
    if (!texts.getOrNull(columns.courseIndex).isNullOrBlank()) return columns.courseIndex
    cells.indices.firstOrNull { index ->
        index != columns.actionIndex && Regex("""[A-Za-z]\d+[A-Za-z]?""").containsMatchIn(texts[index])
    }?.let { return it }
    return texts.indices.firstOrNull { index ->
        index != columns.actionIndex &&
            texts[index].isNotBlank() &&
            cells[index].selectFirst("input[type=checkbox]") == null
    }
}

private fun isCourseSelectionRow(
    cells: List<Element>,
    texts: List<String>,
    courseIndex: Int,
    selected: Boolean,
    columns: CourseSelectionColumns,
): Boolean {
    val courseText = texts.getOrNull(courseIndex).orEmpty()
    if (courseText.isBlank()) return false
    if (containsAny(courseText, "course", "\u8bfe\u7a0b") && cells.any { it.tagName().equals("th", ignoreCase = true) }) {
        return false
    }
    val hasCheckbox = cells.any { it.selectFirst("input[type=checkbox][name]") != null }
    val hasCourseCode = Regex("""[A-Za-z]\d+[A-Za-z]?""").containsMatchIn(courseText)
    return selected || hasCheckbox || hasCourseCode || columns.matchedHeader && texts.size >= 4
}

private fun courseSelectionKey(courseName: String): Triple<String, String?, String?> {
    val text = normalizeSpace(courseName)
    val code = Regex("""(?:^|\s)([A-Za-z]\d+[A-Za-z]?)(?=[\s:：]|$)""")
        .find(text)
        ?.groupValues
        ?.getOrNull(1)
    val section = Regex("""(?:^|\s)(\d{2})(?:\s|\u73ed|$)""").find(text)?.groupValues?.get(1)
    val key = when {
        code != null && section != null -> "${code}_$section"
        code != null -> code
        else -> text
    }
    return Triple(key, code, section)
}

private fun directRows(table: Element?): List<List<Element>> {
    if (table == null) return emptyList()
    return table.select("tr").mapNotNull { row ->
        row.select("> th, > td").takeIf { it.isNotEmpty() }
    }
}

private fun containsAny(text: String, vararg needles: String): Boolean {
    val lower = text.lowercase()
    return needles.any { needle -> lower.contains(needle.lowercase()) }
}

private fun resolveUrl(baseUrl: String, value: String): String =
    URI(baseUrl).resolve(value.trim()).toString()

fun parseTimetable(html: String): TimetableData {
    val document = Jsoup.parse(html)
    val table = document.selectFirst("table.table, table") ?: return TimetableData()
    val rows = table.select("> tbody > tr, > tr")
    if (rows.size < 2) return TimetableData()
    val days = rows.first().select("> th, > td").drop(1).map { normalizeSpace(it.text()) }
    val periods = linkedSetOf<String>()
    val entries = mutableListOf<CourseEntry>()

    for (row in rows.drop(1)) {
        val cells = row.select("> th, > td")
        if (cells.size < 2) continue
        val periodText = normalizeSpace(cells[0].text())
        val periodLabel = Regex("""\d+""").find(periodText)?.let { "Period ${it.value}" } ?: periodText
        val timeRange = Regex("""\[(\d{2}:\d{2}-\d{2}:\d{2})]""").find(periodText)?.groupValues?.get(1)
        if (periodLabel.isNotBlank()) periods.add(periodLabel)
        cells.drop(1).forEachIndexed { dayIndex, cell ->
            val weekday = days.getOrNull(dayIndex) ?: "Day ${dayIndex + 1}"
            val blocks = cell.select("> div").filter { normalizeSpace(it.text()).isNotBlank() }
                .ifEmpty { if (normalizeSpace(cell.text()).isBlank()) emptyList() else listOf(cell) }
            for (block in blocks) {
                val blockText = normalizeSpace(block.text())
                val code = Regex("""([A-Z]\d+[A-Z]?)\s*\[([^]]+)]""").find(blockText) ?: continue
                val nameNode = block.select("span").firstOrNull { !it.classNames().contains("text-muted") }
                val courseName = normalizeSpace(nameNode?.text()).ifBlank { blockText.substringAfter("]").trim() }
                val metaNode = block.select("div").firstOrNull { it.attr("style").contains("max-width") }
                val teacher = metaNode?.selectFirst("i")?.text()?.let(::normalizeSpace)
                val weeks = normalizeSpace(metaNode?.text()?.replace(teacher.orEmpty(), "")).ifBlank { null }
                val locationText = normalizeSpace(block.selectFirst("span.text-muted")?.text())
                val (campus, building, roomName) = splitLocation(locationText)
                entries += CourseEntry(
                    weekday = weekday,
                    period = periodLabel,
                    timeRange = timeRange,
                    courseCode = code.groupValues[1],
                    section = code.groupValues[2],
                    courseName = courseName,
                    teacher = teacher,
                    weeks = weeks,
                    campus = campus,
                    building = building,
                    room = roomName,
                    locationText = locationText.ifBlank { null },
                )
            }
        }
    }
    return TimetableData(days = days, periods = periods.toList(), entries = entries)
}

fun parseExams(html: String, requestedTerm: String? = null): ExamData {
    val document = Jsoup.parse(html)
    val (options, currentTerm) = parseSelectOptions(document, "zxjxjhh")
    val rows = document.selectFirst("table.table, table")?.let(::tableRows).orEmpty()
    val items = rows.drop(1).mapNotNull { cells ->
        if (cells.size < 7) return@mapNotNull null
        ExamItem(
            term = requestedTerm ?: currentTerm,
            courseName = cells.getOrElse(1) { "" },
            schedule = cells.getOrNull(2),
            examMode = cells.getOrNull(3),
            remark = cells.getOrNull(4),
            registration = cells.getOrNull(5),
            status = cells.getOrNull(6),
        )
    }
    return ExamData(currentTerm = requestedTerm ?: currentTerm, availableTerms = options, items = items)
}

fun parseScores(html: String, requestedTerm: String? = null): ScoreData {
    val document = Jsoup.parse(html)
    val (options, currentTerm) = parseSelectOptions(document, "zxjxjhh")
    val rows = document.selectFirst("table.table, table")?.select("> tbody > tr, > tr").orEmpty()
    val items = rows.drop(1).mapNotNull { row ->
        val cellNodes = row.select("> th, > td")
        val cells = cellNodes.map { normalizeSpace(it.text()) }
        if (cells.size < 7) return@mapNotNull null
        val detailPath = cellNodes.getOrNull(7)?.let(::extractScoreDetailPath)
        ScoreItem(
            term = requestedTerm ?: cells.getOrNull(1) ?: currentTerm,
            courseName = cells.getOrElse(2) { cells.getOrElse(1) { "" } },
            credit = cells.getOrNull(3),
            score = cells.getOrNull(4),
            bonusScore = cells.getOrNull(5),
            teacher = cells.getOrNull(6),
            detail = cells.getOrNull(7)?.let { cleanInlineScoreDetail(it, detailPath) },
            detailPath = detailPath,
        )
    }
    return ScoreData(currentTerm = requestedTerm ?: currentTerm, availableTerms = options, items = items)
}

private fun cleanInlineScoreDetail(value: String, detailPath: String?): String? {
    val cleaned = normalizeSpace(value)
    if (cleaned.isBlank()) return null
    if (detailPath != null && isGenericScoreDetailText(cleaned)) return null
    return cleaned
}

private fun extractScoreDetailPath(cell: Element): String? {
    val candidates = mutableListOf<String>()
    cell.select("a").forEach { link ->
        listOf("href", "data-url", "data-href").forEach { attr ->
            normalizeSpace(link.attr(attr)).takeIf { it.isNotBlank() }?.let(candidates::add)
        }
        normalizeSpace(link.attr("onclick")).takeIf { it.isNotBlank() }?.let(candidates::add)
    }
    candidates += cell.html()

    for (candidate in candidates) {
        cleanScoreDetailCandidate(candidate)?.let { return it }
        Regex("""["']([^"']*(?:score|cj|grade)[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(candidate)
            .forEach { match ->
                cleanScoreDetailCandidate(match.groupValues[1])?.let { return it }
            }
    }
    return null
}

private fun cleanScoreDetailCandidate(value: String): String? {
    var cleaned = normalizeSpace(value)
    if (cleaned.isBlank() || cleaned == "#" || cleaned.startsWith("javascript:void", ignoreCase = true) || cleaned.startsWith("void(", ignoreCase = true)) {
        return null
    }
    if (cleaned.startsWith("javascript:", ignoreCase = true)) {
        cleaned = cleaned.substringAfter(':')
    }
    val urlMatch = Regex("""(https?://aa\.bjtu\.edu\.cn/[^\s"'<>]+|/[^\s"'<>]*(?:score|cj|grade)[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
        .find(cleaned)
    if (urlMatch != null) return urlMatch.value.trimEnd(')', ';', ',')
    if (Regex("""(?:score|cj|grade)""", RegexOption.IGNORE_CASE).containsMatchIn(cleaned) && !Regex("""\s""").containsMatchIn(cleaned)) {
        return cleaned.trimEnd(')', ';', ',')
    }
    return null
}

fun parseScoreDetail(html: String): ScoreDetailData {
    val document = Jsoup.parse(html)
    val title = scoreDetailTitle(document)
    val fields = mutableListOf<ScoreDetailField>()
    val tables = mutableListOf<ScoreDetailTable>()
    val seenFields = mutableSetOf<Pair<String, String>>()

    fun addField(label: String, value: String) {
        val cleanedLabel = normalizeSpace(label).trimEnd(':', '：')
        val cleanedValue = normalizeSpace(value)
        if (cleanedLabel.isBlank() || cleanedValue.isBlank()) return
        val key = cleanedLabel to cleanedValue
        if (!seenFields.add(key)) return
        fields += ScoreDetailField(label = cleanedLabel, value = cleanedValue)
    }

    document.select("table").forEach { table ->
        val rows = tableRows(table)
        if (rows.isEmpty()) return@forEach

        rows.forEach { row ->
            if (row.size >= 2 && row.size % 2 == 0) {
                row.chunked(2).forEach { pair ->
                    if (pair[0].length <= 32) addField(pair[0], pair[1])
                }
            }
        }

        val headerCells = table.selectFirst("tr")?.select("> th, > td").orEmpty()
        val hasHeader = headerCells.isNotEmpty() && (
            headerCells.all { it.tagName().equals("th", ignoreCase = true) } ||
                looksLikeScoreDetailHeader(rows.first())
            )
        val headers = if (hasHeader) rows.first() else emptyList()
        val bodyRows = if (hasHeader) rows.drop(1) else rows
        val isFieldTable = !hasHeader && rows.all { row -> row.size >= 2 && row.size % 2 == 0 }
        if (bodyRows.isNotEmpty() && !isFieldTable && !isGenericScoreDetailRows(bodyRows)) {
            tables += ScoreDetailTable(
                title = tableTitle(table),
                headers = headers,
                rows = bodyRows,
            )
        }
    }

    extractScoreDetailFieldsFromElements(document).forEach { (label, value) -> addField(label, value) }
    val rawText = normalizeSpace(document.text()).let { if (it.length > 4000) "${it.take(4000)}..." else it }
    extractScoreDetailFieldsFromText(rawText).forEach { (label, value) -> addField(label, value) }
    if (!hasScoreComponentTable(tables)) {
        scoreComponentTableFromFields(fields)?.let(tables::add)
    }
    return ScoreDetailData(
        title = title,
        fields = fields,
        tables = tables,
        rawText = rawText.takeUnless { it.isBlank() || isGenericScoreDetailText(it) },
    )
}

private fun looksLikeScoreDetailHeader(row: List<String>): Boolean {
    if (row.size < 2) return false
    val joined = row.joinToString(" ")
    return containsAny(joined, "项目", "分项", "平时", "期末", "占比", "比例", "权重") &&
        containsAny(joined, "成绩", "得分", "分数", "占比", "比例", "权重")
}

private fun isGenericScoreDetailRows(rows: List<List<String>>): Boolean =
    rows.flatten().filter { it.isNotBlank() }.let { values ->
        values.isNotEmpty() && values.all(::isGenericScoreDetailText)
    }

private fun isGenericScoreDetailText(value: String): Boolean {
    val compact = normalizeSpace(value).replace(Regex("""\s+"""), "")
    return compact in setOf("详情", "详细", "详细信息", "查看", "查看详情", "成绩详情", "分数详情")
}

private fun extractScoreDetailFieldsFromElements(document: Element): List<Pair<String, String>> {
    val fields = mutableListOf<Pair<String, String>>()
    document.select("label, .control-label, .form-label, .col-form-label, .field-label").forEach { labelElement ->
        val label = cleanScoreDetailLabel(labelElement.text())
        if (!isUsefulScoreDetailLabel(label)) return@forEach
        val value = labelElement.nextElementSibling()?.text()?.let(::normalizeSpace)
            ?.takeIf { it.isNotBlank() }
            ?: labelElement.parent()?.let { parent ->
                normalizeSpace(parent.text().removePrefix(labelElement.text())).trimStart(':', '：')
            }?.takeIf { it.isNotBlank() }
        if (!value.isNullOrBlank()) fields += label to value
    }
    return fields
}

private fun extractScoreDetailFieldsFromText(text: String): List<Pair<String, String>> {
    if (text.isBlank()) return emptyList()
    val fields = mutableListOf<Pair<String, String>>()
    val pattern = Regex("""([^：:\s][^：:]{0,24})[：:]\s*([^：:]+?)(?=\s+[^：:\s][^：:]{0,24}[：:]|$)""")
    pattern.findAll(text).forEach { match ->
        val label = cleanScoreDetailLabel(match.groupValues[1])
        val value = normalizeSpace(match.groupValues[2]).trimEnd(';', '；')
        if (isUsefulScoreDetailLabel(label) && value.isNotBlank()) fields += label to value
    }
    return fields
}

private fun cleanScoreDetailLabel(value: String): String =
    normalizeSpace(value).trimEnd(':', '：')

private fun isUsefulScoreDetailLabel(label: String): Boolean =
    label.isNotBlank() && containsAny(
        label,
        "课程",
        "项目",
        "成绩",
        "得分",
        "分数",
        "比例",
        "占比",
        "权重",
        "平时",
        "期末",
        "期中",
        "总评",
    )

private data class ScoreComponent(
    val name: String,
    var ratio: String? = null,
    var score: String? = null,
)

private fun scoreComponentTableFromFields(fields: List<ScoreDetailField>): ScoreDetailTable? {
    val components = linkedMapOf<String, ScoreComponent>()
    fields.forEach { field ->
        val name = scoreComponentName(field.label) ?: return@forEach
        val component = components.getOrPut(name) { ScoreComponent(name) }
        val ratio = extractScoreRatio(field.value)
        val score = extractScoreValue(field.value, ratio)
        when {
            isScoreRatioLabel(field.label) -> component.ratio = ratio ?: field.value
            isScoreValueLabel(field.label) -> component.score = score
            ratio != null && score != null -> {
                component.ratio = ratio
                component.score = score
            }
            ratio != null -> component.ratio = ratio
            score != null -> component.score = score
        }
    }
    val rows = components.values
        .filter { !it.ratio.isNullOrBlank() || !it.score.isNullOrBlank() }
        .map { component -> listOf(component.name, component.ratio.orEmpty(), component.score.orEmpty()) }
    if (rows.isEmpty()) return null
    return ScoreDetailTable(title = "分项成绩", headers = listOf("项目", "比例", "成绩"), rows = rows)
}

private fun scoreComponentName(label: String): String? {
    val compact = normalizeSpace(label).replace(Regex("""\s+"""), "")
    return listOf("平时", "期末", "期中", "实验", "上机", "作业", "课堂", "考勤", "出勤", "小测", "测验", "报告", "论文", "答辩", "实践", "项目", "总评")
        .firstOrNull { compact.contains(it) }
}

private fun isScoreRatioLabel(label: String): Boolean =
    containsAny(label, "比例", "占比", "权重", "比重", "百分比")

private fun isScoreValueLabel(label: String): Boolean =
    containsAny(label, "成绩", "得分", "分数")

private fun extractScoreRatio(value: String): String? =
    Regex("""\d+(?:\.\d+)?\s*%""").find(value)?.value?.replace(Regex("""\s+"""), "")

private fun extractScoreValue(value: String, ratio: String? = null): String? {
    val cleaned = normalizeSpace(value)
    if (cleaned.isBlank()) return null
    val withoutRatio = ratio?.let { normalizeSpace(cleaned.replace(it, "")) } ?: cleaned
    return withoutRatio.trim('(', ')', '（', '）', ',', '，', ';', '；').takeIf { it.isNotBlank() }
}

private fun hasScoreComponentTable(tables: List<ScoreDetailTable>): Boolean =
    tables.any { table ->
        val text = (table.headers + table.rows.flatten()).joinToString(" ")
        containsAny(text, "平时", "期末", "期中", "比例", "占比", "权重") &&
            containsAny(text, "成绩", "得分", "分数", "比例", "占比", "权重")
    }

private fun scoreDetailTitle(document: Element): String? {
    listOf(".modal-title", "h1", "h2", "h3", "legend", "title").forEach { selector ->
        val text = normalizeSpace(document.selectFirst(selector)?.text())
        if (text.isNotBlank()) return text
    }
    return null
}

private fun tableTitle(table: Element): String? {
    normalizeSpace(table.selectFirst("caption")?.text()).takeIf { it.isNotBlank() }?.let { return it }
    val heading = table.previousElementSiblings().firstOrNull { sibling ->
        sibling.tagName().lowercase() in setOf("h1", "h2", "h3", "h4", "legend")
    }
    return normalizeSpace(heading?.text()).takeIf { it.isNotBlank() }
}

fun parseStudentStatusProfile(html: String): StudentProfileData {
    val document = Jsoup.parse(html)
    val avatarUrl = document.select("img")
        .mapNotNull { normalizeSpace(it.attr("src")).takeIf { src -> src.isNotBlank() && !src.endsWith("/user.jpg") } }
        .firstOrNull()
    val sections = mutableListOf<ProfileSection>()
    val allFields = mutableListOf<ProfileField>()
    var currentTitle = "Profile"
    var currentSectionFields = mutableListOf<ProfileField>()

    fun flushSection() {
        if (currentSectionFields.isNotEmpty()) {
            sections += ProfileSection(currentTitle, currentSectionFields.toList())
            currentSectionFields = mutableListOf()
        }
    }

    fun addField(label: String, value: String) {
        val cleanedLabel = label.trim().trimEnd(':')
        if (cleanedLabel.isBlank()) return
        val field = ProfileField(cleanedLabel, value)
        allFields += field
        currentSectionFields += field
    }

    document.select("table.table, table").forEach { table ->
        table.select("tr").forEach { row ->
            val cells = row.select("> th, > td")
            if (cells.isEmpty()) return@forEach
            val texts = cells.map { normalizeSpace(it.text()) }
            val visible = texts.filter { it.isNotBlank() }
            if (visible.size == 1 && (cells.size == 1 || cells.first().hasAttr("colspan"))) {
                flushSection()
                currentTitle = visible.first()
                return@forEach
            }
            val pairTexts = cells.zip(texts).filterNot { (cell, _) -> cell.selectFirst("img") != null }.map { it.second }
            var index = 0
            while (index + 1 < pairTexts.size) {
                addField(pairTexts[index], pairTexts[index + 1])
                index += 2
            }
        }
        flushSection()
    }

    fun fieldByNeedle(vararg needles: String): String? =
        allFields.firstOrNull { field -> needles.any { field.label.contains(it, ignoreCase = true) } }?.value

    return StudentProfileData(
        name = fieldByNeedle("name", "名"),
        studentId = fieldByNeedle("student", "id", "no", "号"),
        gender = fieldByNeedle("gender", "sex"),
        birthday = fieldByNeedle("birth"),
        college = fieldByNeedle("college", "academy", "院"),
        major = fieldByNeedle("major", "业"),
        className = fieldByNeedle("class"),
        grade = fieldByNeedle("grade"),
        campus = fieldByNeedle("campus"),
        avatarUrl = avatarUrl,
        fields = allFields,
        sections = sections.ifEmpty { listOf(ProfileSection("Profile", allFields)) },
    )
}

fun isPassingScore(score: String?): Boolean {
    val value = normalizeSpace(score)
    if (value.isBlank()) return false
    parseCredit(value)?.let { return it >= 60.0 }
    val lower = value.lowercase()
    if (listOf("fail", "not pass", "unqualified").any { lower.contains(it) }) return false
    val grade = Regex("""(^|[^A-Z])([A-D][+-]?|F)($|[^A-Z])""").find(value.uppercase())?.groupValues?.get(2)
    if (grade != null) return grade in setOf("A+", "A", "A-", "B+", "B", "B-", "C+", "C", "C-", "D+", "D")
    return listOf("pass", "qualified").any { lower.contains(it) }
}

private fun scoreToProgressCourse(item: ScoreItem): AcademicProgressCourse =
    AcademicProgressCourse(
        term = item.term,
        courseName = item.courseName,
        credit = parseCredit(item.credit),
        score = item.score,
        status = if (isPassingScore(item.score)) "passed" else "attention",
        detail = item.detail,
    )

private fun computeCreditSummary(courses: List<AcademicProgressCourse>, targetCredits: Double? = null): CreditSummary {
    val attempted = courses.sumOf { it.credit ?: 0.0 }
    val passed = courses.filter { it.status == "passed" }.sumOf { it.credit ?: 0.0 }
    val failed = attempted - passed
    val denominator = targetCredits?.takeIf { it > 0 } ?: attempted
    val rate = if (denominator > 0) (passed / denominator * 100.0).coerceAtMost(100.0) else 0.0
    return CreditSummary(
        courseCount = courses.size,
        passedCourseCount = courses.count { it.status == "passed" },
        failedCourseCount = courses.count { it.status != "passed" },
        attemptedCredits = "%.2f".format(attempted).toDouble(),
        passedCredits = "%.2f".format(passed).toDouble(),
        failedCredits = "%.2f".format(failed).toDouble(),
        targetCredits = targetCredits,
        completionRate = "%.1f".format(rate).toDouble(),
    )
}

private fun makeCreditBucket(name: String, required: String?, earned: String?, parent: String? = null): CreditBucket? {
    val cleaned = normalizeSpace(name)
    if (cleaned.isBlank()) return null
    val requiredCredits = parseCredit(required)
    val earnedCredits = parseCredit(earned) ?: 0.0
    if (requiredCredits == null && earnedCredits == 0.0) return null
    val pending = requiredCredits?.let { (it - earnedCredits).coerceAtLeast(0.0) }
    val rate = requiredCredits?.takeIf { it > 0 }?.let { (earnedCredits / it * 100.0).coerceAtMost(100.0) }
    return CreditBucket(
        name = cleaned,
        requiredCredits = requiredCredits,
        earnedCredits = "%.2f".format(earnedCredits).toDouble(),
        pendingCredits = pending?.let { "%.2f".format(it).toDouble() },
        completionRate = rate?.let { "%.1f".format(it).toDouble() },
        parent = parent,
    )
}

fun parseAcademicProgressDetailPath(html: String): String? {
    val document = Jsoup.parse(html)
    document.select("a[href]").forEach { link ->
        val href = normalizeSpace(link.attr("href"))
        if (href.contains("stustudyview")) return href
    }
    return Regex("""(/school_census/schooltraininfo/stustudyview/\d+/)""").find(html)?.groupValues?.get(1)
}

fun parseAcademicProgress(html: String): AcademicProgressData {
    val document = Jsoup.parse(html)
    var mergedBuckets = emptyList<CreditBucket>()
    var detailBuckets = emptyList<CreditBucket>()
    var courses = emptyList<AcademicProgressCourse>()

    document.select("table").forEach { table ->
        val rows = tableRows(table)
        if (rows.isEmpty()) return@forEach
        val headers = rows.first()
        val headerText = headers.joinToString(" ")
        when {
            headerText.contains("merge", ignoreCase = true) || rows.first().size == 3 -> {
                mergedBuckets = rows.drop(1).mapNotNull { row ->
                    if (row.size < 3) null else makeCreditBucket(row[0], row[1], row[2])
                }
            }
            rows.first().size >= 4 && rows.drop(1).any { it.size >= 4 } -> {
                var parent: String? = null
                detailBuckets = rows.drop(1).mapNotNull { row ->
                    when {
                        row.size >= 4 -> {
                            parent = row[0]
                            makeCreditBucket(row[1], row[2], row[3], parent)
                        }
                        row.size >= 3 -> makeCreditBucket(row[0], row[1], row[2], parent)
                        else -> null
                    }
                }
            }
            headerText.contains("score", ignoreCase = true) || rows.first().size >= 6 -> {
                val termIndex = headerIndex(headers, "term", "学期")
                val courseIndex = headerIndex(headers, "course", "课程") ?: 2.coerceAtMost(headers.lastIndex)
                val creditIndex = headerIndex(headers, "credit", "学分")
                val examIndex = headerIndex(headers, "exam", "考试")
                val scoreIndex = headerIndex(headers, "score", "成绩")
                val groupIndex = headerIndex(headers, "group", "课组")
                courses = rows.drop(1).mapNotNull { row ->
                    if (row.size <= courseIndex) return@mapNotNull null
                    val rawCourse = row[courseIndex]
                    val match = Regex("""^([A-Z]\d+[A-Z]?)\s+(.+)$""").find(rawCourse)
                    val score = scoreIndex?.let { row.getOrNull(it) }
                    AcademicProgressCourse(
                        term = termIndex?.let { row.getOrNull(it) },
                        courseCode = match?.groupValues?.get(1),
                        courseName = match?.groupValues?.get(2) ?: rawCourse,
                        credit = creditIndex?.let { parseCredit(row.getOrNull(it)) },
                        examDate = examIndex?.let { row.getOrNull(it) },
                        score = score,
                        status = if (isPassingScore(score)) "passed" else "attention",
                        groupInfo = groupIndex?.let { row.getOrNull(it) },
                        source = "academic_progress",
                    )
                }
            }
        }
    }

    val buckets = mergedBuckets.ifEmpty { detailBuckets }
    val target = buckets.sumOf { it.requiredCredits ?: 0.0 }.takeIf { it > 0 }
    val earned = buckets.sumOf { it.earnedCredits }
    val missing = target?.let { (it - earned).coerceAtLeast(0.0) } ?: 0.0
    val rate = target?.takeIf { it > 0 }?.let { (earned / it * 100.0).coerceAtMost(100.0) } ?: 0.0
    return AcademicProgressData(
        summary = CreditSummary(
            courseCount = courses.size,
            passedCourseCount = courses.count { it.status == "passed" },
            failedCourseCount = buckets.count { bucket -> bucket.requiredCredits != null && bucket.earnedCredits < bucket.requiredCredits },
            attemptedCredits = "%.2f".format(earned).toDouble(),
            passedCredits = "%.2f".format(earned).toDouble(),
            failedCredits = "%.2f".format(missing).toDouble(),
            targetCredits = target,
            completionRate = "%.1f".format(rate).toDouble(),
        ),
        buckets = buckets,
        mergedBuckets = mergedBuckets,
        detailBuckets = detailBuckets,
        courses = courses,
    )
}

fun parseEmptyRooms(html: String, requestedQuery: Map<String, String?> = emptyMap()): EmptyRoomData {
    val document = Jsoup.parse(html)
    val (termOptions, selectedTerm) = parseSelectOptions(document, "zxjxjhh")
    val (weekOptions, selectedWeek) = parseSelectOptions(document, "zc")
    val (buildingOptions, selectedBuilding) = parseSelectOptions(document, "jxlh", includeBlank = true)
    val formQuery = mapOf(
        "term" to (selectedTerm ?: parseInputValue(document, "zxjxjhh")),
        "week" to (selectedWeek ?: parseInputValue(document, "zc")),
        "building" to (selectedBuilding ?: parseInputValue(document, "jxlh")),
        "room" to parseInputValue(document, "jash"),
    ).filterValues { it != null }
    // Empty building/room values explicitly clear a filter and belong in cache metadata.
    val query = formQuery + requestedQuery.filterValues { it != null }
    fun emptyData(
        days: List<String> = emptyList(),
        periods: List<Int> = emptyList(),
        slots: List<EmptyRoomSlotHeader> = emptyList(),
        rooms: List<EmptyRoomRow> = emptyList(),
    ) = EmptyRoomData(
        query = query,
        availableTerms = termOptions,
        availableWeeks = weekOptions,
        availableBuildings = buildingOptions,
        days = days,
        periods = periods,
        slots = slots,
        rooms = rooms,
    )

    val table = document.selectFirst("table.table, table") ?: return emptyData()
    val rows = table.select("> tbody > tr, > tr")
    if (rows.size < 2) return emptyData()

    val slotDays = mutableListOf<Pair<String, String?>>()
    rows[0].select("> th").drop(1).forEach { cell ->
        val parts = normalizeSpace(cell.text()).split(" ").filter { it.isNotBlank() }
        val day = parts.getOrNull(0).orEmpty()
        val date = parts.getOrNull(1)
        val colspan = cell.attr("colspan").toIntOrNull() ?: 1
        repeat(colspan) { slotDays += day to date }
    }

    val slots = mutableListOf<EmptyRoomSlotHeader>()
    val periods = linkedSetOf<Int>()
    rows[1].select("> th, > td").drop(1).forEachIndexed { index, cell ->
        val period = normalizeSpace(cell.text()).toIntOrNull() ?: index + 1
        val (day, date) = slotDays.getOrNull(index) ?: ("" to null)
        slots += EmptyRoomSlotHeader(day = day, date = date, period = period)
        periods += period
    }

    val rooms = rows.drop(2).mapNotNull { row ->
        val cells = row.select("> th, > td")
        if (cells.size < 2) return@mapNotNull null
        val roomText = normalizeSpace(cells.firstOrNull()?.text().orEmpty())
        if (roomText.isBlank()) return@mapNotNull null
        val seat = Regex("""\(([^)]+)\)""").find(roomText)?.groupValues?.get(1)
        val cellStates = cells.drop(1).map { cell ->
            val style = cell.attr("style").lowercase()
            val text = normalizeSpace(cell.text())
            classifyEmptyRoomCell(style, text)
        }
        EmptyRoomRow(
            room = roomText.substringBefore(" "),
            seatLabel = seat,
            availability = cellStates.map { it == EmptyRoomCellFree },
            cellStates = cellStates,
        )
    }

    val days = slots.map { normalizeSpace(listOfNotNull(it.day, it.date).joinToString(" ")) }
        .filter { it.isNotBlank() }
        .distinct()
    return emptyData(days = days, periods = periods.toList(), slots = slots, rooms = rooms)
}

private const val EmptyRoomCellFree = "free"
private const val EmptyRoomCellBusy = "busy"
private const val EmptyRoomCellNotice = "notice"

private fun classifyEmptyRoomCell(style: String, text: String): String {
    val normalizedStyle = style.replace(Regex("""\s+"""), "")
    return when {
        isWhiteCellStyle(normalizedStyle) -> EmptyRoomCellFree
        isNoticeCellStyle(normalizedStyle) -> EmptyRoomCellNotice
        isBusyCellStyle(normalizedStyle) -> EmptyRoomCellBusy
        normalizedStyle.isNotBlank() -> EmptyRoomCellNotice
        listOf("occupied", "busy", "unavailable", "占用", "有课", "不可").any { text.contains(it, ignoreCase = true) } -> EmptyRoomCellBusy
        listOf("free", "available", "空闲", "无课", "可用").any { text.contains(it, ignoreCase = true) } -> EmptyRoomCellFree
        else -> if (text.isBlank()) EmptyRoomCellFree else EmptyRoomCellBusy
    }
}

private fun isWhiteCellStyle(style: String): Boolean =
    style.contains("white") ||
        style.contains("rgb(255,255,255)") ||
        containsHexColor(style, "#fff") ||
        containsHexColor(style, "#ffffff")

private fun isNoticeCellStyle(style: String): Boolean =
    style.contains("yellow") || containsHexColor(style, "#ff0") || containsHexColor(style, "#ffff00")

private fun isBusyCellStyle(style: String): Boolean =
    style.contains("red") ||
        containsHexColor(style, "#f99") ||
        containsHexColor(style, "#ff9999") ||
        containsHexColor(style, "#e46868")

private fun containsHexColor(style: String, color: String): Boolean =
    Regex("""${Regex.escape(color)}(?![0-9a-f])""").containsMatchIn(style)

fun parseScorecardProgress(scorecardHtml: String, scores: ScoreData? = null): AcademicProgressData {
    val courses = scores?.items.orEmpty().map(::scoreToProgressCourse)
    return AcademicProgressData(currentTerm = scores?.currentTerm, summary = computeCreditSummary(courses), courses = courses)
}
