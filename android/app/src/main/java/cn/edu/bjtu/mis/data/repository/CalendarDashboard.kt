package cn.edu.bjtu.mis.data.repository

import cn.edu.bjtu.mis.model.CalendarData
import cn.edu.bjtu.mis.model.ExamItem
import cn.edu.bjtu.mis.model.HomeworkItem
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.model.UserTodoItem

data class CalendarDashboard(
    val calendarEnvelope: ModuleEnvelope<CalendarData>,
    val homework: List<HomeworkItem>,
    val exams: List<ExamItem>,
    val todos: List<UserTodoItem>,
    val campusErrors: List<String> = emptyList(),
)
