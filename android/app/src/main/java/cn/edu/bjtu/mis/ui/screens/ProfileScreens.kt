package cn.edu.bjtu.mis.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Grade
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cn.edu.bjtu.mis.data.repository.ModuleLoadStrategy
import cn.edu.bjtu.mis.data.repository.ModuleRepository
import cn.edu.bjtu.mis.data.update.AppUpdateCheckResult
import cn.edu.bjtu.mis.data.update.AppUpdateChecker
import cn.edu.bjtu.mis.data.update.AppUpdateInfo
import cn.edu.bjtu.mis.data.update.AppUpdatePreferenceStore
import cn.edu.bjtu.mis.data.update.AppUpdatePromptPreference
import cn.edu.bjtu.mis.data.update.installedVersionName
import cn.edu.bjtu.mis.model.ModuleKeys
import cn.edu.bjtu.mis.model.ProfileField
import cn.edu.bjtu.mis.model.ProfileSection
import cn.edu.bjtu.mis.model.StudentProfileData
import cn.edu.bjtu.mis.ui.components.InfoCard
import cn.edu.bjtu.mis.ui.components.KeyValue
import cn.edu.bjtu.mis.ui.components.AppUpdateAvailableDialog
import cn.edu.bjtu.mis.ui.components.AppUpdateDialogPreference
import cn.edu.bjtu.mis.ui.theme.AppAppearancePreferences
import cn.edu.bjtu.mis.ui.theme.AppEffectOverride
import cn.edu.bjtu.mis.ui.theme.AppThemeOption
import cn.edu.bjtu.mis.ui.theme.AppUiStyle
import cn.edu.bjtu.mis.ui.theme.LocalAppEffects
import cn.edu.bjtu.mis.ui.theme.LocalAppUiStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(
    repository: ModuleRepository,
    appUpdateChecker: AppUpdateChecker,
    appUpdatePreferenceStore: AppUpdatePreferenceStore,
    selectedTheme: AppThemeOption = AppThemeOption.Default,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    onLogout: () -> Unit,
    onOpenPersonalInfo: () -> Unit,
    onOpenTrainingInfo: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenHomeworkReminder: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val updatePreference by appUpdatePreferenceStore.preference.collectAsState(initial = AppUpdatePromptPreference())
    val installedVersion = remember(context) { context.installedVersionName()?.takeIf { it.isNotBlank() } ?: "未知" }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var updateCheckDialog by remember { mutableStateOf<ProfileUpdateCheckDialog?>(null) }

    fun applyUpdateDialogPreference(
        update: AppUpdateInfo,
        preference: AppUpdateDialogPreference,
        afterApply: () -> Unit = {},
    ) {
        scope.launch {
            runCatching {
                when {
                    preference.disableAutoPrompts -> appUpdatePreferenceStore.disableAutoPrompts()
                    preference.ignoreThisVersion -> appUpdatePreferenceStore.ignoreVersion(update.latestVersion)
                }
            }
            afterApply()
        }
    }

    fun restoreAutoPrompts() {
        scope.launch { appUpdatePreferenceStore.enableAutoPrompts() }
    }

    fun startUpdateCheck() {
        updateCheckDialog = ProfileUpdateCheckDialog.Checking
        scope.launch {
            updateCheckDialog = try {
                ProfileUpdateCheckDialog.Result(appUpdateChecker.checkForUpdateResult())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ProfileUpdateCheckDialog.Result(AppUpdateCheckResult.Unavailable())
            }
        }
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("退出登录") },
            text = { Text("确定要退出当前校园账号吗？退出后需要重新登录。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutConfirm = false
                        onLogout()
                    },
                ) {
                    Text("退出", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text("取消")
                }
            },
        )
    }

    when (val dialog = updateCheckDialog) {
        null -> Unit
        ProfileUpdateCheckDialog.Checking -> AlertDialog(
            onDismissRequest = {},
            title = { Text("检测更新") },
            text = { Text("正在检测最新版本...") },
            confirmButton = {},
        )
        is ProfileUpdateCheckDialog.Result -> ProfileUpdateResultDialog(
            result = dialog.result,
            autoPromptDisabled = updatePreference.autoPromptDisabled,
            onDismiss = { updateCheckDialog = null },
            onRestoreAutoPrompts = {
                restoreAutoPrompts()
            },
            onApplyUpdatePreference = { update, preference, afterApply ->
                updateCheckDialog = null
                applyUpdateDialogPreference(update, preference, afterApply)
            },
            onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
        )
    }

    DataScreen(
        title = "我的",
        initialLoadStrategy = initialLoadStrategy,
        loader = { strategy -> repository.profile(strategy) },
    ) { envelope ->
        val profile = envelope.data
        item { ProfileHeaderCard(profile) }
        item {
            ProfileSettingsGroup(
                items = listOf(
                    ProfileSettingsItem(
                        title = "人员信息",
                        subtitle = "姓名、学号与身份资料",
                        iconLabel = "人",
                        iconColor = Color(0xFF58C7B4),
                        icon = Icons.Filled.Person,
                        onClick = onOpenPersonalInfo,
                    ),
                    ProfileSettingsItem(
                        title = "培养信息",
                        subtitle = "学院、专业与学籍资料",
                        iconLabel = "培",
                        iconColor = Color(0xFF4D8EF7),
                        icon = Icons.Filled.School,
                        onClick = onOpenTrainingInfo,
                    ),
                    ProfileSettingsItem(
                        title = "外观与显示",
                        subtitle = "界面、配色与插件显示",
                        iconLabel = "题",
                        iconColor = Color(0xFFE4B96A),
                        icon = Icons.Filled.Palette,
                        trailingText = themeOptionLabel(selectedTheme),
                        onClick = onOpenTheme,
                    ),
                    ProfileSettingsItem(
                        title = "作业提醒",
                        subtitle = "设置普通和紧急提醒阈值",
                        iconLabel = "醒",
                        iconColor = Color(0xFF7C58C2),
                        icon = Icons.Filled.Notifications,
                        onClick = onOpenHomeworkReminder,
                    ),
                    ProfileSettingsItem(
                        title = "检测更新",
                        subtitle = if (updatePreference.autoPromptDisabled) {
                            "自动提示已关闭，仍可手动检测"
                        } else {
                            "当前版本：$installedVersion"
                        },
                        iconLabel = "更",
                        iconColor = Color(0xFF39A86B),
                        icon = Icons.Filled.SystemUpdate,
                        trailingText = if (updateCheckDialog == ProfileUpdateCheckDialog.Checking) "检测中" else null,
                        onClick = { startUpdateCheck() },
                    ),
                ),
            )
        }
        item {
            ProfileSettingsGroup(
                items = listOf(
                    ProfileSettingsItem(
                        title = "学业进度",
                        subtitle = "查看培养完成情况",
                        iconLabel = "进",
                        iconColor = Color(0xFF6E62D6),
                        icon = Icons.AutoMirrored.Filled.TrendingUp,
                        onClick = { onNavigate(ModuleKeys.AcademicProgress) },
                    ),
                    ProfileSettingsItem(
                        title = "主修成绩",
                        subtitle = "查看本学期成绩",
                        iconLabel = "绩",
                        iconColor = Color(0xFFE46B2D),
                        icon = Icons.Filled.Grade,
                        onClick = { onNavigate(ModuleKeys.Scores) },
                    ),
                    ProfileSettingsItem(
                        title = "查看课表",
                        subtitle = "进入本周课程",
                        iconLabel = "课",
                        iconColor = Color(0xFF18B7D8),
                        icon = Icons.Filled.CalendarMonth,
                        onClick = { onNavigate(ModuleKeys.Timetable) },
                    ),
                ),
            )
        }
        item {
            ProfileSettingsGroup(
                items = listOf(
                    ProfileSettingsItem(
                        title = "退出登录",
                        subtitle = "退出当前校园账号",
                        iconLabel = "退",
                        iconColor = Color(0xFFD95B5B),
                        icon = Icons.AutoMirrored.Filled.Logout,
                        destructive = true,
                        showChevron = false,
                        onClick = { showLogoutConfirm = true },
                    ),
                ),
            )
        }
    }
}

