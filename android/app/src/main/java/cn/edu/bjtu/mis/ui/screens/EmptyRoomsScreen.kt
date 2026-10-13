package cn.edu.bjtu.mis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.edu.bjtu.mis.data.repository.ModuleLoadStrategy
import cn.edu.bjtu.mis.data.repository.ModuleRepository
import cn.edu.bjtu.mis.model.EmptyRoomData
import cn.edu.bjtu.mis.model.EmptyRoomRow
import cn.edu.bjtu.mis.model.EmptyRoomSlotHeader
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.model.TermOption
import cn.edu.bjtu.mis.ui.components.InfoCard
import cn.edu.bjtu.mis.ui.components.LoadState
import cn.edu.bjtu.mis.ui.components.LoadingOrError
import cn.edu.bjtu.mis.ui.friendlyTimestamp
import kotlinx.coroutines.CancellationException

@Composable
fun EmptyRoomsScreen(
    repository: ModuleRepository,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(EmptyRoomQuery()) }
    var loadedQuery by remember { mutableStateOf<EmptyRoomQuery?>(null) }
    var queryData by remember { mutableStateOf<EmptyRoomData?>(null) }
    var showAvailableOnly by remember { mutableStateOf(true) }
    var timeSelection by remember { mutableStateOf(EmptyRoomTimeSelection()) }
    var page by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<LoadState<ModuleEnvelope<EmptyRoomData>>>(LoadState.Loading) }

    val loader = remember(repository, scope) {
        LatestRequestLoader<EmptyRoomQueryResult>(scope) { result ->
            result.onSuccess { response ->
                page = 0
                val resolved = response.requestedQuery.resolve(response.envelope.data)
                // A user may have edited a text field while this request was running.
                if (query.matches(response.requestedQuery)) query = resolved
                loadedQuery = resolved
                queryData = response.envelope.data
                state = LoadState.Data(response.envelope)
            }.onFailure { state = LoadState.Error(it.message ?: "加载失败") }
        }
    }

    fun load(
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
        resolveDefaultWeek: Boolean = false,
    ) {
        val requested = query.normalized()
        val requestedWeek = requested.week.ifBlank { null }
        state = LoadState.Loading
        loader.load {
            val targetWeek = if (resolveDefaultWeek && requestedWeek == null) {
                try {
                    repository.calendar(strategy = strategy).data.currentWeek
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    null
                }
            } else requestedWeek
            val envelope = repository.emptyRooms(
                term = requested.term.ifBlank { null },
                week = targetWeek?.ifBlank { null },
                // Initial loading may use the server/cache defaults; subsequent queries
                // must distinguish explicitly clearing a filter from not specifying one.
                building = requested.building.takeUnless { resolveDefaultWeek && it.isBlank() },
                room = requested.room.takeUnless { resolveDefaultWeek && it.isBlank() },
                strategy = strategy,
            )
            EmptyRoomQueryResult(requested, envelope)
        }
    }

    LaunchedEffect(Unit) {
        load(initialLoadStrategy, resolveDefaultWeek = true)
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EmptyRoomQueryField(
                    value = query.term,
                    onValueChange = {
                        query = query.copy(term = it)
                        if (queryData?.availableTerms?.isNotEmpty() == true) load()
                    },
                    label = "学期",
                    placeholder = "可留空",
                    options = queryData?.availableTerms.orEmpty(),
                )
                EmptyRoomQueryField(
                    value = query.week,
                    onValueChange = {
                        query = query.copy(week = it)
                        if (queryData?.availableWeeks?.isNotEmpty() == true) load()
                    },
                    label = "周次",
                    options = queryData?.availableWeeks.orEmpty(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                EmptyRoomQueryField(
                    value = query.building,
                    onValueChange = {
                        query = query.copy(building = it)
                        if (queryData?.availableBuildings?.isNotEmpty() == true) load()
                    },
                    label = "教学楼",
                    options = queryData?.availableBuildings.orEmpty(),
                )
                OutlinedTextField(
                    value = query.room,
                    onValueChange = { query = query.copy(room = it) },
                    label = { Text("教室（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { load() }, enabled = state != LoadState.Loading) {
                    Text("查询 / 刷新空教室")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = showAvailableOnly, onClick = { showAvailableOnly = true }, label = { Text("按时段找空教室") })
                    FilterChip(selected = !showAvailableOnly, onClick = { showAvailableOnly = false }, label = { Text("整周视图") })
                }
            }
        }
        when (val current = state) {
            LoadState.Loading, is LoadState.Error -> item { LoadingOrError(current) }
            is LoadState.Data -> {
                val envelope = current.value
                if (loadedQuery?.let(query::matches) != true) {
                    item {
                        InfoCard("筛选条件已更改") {
                            Text("请点击查询，查看新条件对应的空教室。")
                        }
                    }
                } else {
                    item {
                        Text(
                            text = "数据更新：${friendlyTimestamp(envelope.syncedAt)} · 以教务占用记录为准",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item {
                        if (showAvailableOnly) {
                            EmptyRoomAvailabilityResults(
                                data = envelope.data,
                                selection = timeSelection,
                                onSelectionChange = { timeSelection = it },
                            )
                        } else {
                            EmptyRoomsMatrix(
                                data = envelope.data,
                                page = page,
                                onPageChange = { page = it },
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class EmptyRoomQueryResult(
    val requestedQuery: EmptyRoomQuery,
    val envelope: ModuleEnvelope<EmptyRoomData>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EmptyRoomQueryField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    options: List<TermOption>,
    placeholder: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            singleLine = true,
            keyboardOptions = keyboardOptions,
            modifier = modifier.fillMaxWidth(),
        )
        return
    }

    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.value == value }?.let(::emptyRoomOptionLabel)
        ?: value
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(emptyRoomOptionLabel(option)) },
                    onClick = {
                        expanded = false
                        onValueChange(option.value)
                    },
                )
            }
        }
    }
}

private fun emptyRoomOptionLabel(option: TermOption): String =
    option.label.ifBlank { option.value.ifBlank { "不限" } }

internal const val EmptyRoomsPageSize = 20
private const val EmptyRoomStateFree = "free"
private const val EmptyRoomStateBusy = "busy"
private const val EmptyRoomStateNotice = "notice"

@Composable
private fun EmptyRoomsMatrix(
    data: EmptyRoomData,
    page: Int,
    onPageChange: (Int) -> Unit,
) {
    val rooms = data.rooms
    val slotColumns = remember(data.slots, rooms) { emptyRoomSlotColumns(data) }
    if (rooms.isEmpty() || slotColumns.isEmpty()) {
        InfoCard("暂无空教室矩阵") {
            Text("当前查询没有返回可显示的空教室数据。", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    val pageCount = ((rooms.size + EmptyRoomsPageSize - 1) / EmptyRoomsPageSize).coerceAtLeast(1)
    val currentPage = page.coerceIn(0, pageCount - 1)
    LaunchedEffect(page, pageCount) {
        if (page != currentPage) onPageChange(currentPage)
    }

    val pageRooms = remember(rooms, currentPage) {
        rooms.drop(currentPage * EmptyRoomsPageSize).take(EmptyRoomsPageSize)
    }
    val dayGroups = remember(slotColumns) { emptyRoomDayGroups(slotColumns) }
    val horizontalScrollState = rememberScrollState()
    val verticalScrollState = rememberScrollState()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EmptyRoomLegend()
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 1.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val leftColumnWidth = 92.dp
                val minSlotWidth = 38.dp
                val availableSlotWidth = (maxWidth - leftColumnWidth) / slotColumns.size
                val slotWidth = if (availableSlotWidth > minSlotWidth) availableSlotWidth else minSlotWidth
                val dayHeaderHeight = 48.dp
                val periodHeaderHeight = 40.dp
                val roomRowHeight = 56.dp

                Column(Modifier.fillMaxWidth()) {
                    Row {
                        EmptyRoomCornerHeader(
                            width = leftColumnWidth,
                            dayHeaderHeight = dayHeaderHeight,
                            periodHeaderHeight = periodHeaderHeight,
                        )
                        Column(Modifier.horizontalScroll(horizontalScrollState)) {
                            EmptyRoomDayHeaderRow(
                                groups = dayGroups,
                                slotWidth = slotWidth,
                                height = dayHeaderHeight,
                            )
                            EmptyRoomPeriodHeaderRow(
                                slots = slotColumns,
                                slotWidth = slotWidth,
                                height = periodHeaderHeight,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .heightIn(max = 560.dp)
                            .verticalScroll(verticalScrollState),
                    ) {
                        Column(Modifier.width(leftColumnWidth)) {
                            pageRooms.forEach { row ->
                                EmptyRoomRoomCell(row = row, height = roomRowHeight)
                            }
                        }
                        Column(Modifier.horizontalScroll(horizontalScrollState)) {
                            pageRooms.forEach { row ->
                                EmptyRoomAvailabilityRow(
                                    row = row,
                                    slotCount = slotColumns.size,
                                    slotWidth = slotWidth,
                                    height = roomRowHeight,
                                )
                            }
                        }
                    }
                }
            }
        }
        EmptyRoomPager(
            currentPage = currentPage,
            pageCount = pageCount,
            totalRooms = rooms.size,
            onPageChange = onPageChange,
        )
    }
}

@Composable
private fun EmptyRoomLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EmptyRoomLegendItem(state = EmptyRoomStateFree, label = "空闲")
        EmptyRoomLegendItem(state = EmptyRoomStateBusy, label = "占用")
        EmptyRoomLegendItem(state = EmptyRoomStateNotice, label = "特殊")
    }
}

@Composable
private fun EmptyRoomLegendItem(state: String, label: String) {
    val colors = emptyRoomCellColors(state)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(18.dp)
                .height(18.dp)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                .background(colors.first),
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyRoomCornerHeader(
    width: Dp,
    dayHeaderHeight: Dp,
    periodHeaderHeight: Dp,
) {
    Column(Modifier.width(width)) {
        EmptyRoomHeaderCell(
            text = "星期",
            modifier = Modifier
                .width(width)
                .height(dayHeaderHeight),
            fontWeight = FontWeight.Bold,
        )
        EmptyRoomHeaderCell(
            text = "教室/\n节次",
            modifier = Modifier
                .width(width)
                .height(periodHeaderHeight),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun EmptyRoomDayHeaderRow(
    groups: List<EmptyRoomDayGroup>,
    slotWidth: Dp,
    height: Dp,
) {
    Row(Modifier.height(height)) {
        groups.forEach { group ->
            EmptyRoomHeaderCell(
                text = group.label,
                modifier = Modifier.width(slotWidth * group.span.toFloat()),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun EmptyRoomPeriodHeaderRow(
    slots: List<EmptyRoomSlotHeader>,
    slotWidth: Dp,
    height: Dp,
) {
    Row(Modifier.height(height)) {
        slots.forEach { slot ->
            EmptyRoomHeaderCell(
                text = slot.period.toString(),
                modifier = Modifier.width(slotWidth),
            )
        }
    }
}

@Composable
private fun EmptyRoomHeaderCell(
    text: String,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Normal,
) {
    Box(
        modifier = modifier
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = fontWeight,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptyRoomRoomCell(row: EmptyRoomRow, height: Dp) {
    Column(
        modifier = Modifier
            .width(92.dp)
            .height(height)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = row.room,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!row.seatLabel.isNullOrBlank()) {
            Text(
                text = "(${row.seatLabel})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyRoomAvailabilityRow(
    row: EmptyRoomRow,
    slotCount: Int,
    slotWidth: Dp,
    height: Dp,
) {
    Row(Modifier.height(height)) {
        repeat(slotCount) { index ->
            val state = emptyRoomCellState(row, index)
            val colors = emptyRoomCellColors(state)
            Box(
                modifier = Modifier
                    .width(slotWidth)
                    .fillMaxHeight()
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                    .background(colors.first),
                contentAlignment = Alignment.Center,
            ) {
                if (state == EmptyRoomStateNotice) {
                    Text(
                        text = "!",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.second,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
internal fun EmptyRoomPager(
    currentPage: Int,
    pageCount: Int,
    totalRooms: Int,
    onPageChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = { onPageChange(currentPage - 1) },
            enabled = currentPage > 0,
        ) {
            Text("上一页")
        }
        Text(
            text = "第 ${currentPage + 1} / $pageCount 页 · 共 $totalRooms 间",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = { onPageChange(currentPage + 1) },
            enabled = currentPage < pageCount - 1,
        ) {
            Text("下一页")
        }
    }
}

@Composable
private fun emptyRoomCellColors(state: String): Pair<Color, Color> {
    val colorScheme = MaterialTheme.colorScheme
    return when (state) {
        EmptyRoomStateBusy -> Color(0xFFE56666) to Color.White
        EmptyRoomStateNotice -> Color(0xFFD8CF4D) to Color(0xFF2F2B12)
        else -> Color.White to colorScheme.onSurface
    }
}

private data class EmptyRoomDayGroup(
    val label: String,
    val span: Int,
)

private fun emptyRoomSlotColumns(data: EmptyRoomData): List<EmptyRoomSlotHeader> {
    val maxRoomCells = data.rooms.maxOfOrNull { maxOf(it.cellStates.size, it.availability.size) } ?: 0
    if (data.slots.size >= maxRoomCells) return data.slots
    return data.slots + (data.slots.size until maxRoomCells).map { index ->
        EmptyRoomSlotHeader(day = "", date = null, period = index + 1)
    }
}

private fun emptyRoomDayGroups(slots: List<EmptyRoomSlotHeader>): List<EmptyRoomDayGroup> {
    if (slots.isEmpty()) return emptyList()
    val groups = mutableListOf<EmptyRoomDayGroup>()
    var currentLabel = emptyRoomSlotDayLabel(slots.first())
    var currentSpan = 0
    slots.forEach { slot ->
        val label = emptyRoomSlotDayLabel(slot)
        if (label != currentLabel && currentSpan > 0) {
            groups += EmptyRoomDayGroup(label = currentLabel, span = currentSpan)
            currentLabel = label
            currentSpan = 0
        }
        currentSpan += 1
    }
    groups += EmptyRoomDayGroup(label = currentLabel, span = currentSpan)
    return groups
}

private fun emptyRoomSlotDayLabel(slot: EmptyRoomSlotHeader): String =
    listOf(slot.day, slot.date)
        .filter { !it.isNullOrBlank() }
        .joinToString(" ")
        .ifBlank { "日期" }

private fun emptyRoomCellState(row: EmptyRoomRow, index: Int): String =
    row.cellStates.getOrNull(index)
        ?: row.availability.getOrNull(index)?.let { if (it) EmptyRoomStateFree else EmptyRoomStateBusy }
        ?: EmptyRoomStateFree
