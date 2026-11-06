package cn.edu.bjtu.mis.ui

import cn.edu.bjtu.mis.ui.theme.AppAppearancePreferences
import cn.edu.bjtu.mis.ui.theme.AppUiStyle

enum class UiStyleChangeOutcome {
    Applied,
    Undone,
}

suspend fun applyUiStyleChangeWithUndo(
    previousAppearance: AppAppearancePreferences,
    nextStyle: AppUiStyle,
    onPreview: (AppAppearancePreferences) -> Unit,
    persist: suspend (AppUiStyle) -> Unit,
    showUndo: suspend (message: String) -> Boolean,
): UiStyleChangeOutcome {
    val nextAppearance = previousAppearance.copy(uiStyle = nextStyle)
    onPreview(nextAppearance)
    persist(nextStyle)
    val undone = showUndo(
        "已切换到${nextStyle.displayName}",
    )
    if (!undone) return UiStyleChangeOutcome.Applied

    onPreview(previousAppearance)
    persist(previousAppearance.uiStyle)
    return UiStyleChangeOutcome.Undone
}
