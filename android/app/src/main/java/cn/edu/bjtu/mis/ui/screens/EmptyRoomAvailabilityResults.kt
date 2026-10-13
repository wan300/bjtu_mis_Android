package cn.edu.bjtu.mis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.edu.bjtu.mis.model.EmptyRoomData
import cn.edu.bjtu.mis.model.EmptyRoomDay
import cn.edu.bjtu.mis.model.TermOption
import cn.edu.bjtu.mis.model.emptyRoomDays
import cn.edu.bjtu.mis.model.emptyRoomPeriods
import cn.edu.bjtu.mis.model.filterEmptyRooms
import cn.edu.bjtu.mis.ui.components.InfoCard

internal data class EmptyRoomTimeSelection(
    val day: EmptyRoomDay? = null,
    val startPeriod: Int? = null,
    val endPeriod: Int? = null,
)

@Composable
internal fun EmptyRoomAvailabilityResults(
    data: EmptyRoomData,
    selection: EmptyRoomTimeSelection,
    onSelectionChange: (EmptyRoomTimeSelection) -> Unit,
) {
    val days = remember(data.slots) { emptyRoomDays(data) }
    val day = selection.day?.takeIf { it in days }
        ?: days.firstOrNull { it.day == selection.day?.day }
        ?: days.firstOrNull()
    if (day == null) {
        InfoCard("暂无可筛选的时间段") {
            Text("当前数据缺少日期或节次，请刷新重试，或切换到整周视图查看。")
        }
        return
    }

    val periods = remember(data.slots, day) { emptyRoomPeriods(data, day) }
    val start = selection.startPeriod?.takeIf { it in periods } ?: periods.first()
    val end = selection.endPeriod?.takeIf { it in periods && it >= start } ?: start
    val resolvedSelection = EmptyRoomTimeSelection(day, start, end)
    val rooms = remember(data, day, start, end) { filterEmptyRooms(data, day, start, end) }
    var page by remember(data, day, start, end) { mutableStateOf(0) }
    val pageCount = ((rooms.size + EmptyRoomsPageSize - 1) / EmptyRoomsPageSize).coerceAtLeast(1)
    val rangeLabel = if (start == end) "第 $start 节" else "第 $start–$end 节"

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EmptyRoomQueryField(
            value = days.indexOf(day).toString(),
            onValueChange = { onSelectionChange(resolvedSelection.copy(day = days[it.toInt()])) },
            label = "星期 / 日期",
            options = days.mapIndexed { index, option -> TermOption(index.toString(), option.label) },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EmptyRoomQueryField(
                value = start.toString(),
                onValueChange = {
                    val next = it.toInt()
                    // Keep the displayed end valid when advancing the start past it.
                    onSelectionChange(resolvedSelection.copy(startPeriod = next, endPeriod = maxOf(next, end)))
                },
                label = "开始节次",
                options = periods.map { TermOption(it.toString(), "第 $it 节") },
                modifier = Modifier.weight(1f),
            )
            EmptyRoomQueryField(
                value = end.toString(),
                onValueChange = { onSelectionChange(resolvedSelection.copy(endPeriod = it.toInt())) },
                label = "结束节次",
                options = periods.filter { it >= start }.map { TermOption(it.toString(), "第 $it 节") },
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = "${day.label} · $rangeLabel · ${rooms.size} 间空教室",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = "仅显示所选时段每一节均为空闲的教室；特殊或缺失状态不计入。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (rooms.isEmpty()) {
            InfoCard("该时段暂无符合条件的空教室") {
                Text("可以更换教学楼、日期或缩短节次范围后重试。")
            }
        } else {
            rooms.drop(page * EmptyRoomsPageSize).take(EmptyRoomsPageSize).forEach { room ->
                InfoCard(title = room.room, subtitle = "$rangeLabel 全程空闲") {
                    Text(room.seatLabel?.takeIf { it.isNotBlank() }?.let { "座位数：$it" } ?: "座位信息未提供")
                }
            }
            if (pageCount > 1) {
                EmptyRoomPager(
                    currentPage = page,
                    pageCount = pageCount,
                    totalRooms = rooms.size,
                    onPageChange = { page = it.coerceIn(0, pageCount - 1) },
                )
            }
        }
    }
}