private sealed interface ProfileUpdateCheckDialog {
    data object Checking : ProfileUpdateCheckDialog
    data class Result(val result: AppUpdateCheckResult) : ProfileUpdateCheckDialog
}

@Composable
private fun ProfileUpdateResultDialog(
    result: AppUpdateCheckResult,
    autoPromptDisabled: Boolean,
    onDismiss: () -> Unit,
    onRestoreAutoPrompts: () -> Unit,
    onApplyUpdatePreference: (AppUpdateInfo, AppUpdateDialogPreference, () -> Unit) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    when (result) {
        is AppUpdateCheckResult.UpdateAvailable -> AppUpdateAvailableDialog(
            update = result.update,
            showAutoPromptRestore = autoPromptDisabled,
            onRestoreAutoPrompts = onRestoreAutoPrompts,
            onDismiss = { preference ->
                onApplyUpdatePreference(result.update, preference) {}
            },
            onOpenUpdate = { preference ->
                onApplyUpdatePreference(result.update, preference) {
                    onOpenUrl(result.update.releaseUrl)
                }
            },
        )
        is AppUpdateCheckResult.UpToDate -> AppUpdateStatusDialog(
            title = "已是最新版本",
            message = "当前版本：${result.currentVersion}",
            autoPromptDisabled = autoPromptDisabled,
            onDismiss = onDismiss,
            onRestoreAutoPrompts = onRestoreAutoPrompts,
        )
        is AppUpdateCheckResult.Unavailable -> AppUpdateStatusDialog(
            title = "检测失败",
            message = "暂时无法获取最新版本，请稍后重试。",
            autoPromptDisabled = autoPromptDisabled,
            onDismiss = onDismiss,
            onRestoreAutoPrompts = onRestoreAutoPrompts,
        )
    }
}

