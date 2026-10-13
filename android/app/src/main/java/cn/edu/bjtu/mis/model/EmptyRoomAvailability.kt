package cn.edu.bjtu.mis.model

data class EmptyRoomDay(val day: String, val date: String?) {
    val label: String
        get() = listOfNotNull(day.takeIf { it.isNotBlank() }, date?.takeIf { it.isNotBlank() })
            .joinToString(" ")
}

fun emptyRoomDays(data: EmptyRoomData): List<EmptyRoomDay> = data.slots
    .filter { it.period > 0 && (it.day.isNotBlank() || !it.date.isNullOrBlank()) }
    .map { EmptyRoomDay(it.day, it.date) }
    .distinct()

fun emptyRoomPeriods(data: EmptyRoomData, day: EmptyRoomDay): List<Int> = data.slots
    .filter { it.day == day.day && it.date == day.date && it.period > 0 }
    .map { it.period }
    .distinct()
    .sorted()

/** Only explicit availability for every period in the inclusive range qualifies. */
fun filterEmptyRooms(
    data: EmptyRoomData,
    day: EmptyRoomDay,
    startPeriod: Int,
    endPeriod: Int,
): List<EmptyRoomRow> {
    if (startPeriod < 1 || endPeriod < startPeriod || day.label.isBlank()) return emptyList()
    val selectedSlots = data.slots.withIndex().filter { (_, slot) ->
        slot.day == day.day && slot.date == day.date && slot.period in startPeriod..endPeriod
    }
    // Missing headers must not turn a partly known interval into a free interval.
    val periodCount = selectedSlots.map { it.value.period }.distinct().size
    if (periodCount.toLong() != endPeriod.toLong() - startPeriod + 1) return emptyList()
    return data.rooms.filter { room ->
        selectedSlots.all { (index, _) ->
            if (room.cellStates.isNotEmpty()) {
                room.cellStates.getOrNull(index) == "free"
            } else {
                // Older snapshots only have booleans; absent cells are still unknown.
                room.availability.getOrNull(index) == true
            }
        }
    }
}
