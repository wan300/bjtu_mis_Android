package cn.edu.bjtu.mis.model

import cn.edu.bjtu.mis.data.parser.parseEmptyRooms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyRoomAvailabilityTest {
    private val monday = EmptyRoomDay("星期一", "04月27日")
    private val tuesday = EmptyRoomDay("星期二", "04月28日")
    private val slots = listOf(
        EmptyRoomSlotHeader(monday.day, monday.date, 1),
        EmptyRoomSlotHeader(monday.day, monday.date, 2),
        EmptyRoomSlotHeader(monday.day, monday.date, 3),
        EmptyRoomSlotHeader(tuesday.day, tuesday.date, 1),
        EmptyRoomSlotHeader(tuesday.day, tuesday.date, 2),
    )

    @Test
    fun includesOnlyRoomsFreeForTheEntireInclusiveRange() {
        val data = EmptyRoomData(slots = slots, rooms = listOf(
            room("A101", "busy", "free", "free", "busy", "busy"),
            room("A102", "free", "busy", "free", "free", "free"),
            room("A103", "free", "free", "busy", "free", "free"),
        ))

        assertEquals(listOf("A101"), filterEmptyRooms(data, monday, 2, 3).map { it.room })
        assertEquals(listOf("A102", "A103"), filterEmptyRooms(data, tuesday, 1, 2).map { it.room })
        assertEquals(listOf("A101", "A103"), filterEmptyRooms(data, monday, 2, 2).map { it.room })
    }

    @Test
    fun excludesSpecialUnknownAndMissingCellsEvenIfAvailabilitySaysFree() {
        val data = EmptyRoomData(slots = slots, rooms = listOf(
            room("special", "free", "notice", "free"),
            room("unknown", "free", "unknown", "free"),
            room("missing", "free"),
            room("blank", "free", "", "free"),
            room("free", "free", "free", "free"),
        ).map { it.copy(availability = List(5) { true }) })

        assertEquals(listOf("free"), filterEmptyRooms(data, monday, 1, 3).map { it.room })
    }

    @Test
    fun supportsLegacyBooleanSnapshotsWithoutTreatingMissingValuesAsFree() {
        val data = EmptyRoomData(slots = slots, rooms = listOf(
            EmptyRoomRow("free", availability = listOf(true, true, true)),
            EmptyRoomRow("busy", availability = listOf(true, false, true)),
            EmptyRoomRow("missing", availability = listOf(true)),
            EmptyRoomRow("empty"),
        ))

        assertEquals(listOf("free"), filterEmptyRooms(data, monday, 1, 3).map { it.room })
    }

    @Test
    fun rejectsRangesWithMissingHeadersInvalidBoundsOrWrongDate() {
        val data = EmptyRoomData(slots = slots, rooms = listOf(room("A101", "free", "free", "free", "free", "free")))

        assertTrue(filterEmptyRooms(data, monday, 3, 2).isEmpty())
        assertTrue(filterEmptyRooms(data, monday, 0, 2).isEmpty())
        assertTrue(filterEmptyRooms(data, monday, 1, 4).isEmpty())
        assertTrue(filterEmptyRooms(data, monday, 1, Int.MAX_VALUE).isEmpty())
        assertTrue(filterEmptyRooms(data, monday.copy(date = "05月04日"), 1, 2).isEmpty())
        assertTrue(filterEmptyRooms(data.copy(slots = listOf(slots[0], slots[2])), monday, 1, 3).isEmpty())
        assertTrue(filterEmptyRooms(data.copy(slots = emptyList()), monday, 1, 1).isEmpty())
    }

    @Test
    fun duplicateHeadersDoNotHideMissingPeriodsOrBusyCells() {
        val data = EmptyRoomData(
            slots = listOf(slots[0], slots[0], slots[2]),
            rooms = listOf(room("A101", "free", "free", "free")),
        )
        assertTrue(filterEmptyRooms(data, monday, 1, 3).isEmpty())
        assertTrue(filterEmptyRooms(
            data.copy(rooms = listOf(room("A101", "free", "busy", "free"))), monday, 1, 1,
        ).isEmpty())
    }

    @Test
    fun derivesDaysAndSortedPeriodsFromRealHeadersOnly() {
        val data = EmptyRoomData(slots = listOf(
            slots[2], slots[0], slots[0], slots[3],
            EmptyRoomSlotHeader("", null, 4),
            EmptyRoomSlotHeader("星期三", null, 0),
        ))
        assertEquals(listOf(monday, tuesday), emptyRoomDays(data))
        assertEquals(listOf(1, 3), emptyRoomPeriods(data, monday))
        assertEquals(listOf(1), emptyRoomPeriods(data, tuesday))
    }

    @Test
    fun filtersParsedFixtureAndPreservesRoomDetails() {
        val html = checkNotNull(javaClass.classLoader?.getResource("fixtures/empty_rooms.html")).readText()
        val data = parseEmptyRooms(html)
        val rooms = filterEmptyRooms(data, monday, 1, 2)

        assertEquals(listOf("SX101"), rooms.map { it.room })
        assertEquals("204", rooms.single().seatLabel)
        assertEquals(listOf("SX101", "SX102"), filterEmptyRooms(data, tuesday, 1, 2).map { it.room })
    }

    private fun room(name: String, vararg states: String) = EmptyRoomRow(name, cellStates = states.toList())
}