@Composable
private fun AppUpdateStatusDialog(
    title: String,
    message: String,
    autoPromptDisabled: Boolean,
    onDismiss: () -> Unit,
    onRestoreAutoPrompts: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
        dismissButton = {
            if (autoPromptDisabled) {
                TextButton(
                    onClick = {
                        onRestoreAutoPrompts()
                        onDismiss()
                    },
                ) {
                    Text("恢复自动提示")
                }
            }
        },
    )
}

@Composable
fun ProfilePersonalInfoScreen(
    repository: ModuleRepository,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
) {
    ProfileInfoDetailScreen(
        title = "人员信息",
        repository = repository,
        initialLoadStrategy = initialLoadStrategy,
        kind = ProfileInfoKind.Personal,
        emptyMessage = "暂无人员信息",
    )
}

@Composable
fun ProfileTrainingInfoScreen(
    repository: ModuleRepository,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
) {
    ProfileInfoDetailScreen(
        title = "培养信息",
        repository = repository,
        initialLoadStrategy = initialLoadStrategy,
        kind = ProfileInfoKind.Training,
        emptyMessage = "暂无培养信息",
    )
}

@Composable
fun ProfileThemeScreen(
    appearance: AppAppearancePreferences,
    onThemeSelected: (AppThemeOption) -> Unit,
    onUiStyleSelected: (AppUiStyle) -> Unit,
    onReduceMotionSelected: (AppEffectOverride) -> Unit,
    onReduceTransparencySelected: (AppEffectOverride) -> Unit,
) {
    val effects = LocalAppEffects.current
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(22.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            ThemeSettingsSection(
                title = "界面",
                subtitle = "界面样式与配色相互独立，切换后立即生效",
            ) {
                InterfaceStyleChoice(
                    title = AppUiStyle.Classic.displayName,
                    description = "保留原有导航、角色图标与页面布局",
                    selected = appearance.uiStyle == AppUiStyle.Classic,
                    style = AppUiStyle.Classic,
                    onSelected = onUiStyleSelected,
                )
                InterfaceStyleChoice(
                    title = AppUiStyle.Apple.displayName,
                    description = "更清晰的层级、动漫入口图标与自适应布局",
                    selected = appearance.uiStyle == AppUiStyle.Apple,
                    style = AppUiStyle.Apple,
                    onSelected = onUiStyleSelected,
                )
            }
        }
        item {
            ThemeSettingsSection(
                title = "配色",
                subtitle = "可与任一界面样式自由组合",
            ) {
                ProfileSettingsGroup(items = listOf(
                    ProfileSettingsItem(
                        title = "蓝白色",
                        subtitle = "浅色背景与蓝色主色",
                        iconLabel = "蓝",
                        iconColor = Color(0xFF0B74F6),
                        trailingText = if (appearance.theme == AppThemeOption.Default) "当前" else null,
                        showChevron = false,
                        onClick = { onThemeSelected(AppThemeOption.Default) },
                    ),
                    ProfileSettingsItem(
                        title = "暖金黑",
                        subtitle = "深色背景与暖金主色",
                        iconLabel = "金",
                        iconColor = Color(0xFFE4B96A),
                        trailingText = if (appearance.theme == AppThemeOption.MascotGold) "当前" else null,
                        showChevron = false,
                        onClick = { onThemeSelected(AppThemeOption.MascotGold) },
                    ),
                    ProfileSettingsItem(
                        title = "奶油粉",
                        subtitle = "奶油背景与玫瑰茶棕主色",
                        iconLabel = "粉",
                        iconColor = Color(0xFFB86B63),
                        trailingText = if (appearance.theme == AppThemeOption.IllustrationRose) "当前" else null,
                        showChevron = false,
                        onClick = { onThemeSelected(AppThemeOption.IllustrationRose) },
                    ),
                ))
            }
        }
        item {
            ThemeSettingsSection(
                title = "辅助效果",
                subtitle = "默认跟随系统；也可只为本应用覆盖",
            ) {
                EffectPreferenceRow(
                    title = "减少动态效果",
                    description = "移除位移、弹簧和循环动画，只保留短暂淡化",
                    override = appearance.reduceMotionOverride,
                    effectiveValue = effects.reduceMotion,
                    onOverrideChanged = onReduceMotionSelected,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
                )
                EffectPreferenceRow(
                    title = "降低透明效果",
                    description = "使用实色表面，增强内容边界与对比度",
                    override = appearance.reduceTransparencyOverride,
                    effectiveValue = effects.reduceTransparency,
                    onOverrideChanged = onReduceTransparencySelected,
                )
            }
        }
    }
}

