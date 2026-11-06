package cn.edu.bjtu.mis.ui.theme

enum class AppUiStyle(
    val storageValue: String,
    val displayName: String,
) {
    Classic("classic", "主题1"),
    Apple("apple", "主题2");

    companion object {
        fun fromStorageValue(value: String?): AppUiStyle =
            entries.firstOrNull { it.storageValue == value } ?: Classic
    }
}

enum class AppEffectOverride(
    val storageValue: String,
) {
    FollowSystem("follow_system"),
    Enabled("enabled"),
    Disabled("disabled");

    fun resolve(systemValue: Boolean): Boolean =
        when (this) {
            FollowSystem -> systemValue
            Enabled -> true
            Disabled -> false
        }

    companion object {
        fun fromStorageValue(value: String?): AppEffectOverride =
            entries.firstOrNull { it.storageValue == value } ?: FollowSystem
    }
}

data class AppAppearancePreferences(
    val theme: AppThemeOption = AppThemeOption.Default,
    val uiStyle: AppUiStyle = AppUiStyle.Classic,
    val reduceMotionOverride: AppEffectOverride = AppEffectOverride.FollowSystem,
    val reduceTransparencyOverride: AppEffectOverride = AppEffectOverride.FollowSystem,
)

data class EffectiveAppEffects(
    val reduceMotion: Boolean,
    val reduceTransparency: Boolean,
)

fun resolveEffectiveAppEffects(
    preferences: AppAppearancePreferences,
    systemReduceMotion: Boolean,
    systemReduceTransparency: Boolean,
    highContrastText: Boolean,
): EffectiveAppEffects = EffectiveAppEffects(
    reduceMotion = preferences.reduceMotionOverride.resolve(systemReduceMotion),
    reduceTransparency = preferences.reduceTransparencyOverride.resolve(
        systemReduceTransparency || highContrastText,
    ),
)

/**
 * A missing UI-style key is resolved only once and then persisted by [AppThemeStore].
 *
 * Existing installs have a last-update timestamp after the first-install timestamp.
 * Invalid or unavailable package metadata deliberately falls back to Classic.
 */
fun resolveInitialUiStyle(
    firstInstallTime: Long?,
    lastUpdateTime: Long?,
): AppUiStyle {
    if (firstInstallTime == null || lastUpdateTime == null) return AppUiStyle.Classic
    if (firstInstallTime <= 0L || lastUpdateTime <= 0L) return AppUiStyle.Classic
    return if (firstInstallTime == lastUpdateTime) AppUiStyle.Apple else AppUiStyle.Classic
}
