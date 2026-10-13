package cn.edu.bjtu.mis.ui.screens

import cn.edu.bjtu.mis.model.EmptyRoomData

internal data class EmptyRoomQuery(
    val term: String = "",
    val week: String = "",
    val building: String = "",
    val room: String = "",
) {
    fun resolve(data: EmptyRoomData): EmptyRoomQuery = copy(
        term = data.query["term"]?.takeIf { it.isNotBlank() } ?: term,
        week = data.query["week"]?.takeIf { it.isNotBlank() } ?: week,
        building = data.query["building"] ?: building,
        room = data.query["room"] ?: room,
    ).normalized()

    fun normalized(): EmptyRoomQuery = copy(
        term = term.trim(), week = week.trim(), building = building.trim(), room = room.trim(),
    )

    fun matches(other: EmptyRoomQuery): Boolean = normalized() == other.normalized()
}