@Composable
private fun ThemeSettingsSection(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@Composable
private fun InterfaceStyleChoice(
    title: String,
    description: String,
    selected: Boolean,
    style: AppUiStyle,
    onSelected: (AppUiStyle) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val showPreview = LocalDensity.current.fontScale < 1.5f
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ui-style-${style.storageValue}")
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = { onSelected(style) },
            ),
        shape = MaterialTheme.shapes.large,
        color = if (selected) colorScheme.primaryContainer else colorScheme.surface,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) colorScheme.primary else colorScheme.outline.copy(alpha = 0.35f),
        ),
        tonalElevation = if (selected) 2.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showPreview) InterfaceStylePreview(style)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selected) colorScheme.onPrimaryContainer else colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) {
                        colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                    } else {
                        colorScheme.onSurfaceVariant
                    },
                )
            }
            RadioButton(
                selected = selected,
                onClick = null,
            )
        }
    }
}

@Composable
private fun InterfaceStylePreview(style: AppUiStyle) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.size(width = 72.dp, height = 52.dp),
        shape = if (style == AppUiStyle.Apple) {
            MaterialTheme.shapes.medium
        } else {
            MaterialTheme.shapes.small
        },
        color = colorScheme.background,
        border = BorderStroke(1.dp, colorScheme.outline.copy(alpha = 0.35f)),
    ) {
        Row(Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (style == AppUiStyle.Apple) {
                Surface(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(10.dp),
                    shape = CircleShape,
                    color = colorScheme.primary.copy(alpha = 0.18f),
                ) {}
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(if (style == AppUiStyle.Apple) 0.72f else 1f)
                        .height(8.dp),
                    shape = CircleShape,
                    color = colorScheme.primary,
                ) {}
                repeat(2) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        shape = if (style == AppUiStyle.Apple) {
                            MaterialTheme.shapes.small
                        } else {
                            MaterialTheme.shapes.extraSmall
                        },
                        color = colorScheme.surfaceVariant,
                    ) {}
                }
            }
        }
    }
}

