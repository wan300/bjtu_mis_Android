package cn.edu.bjtu.mis.ui.screens

import cn.edu.bjtu.mis.data.repository.CalendarDashboard

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.edu.bjtu.mis.data.calendar.TaskCalendarBuckets
import cn.edu.bjtu.mis.data.calendar.groupTaskCalendarBuckets
import cn.edu.bjtu.mis.data.employment.EmploymentCalendarEvent
import cn.edu.bjtu.mis.data.employment.EmploymentCalendarSyncStore
import cn.edu.bjtu.mis.data.employment.employmentCalendarEventTypeLabel
import cn.edu.bjtu.mis.data.exporting.CalendarExportData
import cn.edu.bjtu.mis.data.exporting.CalendarExportScope
import cn.edu.bjtu.mis.data.exporting.ScheduleExportContentBuilder
import cn.edu.bjtu.mis.data.exporting.ScheduleExportFormat
import cn.edu.bjtu.mis.data.homework.HomeworkStatusKind
import cn.edu.bjtu.mis.data.homework.homeworkCalendarStatusLabel
import cn.edu.bjtu.mis.data.homework.homeworkStatusKind
import cn.edu.bjtu.mis.data.repository.EmploymentConsultationRepository
import cn.edu.bjtu.mis.data.repository.ModuleLoadStrategy
import cn.edu.bjtu.mis.data.repository.ModuleRepository
import cn.edu.bjtu.mis.model.AcademicCalendarTerm
import cn.edu.bjtu.mis.model.AcademicCalendarWeek
import cn.edu.bjtu.mis.model.AcademicMonthCalendar
import cn.edu.bjtu.mis.model.AcademicMonthDay
import cn.edu.bjtu.mis.model.HomeworkItem
import cn.edu.bjtu.mis.model.TermOption
import cn.edu.bjtu.mis.model.UserTodoDraft
import cn.edu.bjtu.mis.model.UserTodoItem
import cn.edu.bjtu.mis.data.todo.TodoReminder
import cn.edu.bjtu.mis.data.todo.TodoReminders
import kotlinx.coroutines.flow.flowOf
import java.time.Instant
import java.time.ZoneId
import cn.edu.bjtu.mis.model.buildAcademicCalendar
import cn.edu.bjtu.mis.model.buildAcademicMonthCalendar
import cn.edu.bjtu.mis.model.defaultAcademicMonth
import cn.edu.bjtu.mis.ui.components.InfoCard
import cn.edu.bjtu.mis.ui.components.KeyValue
import cn.edu.bjtu.mis.ui.components.LoadState
import cn.edu.bjtu.mis.ui.components.LoadingOrError
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

private data class CalendarCellChip(
    val text: String,
    val color: Color,
)

private enum class CalendarViewMode {
    Month,
    Week,
}

