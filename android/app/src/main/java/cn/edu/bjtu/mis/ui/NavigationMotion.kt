package cn.edu.bjtu.mis.ui

import cn.edu.bjtu.mis.model.ModuleKeys

enum class AppRouteTransitionDirection {
    Crossfade,
    Forward,
    Backward,
}

private val appMainRoutes = setOf(
    "overview",
    "services",
    ModuleKeys.OpenWebUiAgent,
    ModuleKeys.Profile,
)

fun appRouteTransitionDirection(
    fromRoute: String,
    toRoute: String,
): AppRouteTransitionDirection {
    if (fromRoute == toRoute || fromRoute in appMainRoutes && toRoute in appMainRoutes) {
        return AppRouteTransitionDirection.Crossfade
    }
    if (fromRoute !in appMainRoutes && toRoute in appMainRoutes) {
        return AppRouteTransitionDirection.Backward
    }
    if (fromRoute.startsWith("profile_") && toRoute == ModuleKeys.Profile) {
        return AppRouteTransitionDirection.Backward
    }
    if (fromRoute.startsWith("third_party_service/") && toRoute == "third_party_services") {
        return AppRouteTransitionDirection.Backward
    }
    if (fromRoute == "third_party_diagnostics" && toRoute == "third_party_services") {
        return AppRouteTransitionDirection.Backward
    }
    if (
        fromRoute == "homework_detail" && toRoute in setOf(
            ModuleKeys.Homework,
            ModuleKeys.Timetable,
            ModuleKeys.Calendar,
        )
    ) {
        return AppRouteTransitionDirection.Backward
    }
    return AppRouteTransitionDirection.Forward
}