@Composable
private fun EffectPreferenceRow(
    title: String,
    description: String,
    override: AppEffectOverride,
    effectiveValue: Boolean,
    onOverrideChanged: (AppEffectOverride) -> Unit,
) {
    val isApple = LocalAppUiStyle.current == AppUiStyle.Apple
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        border = if (isApple) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (override != AppEffectOverride.FollowSystem) {
                    TextButton(
                        onClick = { onOverrideChanged(AppEffectOverride.FollowSystem) },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text("恢复跟随系统")
                    }
                } else {
                    Text(
                        text = "当前跟随系统",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Switch(
                modifier = Modifier.semantics { contentDescription = title },
                checked = effectiveValue,
                onCheckedChange = { enabled ->
                    onOverrideChanged(
                        if (enabled) AppEffectOverride.Enabled else AppEffectOverride.Disabled,
                    )
                },
            )
        }
    }
}


@Composable
private fun ProfileInfoDetailScreen(
    title: String,
    repository: ModuleRepository,
    kind: ProfileInfoKind,
    emptyMessage: String,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
) {
    DataScreen(
        title = title,
        initialLoadStrategy = initialLoadStrategy,
        loader = { strategy -> repository.profile(strategy) },
    ) { envelope ->
        val sections = profileDetailSections(envelope.data, kind)
        if (sections.isEmpty()) {
            item {
                InfoCard(emptyMessage, subtitle = "当前教务系统资料中未返回可展示字段") {}
            }
        } else {
            items(sections) { section ->
                ProfileSectionCard(section)
            }
        }
    }
}

