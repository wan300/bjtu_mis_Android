package cn.edu.bjtu.mis.ui.screens

import cn.edu.bjtu.mis.model.EmptyRoomData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyRoomQueryTest {
    @Test
    fun resolvesServerDefaultsWithoutRestoringClearedBuildingOrRoom() {
        val query = EmptyRoomQuery(building = "SX", room = "101")
        val resolved = query.resolve(EmptyRoomData(query = mapOf(
            "term" to "2026-2027-1", "week" to "8", "building" to "", "room" to "",
        )))

        assertEquals(EmptyRoomQuery("2026-2027-1", "8", "", ""), resolved)
    }

    @Test
    fun retainsRequestedValuesWhenOldSnapshotHasNoQueryMetadata() {
        val query = EmptyRoomQuery("2026-2027-1", "8", "SX", "101")
        assertEquals(query, query.resolve(EmptyRoomData()))
    }

    @Test
    fun changedFiltersCannotDisplayPreviousResults() {
        val loaded = EmptyRoomQuery("2026-2027-1", "8", "SX", "101")
        assertFalse(loaded.matches(loaded.copy(building = "SY")))
        assertFalse(loaded.matches(loaded.copy(week = "9")))
        assertFalse(loaded.matches(loaded.copy(term = "2026-2027-2")))
        assertFalse(loaded.matches(loaded.copy(room = "")))
        assertTrue(loaded.matches(loaded.copy(room = " 101 ")))
    }
}
