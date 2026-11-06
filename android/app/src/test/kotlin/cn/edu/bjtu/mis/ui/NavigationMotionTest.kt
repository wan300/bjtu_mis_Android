package cn.edu.bjtu.mis.ui

import cn.edu.bjtu.mis.model.ModuleKeys
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationMotionTest {
    @Test
    fun `main destinations crossfade`() {
        assertEquals(
            AppRouteTransitionDirection.Crossfade,
            appRouteTransitionDirection("overview", "services"),
        )
    }

    @Test
    fun `opening a detail moves forward`() {
        assertEquals(
            AppRouteTransitionDirection.Forward,
            appRouteTransitionDirection("services", ModuleKeys.Timetable),
        )
    }

    @Test
    fun `returning from a detail moves backward`() {
        assertEquals(
            AppRouteTransitionDirection.Backward,
            appRouteTransitionDirection(ModuleKeys.Timetable, "services"),
        )
        assertEquals(
            AppRouteTransitionDirection.Backward,
            appRouteTransitionDirection("profile_theme", ModuleKeys.Profile),
        )
    }

    @Test
    fun `homework detail returns backward to each originating module`() {
        listOf(ModuleKeys.Homework, ModuleKeys.Timetable, ModuleKeys.Calendar).forEach { route ->
            assertEquals(
                AppRouteTransitionDirection.Forward,
                appRouteTransitionDirection(route, "homework_detail"),
            )
            assertEquals(
                AppRouteTransitionDirection.Backward,
                appRouteTransitionDirection("homework_detail", route),
            )
        }
    }

    @Test
    fun `plugin diagnostics returns backward to the service catalog`() {
        assertEquals(
            AppRouteTransitionDirection.Backward,
            appRouteTransitionDirection("third_party_diagnostics", "third_party_services"),
        )
    }
}