@Composable
private fun ProfileSectionCard(section: ProfileSection) {
    InfoCard(section.title) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            section.fields.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { field ->
                        KeyValue(field.label, field.value, Modifier.weight(1f))
                    }
                    if (row.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private data class ProfileSettingsItem(
    val title: String,
    val subtitle: String? = null,
    val iconLabel: String,
    val iconColor: Color,
    val icon: ImageVector? = null,
    val trailingText: String? = null,
    val destructive: Boolean = false,
    val showChevron: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
private fun ProfileSettingsGroup(items: List<ProfileSettingsItem>) {
    val isApple = LocalAppUiStyle.current == AppUiStyle.Apple
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        border = if (isApple) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        },
        tonalElevation = if (isApple) 0.dp else 1.dp,
    ) {
        Column {
            items.forEachIndexed { index, item ->
                ProfileSettingsRow(item)
                if (index < items.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 72.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.32f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileSettingsRow(item: ProfileSettingsItem) {
    val colorScheme = MaterialTheme.colorScheme
    val isApple = LocalAppUiStyle.current == AppUiStyle.Apple
    val titleColor = if (item.destructive) colorScheme.error else colorScheme.onSurface
    val subtitleColor = if (item.destructive) colorScheme.error.copy(alpha = 0.78f) else colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable { item.onClick() }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileSettingsIcon(item.iconLabel, item.iconColor, item.icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = titleColor,
                maxLines = if (isApple) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!item.subtitle.isNullOrBlank()) {
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor,
                    maxLines = if (isApple) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!item.trailingText.isNullOrBlank()) {
            Text(
                text = item.trailingText,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
                maxLines = if (isApple) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (item.showChevron) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
            )
        }
    }
}

@Composable
private fun ProfileSettingsIcon(
    label: String,
    color: Color,
    icon: ImageVector?,
) {
    val badgeSize = with(LocalDensity.current) {
        MaterialTheme.typography.labelLarge.lineHeight.toDp()
    } + 16.dp
    Box(
        modifier = Modifier
            .size(maxOf(38.dp, badgeSize))
            .background(color, CircleShape)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (LocalAppUiStyle.current == AppUiStyle.Apple && icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private enum class ProfileInfoKind {
    Personal,
    Training,
}

private fun profileDetailSections(
    profile: StudentProfileData,
    kind: ProfileInfoKind,
): List<ProfileSection> {
    val sourceSections = profile.sections.ifEmpty { listOf(ProfileSection("基本信息", profile.fields)) }
    val sectionNeedles = when (kind) {
        ProfileInfoKind.Personal -> listOf("人员", "个人", "基本", "身份")
        ProfileInfoKind.Training -> listOf("培养", "学籍", "学习", "专业", "院系")
    }
    val matchedSections = sourceSections
        .filter { section -> section.title.containsAny(sectionNeedles) }
        .mapNotNull { section ->
            val fields = distinctProfileFields(section.fields)
            if (fields.isEmpty()) null else section.copy(fields = fields)
        }
    if (matchedSections.isNotEmpty()) return matchedSections

    val allFields = distinctProfileFields(sourceSections.flatMap { it.fields } + profile.fields)
    val fieldNeedles = when (kind) {
        ProfileInfoKind.Personal -> listOf(
            "姓名",
            "学号",
            "账号",
            "性别",
            "出生",
            "生日",
            "拼音",
            "英文",
            "民族",
            "政治",
            "国籍",
            "留学生",
        )
        ProfileInfoKind.Training -> listOf(
            "学院",
            "院系",
            "专业",
            "班级",
            "年级",
            "培养",
            "层次",
            "学籍",
            "类别",
            "异动",
            "方式",
            "旁听",
            "语言",
            "校区",
        )
    }
    val filteredFields = allFields.filter { it.label.containsAny(fieldNeedles) }
        .ifEmpty { profileFallbackFields(profile, kind) }
    if (filteredFields.isEmpty()) return emptyList()

    val title = when (kind) {
        ProfileInfoKind.Personal -> "人员信息"
        ProfileInfoKind.Training -> "培养信息"
    }
    return listOf(ProfileSection(title, distinctProfileFields(filteredFields)))
}

private fun profileFallbackFields(
    profile: StudentProfileData,
    kind: ProfileInfoKind,
): List<ProfileField> = buildList {
    fun add(label: String, value: String?) {
        if (!value.isNullOrBlank()) add(ProfileField(label, value))
    }
    when (kind) {
        ProfileInfoKind.Personal -> {
            add("姓名", profile.name)
            add("学号", profile.studentId)
            add("账号", profile.account)
            add("性别", profile.gender)
            add("出生日期", profile.birthday)
            add("姓名拼音", profile.namePinyin)
            add("英文姓名", profile.englishName)
            add("民族", profile.ethnicity)
            add("政治面貌", profile.politicalStatus)
            add("国籍", profile.nationality)
            add("是否留学生", profile.isInternationalStudent)
        }
        ProfileInfoKind.Training -> {
            add("学院", profile.college)
            add("专业", profile.major)
            add("班级", profile.className)
            add("年级", profile.grade)
            add("培养层次", profile.educationLevel)
            add("学籍状态", profile.studentStatus ?: profile.hasStudentStatus)
            add("学生类别", profile.studentCategory)
            add("异动状态", profile.changeStatus)
            add("培养方式", profile.cultivationMethod)
            add("是否旁听生", profile.isAuditor)
            add("授课语言", profile.studyLanguage)
            add("校区", profile.campus)
        }
    }
}

private fun distinctProfileFields(fields: List<ProfileField>): List<ProfileField> =
    fields
        .filter { it.label.isNotBlank() && it.value.isNotBlank() }
        .distinctBy { it.label }

private fun String.containsAny(needles: List<String>): Boolean =
    needles.any { contains(it, ignoreCase = true) }

private fun themeOptionLabel(option: AppThemeOption): String =
    when (option) {
        AppThemeOption.Default -> "蓝白色"
        AppThemeOption.MascotGold -> "暖金黑"
        AppThemeOption.IllustrationRose -> "奶油粉"
    }

@Composable
private fun ProfileHeaderCard(profile: StudentProfileData) {
    InfoCard(
        title = profile.name?.takeIf { it.isNotBlank() } ?: "我的信息",
        subtitle = profile.studentId ?: profile.account,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(64.dp)
                    .height(64.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = profile.name?.trim()?.firstOrNull()?.toString() ?: "我",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = listOfNotNull(profile.college, profile.major).joinToString(" · ").ifBlank { "校园账号资料" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ProfileChip(profile.grade ?: profile.educationLevel ?: "在读", Modifier.weight(1f))
                    ProfileChip(profile.className ?: profile.campus ?: "北京交通大学", Modifier.weight(1f))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
            KeyValue("培养层次", profile.educationLevel, Modifier.weight(1f))
            KeyValue("学籍状态", profile.studentStatus ?: profile.hasStudentStatus, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
            KeyValue("联系电话", profile.phone, Modifier.weight(1f))
            KeyValue("邮箱", profile.email, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ProfileChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