private val CalendarMonthTitleFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy年M月")
private val CalendarDayTitleFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)
private val CalendarWeekdayLabels = listOf("日", "一", "二", "三", "四", "五", "六")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    repository: ModuleRepository,
    employmentRepository: EmploymentConsultationRepository,
    employmentCalendarSyncStore: EmploymentCalendarSyncStore,
    todoReminders: TodoReminders? = null,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    onOpenHomework: (HomeworkItem) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val exportSchedule = rememberScheduleExportLauncher()
    val employmentSyncEnabled by employmentCalendarSyncStore.enabled.collectAsState(initial = false)
    val reminders by (todoReminders?.store?.records ?: flowOf(emptyMap<Long, TodoReminder>())).collectAsState(initial = emptyMap())
    val today = remember { LocalDate.now() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedCalendarTerm by remember { mutableStateOf<String?>(null) }
    var selectedMonth by remember { mutableStateOf<YearMonth?>(null) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var addTodoDate by remember { mutableStateOf<LocalDate?>(null) }
    var todoSaveError by remember { mutableStateOf<String?>(null) }
    var showTermOverview by remember { mutableStateOf(false) }
    var calendarViewMode by remember { mutableStateOf(CalendarViewMode.Month) }
    var showCalendarExportDialog by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<LoadState<CalendarDashboard>>(LoadState.Loading) }
    var employmentEventsState by remember {
        mutableStateOf<LoadState<List<EmploymentCalendarEvent>>>(LoadState.Data(emptyList()))
    }
    var employmentInitialLoadConsumed by remember { mutableStateOf(false) }

    fun loadDashboard(strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst) {
        scope.launch {
            state = LoadState.Loading
            runCatching {
                repository.calendarDashboard(strategy)
            }.onSuccess {
                state = LoadState.Data(it)
            }.onFailure {
                state = LoadState.Error(it.message ?: "学年日历加载失败")
            }
        }
    }

    fun setEmploymentSyncEnabled(enabled: Boolean) {
        scope.launch {
            employmentCalendarSyncStore.save(enabled)
            if (!enabled) {
                employmentEventsState = LoadState.Data(emptyList())
            }
        }
    }

    fun openUri(url: String) {
        runCatching { uriHandler.openUri(url) }
    }

    suspend fun refreshTodosOnly() {
        val current = state
        if (current !is LoadState.Data) {
            loadDashboard()
            return
        }
        runCatching { repository.userTodos() }
            .onSuccess { todos ->
                state = LoadState.Data(current.value.copy(todos = todos))
            }
            .onFailure {
                todoSaveError = it.message ?: "待办刷新失败"
            }
    }

    fun saveTodo(draft: UserTodoDraft) {
        scope.launch {
            runCatching { repository.saveUserTodo(draft) }
                .onSuccess {
                    addTodoDate = null
                    todoSaveError = null
                    refreshTodosOnly()
                }
                .onFailure {
                    todoSaveError = it.message ?: "待办保存失败"
                }
        }
    }

    fun setTodoDone(todo: UserTodoItem, done: Boolean) {
        scope.launch {
            runCatching { repository.setUserTodoDone(todo.id, done) }
                .onSuccess {
                    todoSaveError = null
                    refreshTodosOnly()
                }
                .onFailure {
                    todoSaveError = it.message ?: "待办状态更新失败"
                }
        }
    }

    fun deleteTodo(todo: UserTodoItem) {
        scope.launch {
            runCatching { repository.deleteUserTodo(todo.id) }
                .onSuccess {
                    todoSaveError = null
                    refreshTodosOnly()
                }
                .onFailure {
                    todoSaveError = it.message ?: "待办删除失败"
                }
        }
    }

    LaunchedEffect(Unit) {
        loadDashboard(initialLoadStrategy)
    }

    LaunchedEffect(employmentSyncEnabled) {
        val strategy = if (employmentInitialLoadConsumed) ModuleLoadStrategy.NetworkFirst else initialLoadStrategy
        employmentInitialLoadConsumed = true
        if (employmentSyncEnabled) {
            employmentEventsState = LoadState.Loading
            runCatching { employmentRepository.calendarEvents(strategy = strategy) }
                .onSuccess { employmentEventsState = LoadState.Data(it) }
                .onFailure {
                    employmentEventsState = LoadState.Error(it.message ?: "就业活动同步失败")
                }
        } else {
            employmentEventsState = LoadState.Data(emptyList())
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (val current = state) {
            LoadState.Loading, is LoadState.Error -> LoadingOrError(current)
            is LoadState.Data -> {
                val dashboard = current.value
                val data = dashboard.calendarEnvelope.data
                val terms = data.availableTerms
                val fallbackTerm = data.currentTerm?.takeIf { it.isNotBlank() }
                    ?: terms.firstOrNull { it.selected }?.value
                    ?: terms.firstOrNull()?.value
                val effectiveTerm = selectedCalendarTerm
                    ?.takeIf { candidate -> terms.isEmpty() || terms.any { it.value == candidate } }
                    ?: fallbackTerm
                val selectedTerm = terms.firstOrNull { it.value == effectiveTerm }
                val calendar = buildAcademicCalendar(effectiveTerm, selectedTerm?.label)
                val displayMonth = selectedMonth ?: defaultAcademicMonth(today, calendar)
                val monthCalendar = remember(displayMonth) { buildAcademicMonthCalendar(displayMonth) }
                val selectedIsCurrentTerm = effectiveTerm != null && effectiveTerm == data.currentTerm
                val currentWeek = if (selectedIsCurrentTerm) parseWeekNumber(data.currentWeek) else null
                val taskBucketsByDate = remember(dashboard.homework, dashboard.exams, data.items) {
                    groupTaskCalendarBuckets(
                        homework = dashboard.homework,
                        exams = dashboard.exams,
                        calendarItems = data.items,
                    )
                }
                val todosByDate = remember(dashboard.todos) { dashboard.todos.groupByTodoDate() }
                val employmentEvents = if (employmentSyncEnabled) {
                    (employmentEventsState as? LoadState.Data)?.value.orEmpty()
                } else {
                    emptyList()
                }
                val employmentEventsByDate = remember(employmentEvents) { employmentEvents.groupByEmploymentEventDate() }
                val calendarExportData = remember(taskBucketsByDate, todosByDate, employmentEventsByDate) {
                    CalendarExportData(
                        bucketsByDate = taskBucketsByDate,
                        todosByDate = todosByDate,
                        employmentEventsByDate = employmentEventsByDate,
                    )
                }

                fun exportCalendar(scopeValue: CalendarExportScope, format: ScheduleExportFormat) {
                    val document = when (scopeValue) {
                        CalendarExportScope.TermOverview -> {
                            val termCalendar = calendar
                            if (termCalendar == null) {
                                Toast.makeText(context, "无法生成学期周表", Toast.LENGTH_LONG).show()
                                return
                            }
                            ScheduleExportContentBuilder.buildTermOverview(
                                calendar = termCalendar,
                                currentWeek = currentWeek,
                                today = today,
                            )
                        }
                        CalendarExportScope.Month -> ScheduleExportContentBuilder.buildMonthView(
                            calendar = monthCalendar,
                            exportData = calendarExportData,
                            today = today,
                        )
                        CalendarExportScope.Week -> {
                            val anchor = ScheduleExportContentBuilder.weekAnchorDate(
                                selectedDate = selectedDate,
                                today = today,
                                displayMonth = displayMonth,
                            )
                            ScheduleExportContentBuilder.buildWeekView(
                                anchorDate = anchor,
                                exportData = calendarExportData,
                                today = today,
                            )
                        }
                    }
                    exportSchedule(document, format)
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    if (dashboard.campusErrors.isNotEmpty()) item {
                        Text(dashboard.campusErrors.joinToString("\n"), color = MaterialTheme.colorScheme.error)
                    }
                    todoSaveError?.let { message -> item {
                        Text(message, color = MaterialTheme.colorScheme.error)
                    } }
                    item {
                        EmploymentCalendarSyncCard(
                            enabled = employmentSyncEnabled,
                            eventsState = employmentEventsState,
                            onEnabledChange = ::setEmploymentSyncEnabled,
                        )
                    }
                    item {
                        CalendarMonthHeaderCard(
                            month = displayMonth,
                            terms = terms,
                            selectedTerm = effectiveTerm,
                            calendar = calendar,
                            today = today,
                            currentWeek = currentWeek,
                            onPreviousMonth = { selectedMonth = displayMonth.minusMonths(1) },
                            onNextMonth = { selectedMonth = displayMonth.plusMonths(1) },
                            onToday = {
                                selectedMonth = YearMonth.from(today)
                                selectedDate = today
                            },
                            onExport = { showCalendarExportDialog = true },
                            onTermChange = { value ->
                                selectedCalendarTerm = value
                                val term = terms.firstOrNull { it.value == value }
                                selectedMonth = buildAcademicCalendar(value, term?.label)
                                    ?.let { defaultAcademicMonth(today, it) }
                                selectedDate = null
                                showTermOverview = false
                            },
                        )
                    }
                    item {
                        CalendarMonthOverviewCard(
                            month = displayMonth,
                            taskBucketsByDate = taskBucketsByDate,
                            todos = dashboard.todos,
                            employmentEvents = employmentEvents,
                        )
                    }
                    item {
                        CalendarViewModeSelector(
                            value = calendarViewMode,
                            onValueChange = { calendarViewMode = it },
                        )
                    }
                    item {
                        if (calendarViewMode == CalendarViewMode.Month) {
                            AcademicMonthGrid(
                                calendar = monthCalendar,
                                today = today,
                                selectedDate = selectedDate,
                                taskBucketsByDate = taskBucketsByDate,
                                todosByDate = todosByDate,
                                employmentEventsByDate = employmentEventsByDate,
                                onDateClick = { selectedDate = it },
                            )
                        } else {
                            CalendarWeekTaskList(
                                anchorDate = selectedDate ?: if (YearMonth.from(today) == displayMonth) today else displayMonth.atDay(1),
                                today = today,
                                selectedDate = selectedDate,
                                taskBucketsByDate = taskBucketsByDate,
                                todosByDate = todosByDate,
                                employmentEventsByDate = employmentEventsByDate,
                                onDateClick = { selectedDate = it },
                            )
                        }
                    }
                    item {
                        OutlinedButton(
                            onClick = { showTermOverview = !showTermOverview },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (showTermOverview) "收起学期总览/周表" else "展开学期总览/周表")
                        }
                    }
                    if (showTermOverview) {
                        if (calendar == null) {
                            item {
                                InfoCard("无法生成校历", subtitle = effectiveTerm) {
                                    Text("当前学期编码或标签无法解析。", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        } else {
                            item {
                                AcademicCalendarTable(
                                    calendar = calendar,
                                    currentWeek = currentWeek,
                                    today = today,
                                )
                            }
                        }
                    }
                }

                FloatingActionButton(
                    onClick = {
                        todoSaveError = null
                        addTodoDate = selectedDate ?: today
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(18.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "新增待办")
                }

                if (showCalendarExportDialog) {
                    CalendarExportDialog(
                        termOverviewAvailable = calendar != null,
                        onDismiss = { showCalendarExportDialog = false },
                        onExport = { scopeValue, format ->
                            showCalendarExportDialog = false
                            exportCalendar(scopeValue, format)
                        },
                    )
                }

                selectedDate?.let { date ->
                    ModalBottomSheet(
                        onDismissRequest = { selectedDate = null },
                        sheetState = sheetState,
                    ) {
                        CalendarDayDetail(
                            date = date,
                            buckets = taskBucketsByDate[date] ?: TaskCalendarBuckets(),
                            todos = todosByDate[date].orEmpty(),
                            employmentEvents = employmentEventsByDate[date].orEmpty(),
                            onAddTodo = {
                                todoSaveError = null
                                addTodoDate = date
                            },
                            onToggleTodo = ::setTodoDone,
                            onDeleteTodo = ::deleteTodo,
                            reminders = reminders,
                            onCancelReminder = { todo ->
                                scope.launch {
                                    runCatching { todoReminders?.cancel(todo.id) }
                                        .onFailure { todoSaveError = "提醒取消失败，请重试" }
                                }
                            },
                            onOpenEmploymentUrl = ::openUri,
                            onOpenHomework = { item ->
                                selectedDate = null
                                onOpenHomework(item)
                            },
                        )
                    }
                }

                addTodoDate?.let { date ->
                    UserTodoEditorDialog(
                        initialDate = date,
                        errorMessage = todoSaveError,
                        onDismiss = {
                            addTodoDate = null
                            todoSaveError = null
                        },
                        onSave = ::saveTodo,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmploymentCalendarSyncCard(
    enabled: Boolean,
    eventsState: LoadState<List<EmploymentCalendarEvent>>,
    onEnabledChange: (Boolean) -> Unit,
) {
    InfoCard(
        title = "同步就业活动",
        subtitle = if (enabled) "宣讲会、双选会将显示在学年日历中" else "开启后在日历中展示宣讲会、双选会",
        trailing = {
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
            )
        },
    ) {
        val statusText = when (eventsState) {
            LoadState.Loading -> "正在同步就业活动"
            is LoadState.Data -> if (enabled) "已同步 ${eventsState.value.size} 项就业活动" else "当前未开启同步"
            is LoadState.Error -> eventsState.message
        }
        Text(
            statusText,
            style = MaterialTheme.typography.bodySmall,
            color = if (eventsState is LoadState.Error) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun CalendarMonthHeaderCard(
    month: YearMonth,
    terms: List<TermOption>,
    selectedTerm: String?,
    calendar: AcademicCalendarTerm?,
    today: LocalDate,
    currentWeek: Int?,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToday: () -> Unit,
    onExport: () -> Unit,
    onTermChange: (String) -> Unit,
) {
    InfoCard(
        title = month.format(CalendarMonthTitleFormatter),
        subtitle = calendar?.label ?: selectedTerm,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPreviousMonth) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "上个月")
            }
            Text(
                month.format(CalendarMonthTitleFormatter),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = onNextMonth) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "下个月")
            }
        }
        if (terms.isNotEmpty()) {
            TermSelector(
                terms = terms,
                value = selectedTerm,
                onValueChange = onTermChange,
            )
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onToday, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                Icon(Icons.Filled.Today, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("今天")
            }
            OutlinedButton(onClick = onExport, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("导出")
            }
            AssistChip(onClick = {}, label = { Text("今天 ${today.monthValue}月${today.dayOfMonth}日") })
            calendar?.let {
                AssistChip(onClick = {}, label = { Text("${it.weeks.size} 周") })
            }
            if (currentWeek != null) {
                AssistChip(onClick = {}, label = { Text("第 $currentWeek 教学周") })
            }
        }
    }
}

@Composable
private fun CalendarExportDialog(
    termOverviewAvailable: Boolean,
    onDismiss: () -> Unit,
    onExport: (CalendarExportScope, ScheduleExportFormat) -> Unit,
) {
    var scope by remember(termOverviewAvailable) {
        mutableStateOf(if (termOverviewAvailable) CalendarExportScope.TermOverview else CalendarExportScope.Month)
    }
    var format by remember { mutableStateOf(ScheduleExportFormat.Pdf) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出学年日历") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("导出范围")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CalendarExportScope.values().forEach { option ->
                        FilterChip(
                            selected = scope == option,
                            enabled = option != CalendarExportScope.TermOverview || termOverviewAvailable,
                            onClick = { scope = option },
                            label = {
                                Text(
                                    if (option == CalendarExportScope.TermOverview && !termOverviewAvailable) {
                                        "${option.label}（无法生成）"
                                    } else {
                                        option.label
                                    }
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Text("导出格式")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ScheduleExportFormat.values().forEach { option ->
                        FilterChip(
                            selected = format == option,
                            onClick = { format = option },
                            label = { Text(option.label) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onExport(scope, format) }) {
                Text("导出")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun CalendarMonthOverviewCard(
    month: YearMonth,
    taskBucketsByDate: Map<LocalDate, TaskCalendarBuckets>,
    todos: List<UserTodoItem>,
    employmentEvents: List<EmploymentCalendarEvent>,
) {
    val monthBuckets = taskBucketsByDate
        .filterKeys { YearMonth.from(it) == month }
        .values
    val monthTodos = todos.filter { item ->
        item.todoDate()?.let { YearMonth.from(it) == month } == true
    }
    val monthEmploymentEvents = employmentEvents.filter { YearMonth.from(it.date) == month }
    val monthHomework = monthBuckets.flatMap { it.homeworkDues }
    val homeworkStarts = monthBuckets.sumOf { it.homeworkStarts.size }
    val unsubmittedHomework = monthHomework.count { homeworkCalendarStatusLabel(it) == "未提交" }
    val homeworkDues = monthBuckets.sumOf { it.homeworkDues.size }
    val exams = monthBuckets.sumOf { it.exams.size }
    val schoolCalendarItems = monthBuckets.sumOf { it.calendarItems.size }
    val doneHomework = monthHomework.size - unsubmittedHomework
    val openTodos = monthTodos.count { !it.done }

    InfoCard(
        title = "本月任务",
        subtitle = month.format(CalendarMonthTitleFormatter),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                CalendarMetric("作业开始", homeworkStarts.toString(), Color(0xFF2F8DD8), Modifier.weight(1f))
                CalendarMetric("作业截止", homeworkDues.toString(), Color(0xFFD64B6B), Modifier.weight(1f))
                CalendarMetric("考试", exams.toString(), Color(0xFFFF8A00), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                CalendarMetric("校历", schoolCalendarItems.toString(), Color(0xFF0E9D9D), Modifier.weight(1f))
                CalendarMetric("待办", openTodos.toString(), Color(0xFF7C58C2), Modifier.weight(1f))
                CalendarMetric("就业", monthEmploymentEvents.size.toString(), Color(0xFF0E7490), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CalendarMetric(label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(tint.copy(alpha = 0.1f), MaterialTheme.shapes.medium)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = tint)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CalendarViewModeSelector(
    value: CalendarViewMode,
    onValueChange: (CalendarViewMode) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        FilterChip(
            selected = value == CalendarViewMode.Month,
            onClick = { onValueChange(CalendarViewMode.Month) },
            label = { Text("月视图") },
            modifier = Modifier.weight(1f),
        )
        FilterChip(
            selected = value == CalendarViewMode.Week,
            onClick = { onValueChange(CalendarViewMode.Week) },
            label = { Text("周视图") },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CalendarWeekTaskList(
    anchorDate: LocalDate,
    today: LocalDate,
    selectedDate: LocalDate?,
    taskBucketsByDate: Map<LocalDate, TaskCalendarBuckets>,
    todosByDate: Map<LocalDate, List<UserTodoItem>>,
    employmentEventsByDate: Map<LocalDate, List<EmploymentCalendarEvent>>,
    onDateClick: (LocalDate) -> Unit,
) {
    val weekStart = remember(anchorDate) {
        anchorDate.minusDays((anchorDate.dayOfWeek.value - 1).toLong())
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (0L..6L).forEach { offset ->
            val date = weekStart.plusDays(offset)
            CalendarWeekDayRow(
                date = date,
                isToday = date == today,
                selected = date == selectedDate,
                buckets = taskBucketsByDate[date] ?: TaskCalendarBuckets(),
                todos = todosByDate[date].orEmpty(),
                employmentEvents = employmentEventsByDate[date].orEmpty(),
                onClick = { onDateClick(date) },
            )
        }
    }
}

private fun buildCalendarChips(
    buckets: TaskCalendarBuckets,
    todos: List<UserTodoItem>,
    employmentEvents: List<EmploymentCalendarEvent>,
): List<CalendarCellChip> = buildList {
    buckets.homeworkStarts.forEach { item ->
        add(CalendarCellChip(text = "开始 ${item.title}", color = Color(0xFF2F8DD8)))
    }
    buckets.homeworkDues.forEach { item ->
        val submitted = homeworkStatusKind(item) == HomeworkStatusKind.Done
        add(
            CalendarCellChip(
                text = "截止 ${item.title}",
                color = if (submitted) Color(0xFF2AA876) else Color(0xFFD64B6B),
            )
        )
    }
    buckets.exams.forEach { exam ->
        add(CalendarCellChip(text = "考试 ${exam.courseName}", color = Color(0xFFFF8A00)))
    }
    buckets.calendarItems.forEach { item ->
        add(CalendarCellChip(text = item.note.orEmpty(), color = Color(0xFF0E9D9D)))
    }
    todos.forEach { todo ->
        add(CalendarCellChip(text = todo.title, color = if (todo.done) Color(0xFF6B7280) else Color(0xFF7C58C2)))
    }
    employmentEvents.forEach { event ->
        add(
            CalendarCellChip(
                text = "${employmentCalendarEventTypeLabel(event.type)} ${event.title}",
                color = Color(0xFF0E7490),
            )
        )
    }
}

@Composable
private fun CalendarWeekDayRow(
    date: LocalDate,
    isToday: Boolean,
    selected: Boolean,
    buckets: TaskCalendarBuckets,
    todos: List<UserTodoItem>,
    employmentEvents: List<EmploymentCalendarEvent>,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val borderColor = if (selected) colorScheme.primary else colorScheme.outline.copy(alpha = 0.32f)
    val chips = buildCalendarChips(buckets, todos, employmentEvents)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (isToday) colorScheme.primaryContainer.copy(alpha = 0.28f) else colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(if (selected) 1.5.dp else 0.5.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(52.dp)) {
                Text(
                    text = date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isToday || selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isToday || selected) colorScheme.primary else colorScheme.onSurface,
                )
                Text(
                    text = CalendarWeekdayLabels[date.dayOfWeek.value % 7],
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (chips.isEmpty()) {
                    Text("暂无任务", style = MaterialTheme.typography.bodySmall, color = colorScheme.onSurfaceVariant)
                } else {
                    chips.take(4).forEach { CalendarCellChipView(it) }
                    if (chips.size > 4) {
                        Text(
                            "+${chips.size - 4}",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AcademicMonthGrid(
    calendar: AcademicMonthCalendar,
    today: LocalDate,
    selectedDate: LocalDate?,
    taskBucketsByDate: Map<LocalDate, TaskCalendarBuckets>,
    todosByDate: Map<LocalDate, List<UserTodoItem>>,
    employmentEventsByDate: Map<LocalDate, List<EmploymentCalendarEvent>>,
    onDateClick: (LocalDate) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.36f)),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                CalendarWeekdayLabels.forEach { label ->
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            calendar.weeks.forEach { week ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    week.days.forEach { day ->
                        AcademicMonthDayCell(
                            day = day,
                            today = today,
                            selected = selectedDate == day.date,
                            buckets = taskBucketsByDate[day.date] ?: TaskCalendarBuckets(),
                            todos = todosByDate[day.date].orEmpty(),
                            employmentEvents = employmentEventsByDate[day.date].orEmpty(),
                            onClick = { onDateClick(day.date) },
                            modifier = Modifier
                                .weight(1f)
                                .height(92.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AcademicMonthDayCell(
    day: AcademicMonthDay,
    today: LocalDate,
    selected: Boolean,
    buckets: TaskCalendarBuckets,
    todos: List<UserTodoItem>,
    employmentEvents: List<EmploymentCalendarEvent>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val isToday = day.date == today
    val borderColor = when {
        selected -> colorScheme.primary
        isToday -> colorScheme.primary.copy(alpha = 0.62f)
        else -> colorScheme.outline.copy(alpha = 0.28f)
    }
    val background = when {
        selected -> colorScheme.primaryContainer.copy(alpha = 0.44f)
        isToday -> colorScheme.primaryContainer.copy(alpha = 0.28f)
        else -> colorScheme.surface
    }
    val dayColor = when {
        !day.inMonth -> colorScheme.onSurfaceVariant.copy(alpha = 0.48f)
        isToday || selected -> colorScheme.primary
        else -> colorScheme.onSurface
    }
    val chips = remember(buckets, todos, employmentEvents) {
        buildList {
            buckets.homeworkStarts.forEach { item ->
                add(
                    CalendarCellChip(
                        text = "开始 ${item.title}",
                        color = Color(0xFF2F8DD8),
                    )
                )
            }
            buckets.homeworkDues.forEach { item ->
                val submitted = homeworkCalendarStatusLabel(item) == "已提交"
                add(
                    CalendarCellChip(
                        text = "截止 ${item.title}",
                        color = if (submitted) Color(0xFF2AA876) else Color(0xFFD64B6B),
                    )
                )
            }
            buckets.exams.forEach { exam ->
                add(
                    CalendarCellChip(
                        text = "考试 ${exam.courseName}",
                        color = Color(0xFFFF8A00),
                    )
                )
            }
            buckets.calendarItems.forEach { item ->
                add(
                    CalendarCellChip(
                        text = item.note.orEmpty(),
                        color = Color(0xFF0E9D9D),
                    )
                )
            }
            todos.forEach { todo ->
                add(
                    CalendarCellChip(
                        text = todo.title,
                        color = if (todo.done) Color(0xFF6B7280) else Color(0xFF7C58C2),
                    )
                )
            }
            employmentEvents.forEach { event ->
                add(
                    CalendarCellChip(
                        text = "${employmentCalendarEventTypeLabel(event.type)} ${event.title}",
                        color = Color(0xFF0E7490),
                    )
                )
            }
        }
    }

    Column(
        modifier = modifier
            .border(if (selected) 1.5.dp else 0.5.dp, borderColor, MaterialTheme.shapes.small)
            .background(background, MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = day.date.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isToday || selected) FontWeight.SemiBold else FontWeight.Normal,
            color = dayColor,
            maxLines = 1,
        )
        chips.take(2).forEach { chip ->
            CalendarCellChipView(chip)
        }
        if (chips.size > 2) {
            Text(
                text = "+${chips.size - 2}",
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CalendarCellChipView(chip: CalendarCellChip) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(chip.color.copy(alpha = 0.16f), MaterialTheme.shapes.small)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text(
            chip.text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = chip.color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CalendarDayDetail(
    date: LocalDate,
    buckets: TaskCalendarBuckets,
    todos: List<UserTodoItem>,
    employmentEvents: List<EmploymentCalendarEvent>,
    onAddTodo: () -> Unit,
    onToggleTodo: (UserTodoItem, Boolean) -> Unit,
    onDeleteTodo: (UserTodoItem) -> Unit,
    reminders: Map<Long, TodoReminder>,
    onCancelReminder: (UserTodoItem) -> Unit,
    onOpenEmploymentUrl: (String) -> Unit,
    onOpenHomework: (HomeworkItem) -> Unit,
) {
    val homeworkStarts = buckets.homeworkStarts
    val homework = buckets.homeworkDues
    val exams = buckets.exams
    val calendarItems = buckets.calendarItems
    val summary = buildList {
        if (homeworkStarts.isNotEmpty()) add("${homeworkStarts.size} 项作业开始")
        if (homework.isNotEmpty()) add("${homework.size} 项作业截止")
        if (exams.isNotEmpty()) add("${exams.size} 场考试")
        if (calendarItems.isNotEmpty()) add("${calendarItems.size} 项校历安排")
        if (todos.isNotEmpty()) add("${todos.size} 项自定义待办")
        if (employmentEvents.isNotEmpty()) add("${employmentEvents.size} 项就业活动")
    }.ifEmpty { listOf("当天暂无任务") }.joinToString(" · ")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    date.format(CalendarDayTitleFormatter),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onAddTodo) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("新增")
            }
        }

        if (buckets.isEmpty && todos.isEmpty() && employmentEvents.isEmpty()) {
            Text("当天暂无任务。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (homeworkStarts.isNotEmpty()) {
            Text("今天开始的作业", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            homeworkStarts.forEach { item ->
                InfoCard(
                    title = item.title,
                    modifier = Modifier.clickable { onOpenHomework(item) },
                    subtitle = item.course,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                        KeyValue("开始", item.openedAt, Modifier.weight(1f))
                        KeyValue("截止", item.dueAt, Modifier.weight(1f))
                    }
                    KeyValue("内容", item.contentExcerpt)
                }
            }
        }

        if (exams.isNotEmpty()) {
            Text("今天的考试", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            exams.forEach { exam ->
                InfoCard(
                    title = exam.courseName,
                    subtitle = exam.examMode,
                ) {
                    KeyValue("安排", exam.schedule)
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                        KeyValue("状态", exam.status, Modifier.weight(1f))
                        KeyValue("备注", exam.remark, Modifier.weight(1f))
                    }
                }
            }
        }

        if (calendarItems.isNotEmpty()) {
            Text("校历安排", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            calendarItems.forEach { item ->
                InfoCard(
                    title = item.note.orEmpty(),
                    subtitle = item.week,
                ) {
                    KeyValue("日期", item.date)
                }
            }
        }

        if (homework.isNotEmpty()) {
            Text("作业截止", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            homework.forEach { item ->
                InfoCard(
                    title = item.title,
                    modifier = Modifier.clickable { onOpenHomework(item) },
                    subtitle = item.course,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        ModuleStatusPill(
                            text = homeworkCalendarStatusLabel(item),
                            color = if (homeworkCalendarStatusLabel(item) == "已提交") Color(0xFF2AA876) else Color(0xFFD64B6B),
                        )
                        item.submissionStatus?.takeIf { it.isNotBlank() }?.let {
                            ModuleStatusPill(text = it, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                        KeyValue("截止", item.dueAt, Modifier.weight(1f))
                        KeyValue("提交", item.submittedAt, Modifier.weight(1f))
                    }
                    KeyValue("内容", item.contentExcerpt)
                }
            }
        }

        if (todos.isNotEmpty()) {
            Text("自定义待办", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            todos.forEach { todo ->
                UserTodoRow(
                    todo = todo,
                    reminder = reminders[todo.id]?.takeIf { !todo.done && it.todoUpdatedAt == todo.updatedAt },
                    onCancelReminder = { onCancelReminder(todo) },
                    onToggle = { onToggleTodo(todo, it) },
                    onDelete = { onDeleteTodo(todo) },
                )
            }
        }

        if (employmentEvents.isNotEmpty()) {
            Text("就业活动", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            employmentEvents.forEach { event ->
                InfoCard(
                    title = event.title,
                    subtitle = listOfNotNull(
                        employmentCalendarEventTypeLabel(event.type),
                        event.organization,
                    ).joinToString(" · ").ifBlank { null },
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        ModuleStatusPill(
                            text = employmentCalendarEventTypeLabel(event.type),
                            color = Color(0xFF0E7490),
                        )
                        event.statusLabel?.let {
                            ModuleStatusPill(text = it, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                        KeyValue("时间", event.employmentTimeLabel(), Modifier.weight(1f))
                        KeyValue("地点", event.location, Modifier.weight(1f))
                    }
                    event.organization?.let { KeyValue("单位", it) }
                    event.url.takeIf { it.isNotBlank() }?.let { url ->
                        OutlinedButton(
                            onClick = { onOpenEmploymentUrl(url) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("打开详情")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UserTodoRow(
    todo: UserTodoItem,
    reminder: TodoReminder?,
    onCancelReminder: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = todo.done, onCheckedChange = onToggle)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    todo.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (todo.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                todo.note?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                reminder?.let {
                    val time = Instant.ofEpochMilli(it.triggerAtMs).atZone(ZoneId.of("Asia/Shanghai"))
                        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                    val status = when (it.state) {
                        "scheduled" -> "已安排（系统可能延迟）"
                        "delivered" -> "已发送"
                        "blocked" -> "通知未获许可，请重新设置"
                        "expired" -> "已过期"
                        "pending", "delivering" -> "状态待核实，请重新设置"
                        else -> "安排失败，请重新设置"
                    }
                    Text("提醒 $time · $status", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onCancelReminder) { Text("取消提醒") }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除待办", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun UserTodoEditorDialog(
    initialDate: LocalDate,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSave: (UserTodoDraft) -> Unit,
) {
    var title by remember(initialDate) { mutableStateOf("") }
    var dateText by remember(initialDate) { mutableStateOf(initialDate.toString()) }
    var note by remember(initialDate) { mutableStateOf("") }
    var validationError by remember(initialDate) { mutableStateOf<String?>(null) }
    val visibleError = validationError ?: errorMessage

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增待办") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        validationError = null
                    },
                    label = { Text("标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = dateText,
                    onValueChange = {
                        dateText = it
                        validationError = null
                    },
                    label = { Text("日期 yyyy-MM-dd") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                visibleError?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsedDate = try {
                        LocalDate.parse(dateText.trim())
                    } catch (_: DateTimeParseException) {
                        null
                    }
                    when {
                        title.isBlank() -> validationError = "标题不能为空"
                        parsedDate == null -> validationError = "日期格式应为 yyyy-MM-dd"
                        else -> onSave(
                            UserTodoDraft(
                                title = title.trim(),
                                date = parsedDate.toString(),
                                note = note,
                            )
                        )
                    }
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun AcademicCalendarTable(
    calendar: AcademicCalendarTerm,
    currentWeek: Int?,
    today: LocalDate,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = calendar.label,
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val leftColumnWidth = 74.dp
                val minDayWidth = 42.dp
                val availableDayWidth = (maxWidth - leftColumnWidth) / 7
                val dayWidth = if (availableDayWidth > minDayWidth) availableDayWidth else minDayWidth
                val contentWidth = leftColumnWidth + dayWidth * 7f

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    Column(Modifier.width(contentWidth)) {
                        AcademicCalendarHeaderRow(
                            leftColumnWidth = leftColumnWidth,
                            dayWidth = dayWidth,
                        )
                        calendar.weeks.forEach { week ->
                            AcademicCalendarWeekRow(
                                week = week,
                                leftColumnWidth = leftColumnWidth,
                                dayWidth = dayWidth,
                                currentWeek = currentWeek,
                                today = today,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AcademicCalendarHeaderRow(leftColumnWidth: Dp, dayWidth: Dp) {
    val borderColor = MaterialTheme.colorScheme.surfaceVariant
    Row {
        AcademicCalendarCell(
            modifier = Modifier
                .width(leftColumnWidth)
                .height(42.dp),
            background = MaterialTheme.colorScheme.surfaceVariant,
            borderColor = borderColor,
        ) {
            Text("周", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
        listOf("一", "二", "三", "四", "五", "六", "日").forEach { weekday ->
            AcademicCalendarCell(
                modifier = Modifier
                    .width(dayWidth)
                    .height(42.dp),
                background = MaterialTheme.colorScheme.surfaceVariant,
                borderColor = borderColor,
            ) {
                Text("周$weekday", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun AcademicCalendarWeekRow(
    week: AcademicCalendarWeek,
    leftColumnWidth: Dp,
    dayWidth: Dp,
    currentWeek: Int?,
    today: LocalDate,
) {
    val isCurrentWeek = currentWeek == week.termWeekNumber
    val colorScheme = MaterialTheme.colorScheme
    val rowBackground = if (isCurrentWeek) {
        colorScheme.primaryContainer.copy(alpha = 0.32f)
    } else {
        colorScheme.surface
    }
    val borderColor = if (isCurrentWeek) colorScheme.primary else colorScheme.surfaceVariant

    Row(Modifier.height(IntrinsicSize.Min)) {
        AcademicCalendarCell(
            modifier = Modifier
                .width(leftColumnWidth)
                .fillMaxHeight()
                .heightIn(min = 54.dp),
            background = if (isCurrentWeek) colorScheme.primaryContainer else colorScheme.surfaceVariant.copy(alpha = 0.45f),
            borderColor = borderColor,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${week.seasonLabel}${week.seasonWeekNumber}周",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    text = week.monthLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        week.dates.forEachIndexed { index, date ->
            val isToday = date == today
            val isWeekend = index >= 5
            AcademicCalendarCell(
                modifier = Modifier
                    .width(dayWidth)
                    .fillMaxHeight()
                    .heightIn(min = 54.dp),
                background = if (isToday) colorScheme.primaryContainer else rowBackground,
                borderColor = borderColor,
                contentPadding = 2.dp,
            ) {
                val label = academicCalendarDateLabel(week, date)
                val isCompactLabel = '/' in label
                val dateTextStyle = if (isCompactLabel) {
                    MaterialTheme.typography.labelMedium
                } else {
                    MaterialTheme.typography.bodyMedium
                }
                if (isToday) {
                    Box(
                        modifier = Modifier
                            .background(colorScheme.primary, CircleShape)
                            .padding(
                                horizontal = if (isCompactLabel) 4.dp else 8.dp,
                                vertical = 4.dp,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            color = colorScheme.onPrimary,
                            style = dateTextStyle,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                } else {
                    Text(
                        text = label,
                        color = if (isWeekend) Color(0xFFC62828) else colorScheme.onSurface,
                        style = dateTextStyle,
                        fontWeight = if (isCurrentWeek) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun AcademicCalendarCell(
    modifier: Modifier,
    background: Color,
    borderColor: Color,
    contentAlignment: Alignment = Alignment.Center,
    contentPadding: Dp = 4.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .border(0.5.dp, borderColor)
            .background(background)
            .padding(contentPadding),
        contentAlignment = contentAlignment,
    ) {
        content()
    }
}

private fun academicCalendarDateLabel(week: AcademicCalendarWeek, date: LocalDate): String =
    if (week.dates.map { it.monthValue }.distinct().size > 1) {
        "${date.monthValue}/${date.dayOfMonth}"
    } else {
        date.dayOfMonth.toString()
    }
