package cn.edu.bjtu.mis.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import cn.edu.bjtu.mis.R
import cn.edu.bjtu.mis.data.agent.tools.AgentPageTarget
import cn.edu.bjtu.mis.data.provider.SessionValidationPolicy
import cn.edu.bjtu.mis.data.repository.ModuleLoadStrategy
import cn.edu.bjtu.mis.data.sync.SessionKeepAliveForegroundService
import cn.edu.bjtu.mis.data.thirdparty.THIRD_PARTY_DIAGNOSTICS_ROUTE
import cn.edu.bjtu.mis.data.thirdparty.THIRD_PARTY_SERVICES_ROUTE
import cn.edu.bjtu.mis.data.thirdparty.thirdPartyServiceIdFromRoute
import cn.edu.bjtu.mis.data.update.AppUpdateInfo
import cn.edu.bjtu.mis.di.AppContainer
import cn.edu.bjtu.mis.model.AutoLoginStatus
import cn.edu.bjtu.mis.model.HomeworkItem
import cn.edu.bjtu.mis.model.ModuleKeys
import cn.edu.bjtu.mis.model.SessionState
import cn.edu.bjtu.mis.ui.screens.AcademicProgressScreen
import cn.edu.bjtu.mis.ui.screens.CalendarScreen
import cn.edu.bjtu.mis.ui.screens.CourseReplayScreen
import cn.edu.bjtu.mis.ui.screens.CourseResourcesScreen
import cn.edu.bjtu.mis.ui.screens.CourseSelectionScreen
import cn.edu.bjtu.mis.ui.screens.EmptyRoomsScreen
import cn.edu.bjtu.mis.ui.screens.EmploymentConsultationScreen
import cn.edu.bjtu.mis.ui.screens.ExamsScreen
import cn.edu.bjtu.mis.ui.screens.HomeworkDetailScreen
import cn.edu.bjtu.mis.ui.screens.HomeworkReminderSettingsScreen
import cn.edu.bjtu.mis.ui.screens.HomeworkScreen
import cn.edu.bjtu.mis.ui.screens.LoginScreen
import cn.edu.bjtu.mis.ui.screens.MailScreen
import cn.edu.bjtu.mis.ui.screens.OpenWebUiAgentScreen
import cn.edu.bjtu.mis.ui.screens.OverviewScreen
import cn.edu.bjtu.mis.ui.screens.ProfilePersonalInfoScreen
import cn.edu.bjtu.mis.ui.screens.ProfileScreen
import cn.edu.bjtu.mis.ui.screens.ProfileThemeScreen
import cn.edu.bjtu.mis.ui.screens.ProfileTrainingInfoScreen
import cn.edu.bjtu.mis.ui.screens.ScoresScreen
import cn.edu.bjtu.mis.ui.screens.ServicesScreen
import cn.edu.bjtu.mis.ui.screens.TeachingAssessmentScreen
import cn.edu.bjtu.mis.ui.screens.ThirdPartyServiceRoute
import cn.edu.bjtu.mis.ui.screens.ThirdPartyRuntimeDiagnosticsScreen
import cn.edu.bjtu.mis.ui.screens.ThirdPartyServicesScreen
import cn.edu.bjtu.mis.ui.screens.TimetableScreen
import cn.edu.bjtu.mis.ui.screens.ZhixingScreen
import cn.edu.bjtu.mis.ui.screens.navigationTargets
import cn.edu.bjtu.mis.ui.components.AppUpdateAvailableDialog
import cn.edu.bjtu.mis.ui.components.AppUpdateDialogPreference
import cn.edu.bjtu.mis.ui.theme.AppAppearancePreferences
import cn.edu.bjtu.mis.ui.theme.AppEffectOverride
import cn.edu.bjtu.mis.ui.theme.AppHapticEvent
import cn.edu.bjtu.mis.ui.theme.AppThemeOption
import cn.edu.bjtu.mis.ui.theme.AppUiStyle
import cn.edu.bjtu.mis.ui.theme.AppWindowWidthClass
import cn.edu.bjtu.mis.ui.theme.BjtuMisSystemBars
import cn.edu.bjtu.mis.ui.theme.LocalAppDesign
import cn.edu.bjtu.mis.ui.theme.LocalAppHaptics
import cn.edu.bjtu.mis.ui.theme.LocalAppMotion
import cn.edu.bjtu.mis.ui.theme.LocalAppUiStyle
import cn.edu.bjtu.mis.ui.theme.LocalAppWindowWidthClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val RouteHome = "overview"
private const val RouteServices = "services"
private const val LegacyNativeAgentRoute = "agent"
private const val RouteProfilePersonalInfo = "profile_personal_info"
private const val RouteProfileTrainingInfo = "profile_training_info"
private const val RouteProfileTheme = "profile_theme"
private const val RouteProfileHomeworkReminder = "profile_homework_reminder"
private const val RouteHomeworkDetail = "homework_detail"
private const val UiStyleUndoSnackbarDurationMillis = 5_000L
private val MainRoutes = setOf(RouteHome, RouteServices, ModuleKeys.OpenWebUiAgent, ModuleKeys.Profile)
private val ProfileDetailRouteTitles = mapOf(
    RouteProfilePersonalInfo to "人员信息",
    RouteProfileTrainingInfo to "培养信息",
    RouteProfileTheme to "外观与显示",
    RouteProfileHomeworkReminder to "作业提醒",
)

private data class BottomTab(
    val route: String,
    val label: String,
    val icon: ShellIcon,
    @DrawableRes val imageRes: Int? = null,
)

private enum class ShellIcon {
    Home,
    Grid,
    Agent,
    Person,
    Back,
}

private val BottomTabs = listOf(
    BottomTab(RouteHome, "首页", ShellIcon.Home, R.drawable.icon_home),
    BottomTab(RouteServices, "服务", ShellIcon.Grid, R.drawable.icon_services),
    BottomTab(ModuleKeys.OpenWebUiAgent, "Agent", ShellIcon.Agent, R.drawable.icon_agent),
    BottomTab(ModuleKeys.Profile, "我的", ShellIcon.Person, R.drawable.icon_profile),
)

@Composable
fun BjtuMisApp(
    container: AppContainer,
    appearance: AppAppearancePreferences = AppAppearancePreferences(),
    onAppearancePreview: (AppAppearancePreferences?) -> Unit = {},
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.CacheFirst,
    requestedRoute: String? = null,
    onRouteHandled: () -> Unit = {},
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val themeOption = appearance.theme
    val isApple = appearance.uiStyle == AppUiStyle.Apple
    val windowWidthClass = LocalAppWindowWidthClass.current
    val haptics = LocalAppHaptics.current
    val motion = LocalAppMotion.current
    val snackbarHostState = remember { SnackbarHostState() }
    var current by rememberSaveable { mutableStateOf(RouteHome) }
    var mainTab by rememberSaveable { mutableStateOf(RouteHome) }
    var ready by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var sessionDetail by rememberSaveable { mutableStateOf("") }
    var showExitDialog by remember { mutableStateOf(false) }
    var showAutoLoginFailedDialog by remember { mutableStateOf(false) }
    var autoLoginFailedMessage by remember { mutableStateOf("") }
    var autoLoginRetrying by remember { mutableStateOf(false) }
    var openWebUiBackHandler by remember { mutableStateOf<(() -> Boolean)?>(null) }
    var thirdPartyServiceBackHandler by remember { mutableStateOf<(() -> Boolean)?>(null) }
    var hasOpenedOpenWebUiAgent by rememberSaveable { mutableStateOf(false) }
    var updateCheckStarted by remember { mutableStateOf(false) }
    var pendingUpdate by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var homeworkDetailTarget by remember { mutableStateOf<HomeworkItem?>(null) }
    var agentPageTarget by remember { mutableStateOf<AgentPageTarget?>(null) }
    var homeworkDetailReturnRoute by rememberSaveable { mutableStateOf(ModuleKeys.Homework) }
    var initialLoadStrategyConsumed by rememberSaveable { mutableStateOf(false) }
    var predictiveBackProgress by remember { mutableFloatStateOf(0f) }

    fun selectTheme(nextTheme: AppThemeOption) {
        if (nextTheme == appearance.theme) return
        val nextAppearance = appearance.copy(theme = nextTheme)
        onAppearancePreview(nextAppearance)
        scope.launch {
            try {
                container.themeStore.saveTheme(nextTheme)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                onAppearancePreview(null)
                haptics.perform(AppHapticEvent.Error)
                snackbarHostState.showSnackbar("配色保存失败，已恢复原设置")
            }
        }
    }

    fun selectUiStyle(nextStyle: AppUiStyle) {
        if (nextStyle == appearance.uiStyle) return
        val previousAppearance = appearance
        haptics.perform(AppHapticEvent.Selection)
        scope.launch {
            try {
                val outcome = applyUiStyleChangeWithUndo(
                    previousAppearance = previousAppearance,
                    nextStyle = nextStyle,
                    onPreview = onAppearancePreview,
                    persist = container.themeStore::saveUiStyle,
                    showUndo = { message ->
                        withTimeoutOrNull(UiStyleUndoSnackbarDurationMillis) {
                            snackbarHostState.showSnackbar(
                                message = message,
                                actionLabel = "撤销",
                                withDismissAction = true,
                            )
                        } == SnackbarResult.ActionPerformed
                    },
                )
                if (outcome == UiStyleChangeOutcome.Undone) {
                    haptics.perform(AppHapticEvent.Selection)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                onAppearancePreview(null)
                haptics.perform(AppHapticEvent.Error)
                snackbarHostState.showSnackbar("界面设置保存失败，已恢复原设置")
            }
        }
    }

    fun selectReduceMotion(next: AppEffectOverride) {
        if (next == appearance.reduceMotionOverride) return
        onAppearancePreview(appearance.copy(reduceMotionOverride = next))
        scope.launch {
            try {
                container.themeStore.saveReduceMotionOverride(next)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                onAppearancePreview(null)
                haptics.perform(AppHapticEvent.Error)
                snackbarHostState.showSnackbar("动态效果设置保存失败")
            }
        }
    }

    fun selectReduceTransparency(next: AppEffectOverride) {
        if (next == appearance.reduceTransparencyOverride) return
        onAppearancePreview(appearance.copy(reduceTransparencyOverride = next))
        scope.launch {
            try {
                container.themeStore.saveReduceTransparencyOverride(next)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                onAppearancePreview(null)
                haptics.perform(AppHapticEvent.Error)
                snackbarHostState.showSnackbar("透明效果设置保存失败")
            }
        }
    }

    fun startBackgroundQuickSync() {
        scope.launch {
            runCatching { container.syncRepository.runQuickSync() }
        }
    }

    fun autoLoginFailureMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: "自动登录失败，请检查网络后重试。"

    fun navigateMain(route: String) {
        agentPageTarget = null
        val target = normalizeRoute(route)
        current = target
        mainTab = target
        showExitDialog = false
    }

    fun navigateModule(route: String) {
        agentPageTarget = null
        val target = normalizeRoute(route)
        if (target in MainRoutes) {
            navigateMain(target)
        } else if (target in ProfileDetailRouteTitles) {
            current = target
            mainTab = ModuleKeys.Profile
            showExitDialog = false
        } else {
            current = target
            showExitDialog = false
        }
    }

    fun navigateProfileDetail(route: String) {
        current = route
        mainTab = ModuleKeys.Profile
        showExitDialog = false
    }

    fun closeHomeworkDetail() {
        val target = homeworkDetailReturnRoute
        homeworkDetailTarget = null
        current = target
        if (target in MainRoutes) {
            mainTab = target
        } else if (target in ProfileDetailRouteTitles) {
            mainTab = ModuleKeys.Profile
        }
        showExitDialog = false
    }

    fun openHomeworkDetail(item: HomeworkItem, returnRoute: String) {
        homeworkDetailTarget = item
        homeworkDetailReturnRoute = normalizeRoute(returnRoute)
        current = RouteHomeworkDetail
        showExitDialog = false
    }

    fun refreshSession(strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst) {
        scope.launch {
            val cached = runCatching { container.sessionRepository.cachedStatus() }.getOrNull()
            val hadCachedSession = cached?.state == SessionState.Ready
            if (hadCachedSession) {
                sessionDetail = cached?.detail.orEmpty()
                ready = true
            }
            if (strategy == ModuleLoadStrategy.CacheOnly) {
                if (!hadCachedSession) ready = false
                return@launch
            }

            try {
                val session = container.sessionRepository.recoverSession(SessionValidationPolicy.UseRecentOrValidate)
                val isReady = session.status == AutoLoginStatus.Ready
                val autoLoggedIn = isReady && session.attempts > 0
                sessionDetail = session.session?.detail ?: session.message.orEmpty().ifBlank { sessionDetail }
                if (isReady) {
                    showAutoLoginFailedDialog = false
                } else if (session.status == AutoLoginStatus.AutoFailed && session.attempts > 0) {
                    autoLoginFailedMessage = session.message ?: "自动重新登录失败。"
                    showAutoLoginFailedDialog = true
                }
                if (isReady) {
                    ready = true
                    if (autoLoggedIn) startBackgroundQuickSync()
                } else if (!hadCachedSession) {
                    ready = false
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                autoLoginFailedMessage = autoLoginFailureMessage(error)
                showAutoLoginFailedDialog = true
                if (!hadCachedSession) ready = false
            }
        }
    }

    fun continueAutoLoginRetry() {
        scope.launch {
            autoLoginRetrying = true
            try {
                val result = container.sessionRepository.recoverSession(SessionValidationPolicy.Fresh)
                if (result.status == AutoLoginStatus.Ready) {
                    ready = true
                    sessionDetail = result.session?.detail ?: result.message.orEmpty()
                    showAutoLoginFailedDialog = false
                    startBackgroundQuickSync()
                } else {
                    autoLoginFailedMessage = result.message ?: "自动重新登录失败。"
                    showAutoLoginFailedDialog = true
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                autoLoginFailedMessage = autoLoginFailureMessage(error)
                showAutoLoginFailedDialog = true
            } finally {
                autoLoginRetrying = false
            }
        }
    }

    fun applyUpdateDialogPreference(
        update: AppUpdateInfo,
        preference: AppUpdateDialogPreference,
        afterApply: () -> Unit = {},
    ) {
        scope.launch {
            runCatching {
                when {
                    preference.disableAutoPrompts -> container.appUpdatePreferenceStore.disableAutoPrompts()
                    preference.ignoreThisVersion -> container.appUpdatePreferenceStore.ignoreVersion(update.latestVersion)
                }
            }
            afterApply()
        }
    }

    LaunchedEffect(Unit) { refreshSession(initialLoadStrategy) }
    LaunchedEffect(ready) {
        if (initialLoadStrategy != ModuleLoadStrategy.CacheOnly && !updateCheckStarted && ready != null) {
            updateCheckStarted = true
            pendingUpdate = try {
                val update = container.appUpdateChecker.checkForUpdate()
                update?.takeIf {
                    container.appUpdatePreferenceStore.snapshot().shouldPromptForUpdate(it)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
    }
    LaunchedEffect(current) {
        if (current == ModuleKeys.OpenWebUiAgent) {
            hasOpenedOpenWebUiAgent = true
        }
    }
    LaunchedEffect(requestedRoute, ready) {
        val route = requestedRoute
        if (ready == true && !route.isNullOrBlank()) {
            val target = normalizeRoute(route)
            current = target
            mainTab = when (target) {
                RouteHome, RouteServices, ModuleKeys.OpenWebUiAgent, ModuleKeys.Profile -> target
                in ProfileDetailRouteTitles.keys -> ModuleKeys.Profile
                else -> RouteServices
            }
            showExitDialog = false
            onRouteHandled()
        }
    }

    LaunchedEffect(container.agentNavigationRequests, ready) {
        if (ready == true) container.agentNavigationRequests.requests.collect { request ->
            if (request != null) {
                // Only typed, native-validated targets reach this path; no routes from model strings.
                container.agentNavigationRequests.consume(request.id)
                val target = request.target
                if (target.homework != null) openHomeworkDetail(target.homework, ModuleKeys.OpenWebUiAgent)
                else navigateModule(target.route)
                agentPageTarget = target
            }
        }
    }

    pendingUpdate?.takeIf { !showAutoLoginFailedDialog }?.let { update ->
        AppUpdateAvailableDialog(
            update = update,
            onDismiss = { preference ->
                pendingUpdate = null
                applyUpdateDialogPreference(update, preference)
            },
            onOpenUpdate = { preference ->
                pendingUpdate = null
                applyUpdateDialogPreference(update, preference) {
                    openExternalUrl(context, update.releaseUrl)
                }
            },
        )
    }

    if (showAutoLoginFailedDialog) {
        AlertDialog(
            onDismissRequest = { showAutoLoginFailedDialog = false },
            title = { Text("自动重新登录失败") },
            text = { Text(autoLoginFailedMessage.ifBlank { "已连续重试 3 次，当前会话仍不可用。" }) },
            confirmButton = {
                TextButton(
                    enabled = !autoLoginRetrying,
                    onClick = { continueAutoLoginRetry() },
                ) {
                    Text(if (autoLoginRetrying) "重试中" else "继续重试")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showAutoLoginFailedDialog = false }) {
                        Text("稍后处理")
                    }
                    TextButton(
                        onClick = {
                            showAutoLoginFailedDialog = false
                            ready = false
                        },
                    ) {
                        Text("重新登录")
                    }
                }
            },
        )
    }

    when (ready) {
        null -> Splash()
        false -> LoginScreen(container.sessionRepository) {
            ready = true
            current = requestedRoute?.let(::normalizeRoute) ?: RouteHome
            mainTab = when {
                current in MainRoutes -> current
                current in ProfileDetailRouteTitles -> ModuleKeys.Profile
                else -> RouteServices
            }
            if (!requestedRoute.isNullOrBlank()) onRouteHandled()
            startBackgroundQuickSync()
            refreshSession()
        }
        true -> {
            val routeInitialLoadStrategy = if (initialLoadStrategyConsumed) {
                ModuleLoadStrategy.NetworkFirst
            } else {
                initialLoadStrategy
            }
            LaunchedEffect(current) {
                if (!initialLoadStrategyConsumed) {
                    initialLoadStrategyConsumed = true
                }
            }

            val routeContent: @Composable (String) -> Unit = { route ->
                when (route) {
                    RouteHome -> OverviewScreen(
                        overviewRepository = container.overviewRepository,
                        syncRepository = container.syncRepository,
                        quickActionsStore = container.quickActionsStore,
                        thirdPartyServiceRepository = container.thirdPartyServiceRepository,
                        sessionDetail = sessionDetail,
                        initialLoadStrategy = routeInitialLoadStrategy,
                        onNavigate = ::navigateModule,
                        onOpenServices = { navigateMain(RouteServices) },
                        extendIntoStatusBar = !isApple && themeOption.isDark && route == RouteHome,
                    )
                    RouteServices -> ServicesScreen(
                        moduleRepository = container.moduleRepository,
                        thirdPartyServiceRepository = container.thirdPartyServiceRepository,
                        onNavigate = ::navigateModule,
                    )
                    THIRD_PARTY_SERVICES_ROUTE -> ThirdPartyServicesScreen(
                        repository = container.thirdPartyServiceRepository,
                        catalogRepository = container.thirdPartyCatalogRepository,
                        onOpenService = ::navigateModule,
                        onOpenDiagnostics = { navigateModule(THIRD_PARTY_DIAGNOSTICS_ROUTE) },
                    )
                    THIRD_PARTY_DIAGNOSTICS_ROUTE -> ThirdPartyRuntimeDiagnosticsScreen()
                    ModuleKeys.OpenWebUiAgent -> Unit
                    ModuleKeys.Profile -> MainScreenPadding {
                        ProfileScreen(
                            repository = container.moduleRepository,
                            appUpdateChecker = container.appUpdateChecker,
                            appUpdatePreferenceStore = container.appUpdatePreferenceStore,
                            selectedTheme = themeOption,
                            initialLoadStrategy = routeInitialLoadStrategy,
                            onLogout = {
                                container.sessionRepository.logout()
                                SessionKeepAliveForegroundService.stop(context)
                                hasOpenedOpenWebUiAgent = false
                                ready = false
                            },
                            onOpenPersonalInfo = { navigateProfileDetail(RouteProfilePersonalInfo) },
                            onOpenTrainingInfo = { navigateProfileDetail(RouteProfileTrainingInfo) },
                            onOpenTheme = { navigateProfileDetail(RouteProfileTheme) },
                            onOpenHomeworkReminder = { navigateProfileDetail(RouteProfileHomeworkReminder) },
                            onNavigate = ::navigateModule,
                        )
                    }
                    RouteProfilePersonalInfo -> MainScreenPadding {
                        ProfilePersonalInfoScreen(container.moduleRepository, routeInitialLoadStrategy)
                    }
                    RouteProfileTrainingInfo -> MainScreenPadding {
                        ProfileTrainingInfoScreen(container.moduleRepository, routeInitialLoadStrategy)
                    }
                    RouteProfileTheme -> MainScreenPadding {
                        ProfileThemeScreen(
                            appearance = appearance,
                            onThemeSelected = ::selectTheme,
                            onUiStyleSelected = ::selectUiStyle,
                            onReduceMotionSelected = ::selectReduceMotion,
                            onReduceTransparencySelected = ::selectReduceTransparency,
                        )
                    }
                    RouteProfileHomeworkReminder -> MainScreenPadding {
                        HomeworkReminderSettingsScreen(container.homeworkReminderPreferenceStore)
                    }
                    RouteHomeworkDetail -> MainScreenPadding {
                        homeworkDetailTarget?.let { homework ->
                            HomeworkDetailScreen(
                                initialHomework = homework,
                                repository = container.moduleRepository,
                                attachmentRepository = container.homeworkAttachmentRepository,
                                initialLoadStrategy = if (agentPageTarget?.homework != null) ModuleLoadStrategy.CacheOnly else ModuleLoadStrategy.NetworkFirst,
                                onOpenAgent = { navigateModule(ModuleKeys.OpenWebUiAgent) },
                            )
                        } ?: Text("作业详情不可用")
                    }
                    else -> {
                        val thirdPartyServiceId = thirdPartyServiceIdFromRoute(route)
                        if (thirdPartyServiceId != null) {
                            ThirdPartyServiceRoute(
                                serviceId = thirdPartyServiceId,
                                repository = container.thirdPartyServiceRepository,
                                apiRegistry = container.thirdPartyServiceApiRegistry,
                                resourceStore = container.thirdPartyResourceStore,
                                kvStore = container.thirdPartyKvStore,
                                onBackToServices = { current = THIRD_PARTY_SERVICES_ROUTE },
                                onBackHandlerChanged = { thirdPartyServiceBackHandler = it },
                            )
                        } else {
                            MainScreenPadding {
                                ModuleRoute(
                                    route = route,
                                    container = container,
                                    initialLoadStrategy = if (agentPageTarget?.route == route) ModuleLoadStrategy.CacheOnly else routeInitialLoadStrategy,
                                    agentPageTarget = agentPageTarget,
                                    onNavigate = ::navigateModule,
                                    onOpenHomeworkDetail = ::openHomeworkDetail,
                                )
                            }
                        }
                    }
                }
            }

            fun handleAppBack() {
                when {
                    current == ModuleKeys.OpenWebUiAgent && openWebUiBackHandler?.invoke() == true -> Unit
                    thirdPartyServiceIdFromRoute(current) != null &&
                        thirdPartyServiceBackHandler?.invoke() == true -> Unit
                    thirdPartyServiceIdFromRoute(current) != null -> current = THIRD_PARTY_SERVICES_ROUTE
                    current == THIRD_PARTY_DIAGNOSTICS_ROUTE -> current = THIRD_PARTY_SERVICES_ROUTE
                    current == RouteHomeworkDetail -> closeHomeworkDetail()
                    current !in MainRoutes -> current = mainTab
                    current != RouteHome -> navigateMain(RouteHome)
                    else -> showExitDialog = true
                }
            }

            if (isApple) {
                PredictiveBackHandler {
                    try {
                        it.collect { backEvent ->
                            predictiveBackProgress = if (motion.reduceMotion) {
                                0f
                            } else {
                                backEvent.progress.coerceIn(0f, 1f)
                            }
                        }
                        predictiveBackProgress = 0f
                        handleAppBack()
                    } catch (_: CancellationException) {
                        predictiveBackProgress = 0f
                    }
                }
            } else {
                BackHandler { handleAppBack() }
            }

            if (showExitDialog) {
                AlertDialog(
                    onDismissRequest = { showExitDialog = false },
                    title = { Text("退出应用") },
                    text = { Text("确定要退出 BJTU MIS 吗？") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showExitDialog = false
                                onExit()
                            },
                        ) {
                            Text("是")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showExitDialog = false }) {
                            Text("否")
                        }
                    },
                )
            }

            val useBackgroundStatusBar = isApple ||
                current == RouteServices ||
                current == ModuleKeys.Profile ||
                current !in MainRoutes
            val backgroundStatusBarColor = MaterialTheme.colorScheme.background
            val extendHomeIntoStatusBar = !isApple && themeOption.isDark && current == RouteHome
            val isOpenWebUiAgent = current == ModuleKeys.OpenWebUiAgent
            val isLightAgentTheme = isOpenWebUiAgent && !themeOption.isDark
            val useNavigationRail = isApple && windowWidthClass != AppWindowWidthClass.Compact
            BjtuMisSystemBars(
                statusBarColor = when {
                    extendHomeIntoStatusBar -> Color.Transparent
                    isLightAgentTheme -> Color.White
                    isOpenWebUiAgent -> Color(0xFF171717)
                    useBackgroundStatusBar -> backgroundStatusBarColor
                    else -> MaterialTheme.colorScheme.primary
                },
                navigationBarColor = MaterialTheme.colorScheme.surface,
                useDarkStatusBarIcons = if (isOpenWebUiAgent) {
                    isLightAgentTheme
                } else {
                    !themeOption.isDark
                },
                useDarkNavigationBarIcons = !themeOption.isDark,
                decorFitsSystemWindows = !extendHomeIntoStatusBar,
            )

            Row(Modifier.fillMaxSize()) {
                if (useNavigationRail) {
                    AppNavigationRail(
                        current = if (current in MainRoutes) current else mainTab,
                        onSelect = ::navigateMain,
                    )
                }
                Scaffold(
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.background,
                    contentWindowInsets = WindowInsets(0.dp),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                    when {
                        current == RouteServices -> CompactTitleBar("服务")
                        current == RouteHomeworkDetail -> DetailTitleBar(
                            title = "作业详情",
                            onBack = ::closeHomeworkDetail,
                        )
                        current == THIRD_PARTY_SERVICES_ROUTE -> DetailTitleBar(
                            title = "第三方服务",
                            onBack = { current = RouteServices },
                        )
                        current == THIRD_PARTY_DIAGNOSTICS_ROUTE -> DetailTitleBar(
                            title = "插件运行环境诊断",
                            onBack = { current = THIRD_PARTY_SERVICES_ROUTE },
                        )
                        thirdPartyServiceIdFromRoute(current) != null -> Unit
                        current in ProfileDetailRouteTitles -> DetailTitleBar(
                            title = ProfileDetailRouteTitles.getValue(current),
                            onBack = {
                                current = ModuleKeys.Profile
                                mainTab = ModuleKeys.Profile
                            },
                        )
                        current !in MainRoutes -> DetailTitleBar(
                            title = navigationTargets.firstOrNull { it.key == current }?.label ?: "服务详情",
                            onBack = { current = mainTab },
                        )
                    }
                },
                    bottomBar = {
                        if (current in MainRoutes && !useNavigationRail) {
                            AppBottomBar(
                                current = current,
                                useWindowInsets = extendHomeIntoStatusBar,
                                onSelect = ::navigateMain,
                            )
                        }
                    },
                ) { padding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .graphicsLayer {
                                val progress = if (
                                    isApple &&
                                    current !in MainRoutes &&
                                    !motion.reduceMotion
                                ) {
                                    predictiveBackProgress
                                } else {
                                    0f
                                }
                                translationX = size.width * 0.08f * progress
                                alpha = 1f - (0.06f * progress)
                            },
                    ) {
                    val showOpenWebUiAgent = current == ModuleKeys.OpenWebUiAgent
                    val keepOpenWebUiAgent = hasOpenedOpenWebUiAgent || showOpenWebUiAgent
                    if (isApple) {
                        AnimatedContent(
                            targetState = current,
                            modifier = Modifier.fillMaxSize(),
                            transitionSpec = {
                                if (motion.reduceMotion) {
                                    fadeIn(tween(motion.feedbackDurationMillis)) togetherWith
                                        fadeOut(tween(motion.feedbackDurationMillis))
                                } else {
                                    when (
                                        appRouteTransitionDirection(
                                            initialState,
                                            targetState,
                                        )
                                    ) {
                                        AppRouteTransitionDirection.Crossfade ->
                                            fadeIn(tween(160)) togetherWith fadeOut(tween(140))
                                        AppRouteTransitionDirection.Forward ->
                                            (
                                                slideInHorizontally(
                                                    animationSpec = spring(
                                                        dampingRatio = motion.normalDampingRatio,
                                                        stiffness = motion.normalStiffness,
                                                    ),
                                                    initialOffsetX = { width -> width / 10 },
                                                ) + fadeIn(tween(140))
                                            ) togetherWith (
                                                slideOutHorizontally(
                                                    animationSpec = spring(
                                                        dampingRatio = motion.normalDampingRatio,
                                                        stiffness = motion.normalStiffness,
                                                    ),
                                                    targetOffsetX = { width -> -width / 16 },
                                                ) + fadeOut(tween(120))
                                            )
                                        AppRouteTransitionDirection.Backward ->
                                            (
                                                slideInHorizontally(
                                                    animationSpec = spring(
                                                        dampingRatio = motion.normalDampingRatio,
                                                        stiffness = motion.normalStiffness,
                                                    ),
                                                    initialOffsetX = { width -> -width / 16 },
                                                ) + fadeIn(tween(140))
                                            ) togetherWith (
                                                slideOutHorizontally(
                                                    animationSpec = spring(
                                                        dampingRatio = motion.normalDampingRatio,
                                                        stiffness = motion.normalStiffness,
                                                    ),
                                                    targetOffsetX = { width -> width / 10 },
                                                ) + fadeOut(tween(120))
                                            )
                                    }
                                }
                            },
                            label = "appRoute",
                        ) { animatedRoute ->
                            routeContent(animatedRoute)
                        }
                    } else {
                        routeContent(current)
                    }
                    if (keepOpenWebUiAgent) {
                        OpenWebUiAgentScreen(
                            repository = container.moduleRepository,
                            themeOption = themeOption,
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(if (showOpenWebUiAgent) 1f else -1f),
                            visible = showOpenWebUiAgent,
                            onBackHandlerChanged = { openWebUiBackHandler = it },
                        )
                    }
                    }
                }
            }
        }
    }
}

private fun normalizeRoute(route: String): String =
    if (route == LegacyNativeAgentRoute) ModuleKeys.OpenWebUiAgent else route

@Composable
private fun MainScreenPadding(content: @Composable () -> Unit) {
    val design = LocalAppDesign.current
    val widthClass = LocalAppWindowWidthClass.current
    val horizontalPadding = if (
        LocalAppUiStyle.current == AppUiStyle.Apple &&
        widthClass == AppWindowWidthClass.Expanded
    ) {
        32.dp
    } else {
        design.pageHorizontalPadding
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                horizontal = horizontalPadding,
                vertical = design.pageVerticalPadding,
            ),
    ) {
        content()
    }
}

@Composable
private fun ModuleRoute(
    route: String,
    container: AppContainer,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    agentPageTarget: AgentPageTarget? = null,
    onNavigate: (String) -> Unit,
    onOpenHomeworkDetail: (HomeworkItem, String) -> Unit,
) {
    when (route) {
        ModuleKeys.AcademicProgress -> AcademicProgressScreen(container.moduleRepository, initialLoadStrategy)
        ModuleKeys.HistoryScores -> ScoresScreen(container.moduleRepository, history = true, initialLoadStrategy = initialLoadStrategy)
        ModuleKeys.Timetable -> TimetableScreen(
            container.moduleRepository,
            container.courseResourceRepository,
            container.homeworkAttachmentRepository,
            initialLoadStrategy = initialLoadStrategy,
            onOpenHomeworkDetail = { onOpenHomeworkDetail(it, ModuleKeys.Timetable) },
        )
        ModuleKeys.CourseSelection -> CourseSelectionScreen(
            repository = container.courseSelectionRepository,
            runner = container.courseSelectionRunner,
            initialLoadStrategy = initialLoadStrategy,
        )
        ModuleKeys.Exams -> ExamsScreen(
            repository = container.moduleRepository,
            initialLoadStrategy = initialLoadStrategy,
            onNavigate = onNavigate,
        )
        ModuleKeys.Scores -> ScoresScreen(container.moduleRepository, initialLoadStrategy = initialLoadStrategy)
        ModuleKeys.Calendar -> CalendarScreen(
            repository = container.moduleRepository,
            employmentRepository = container.employmentConsultationRepository,
            employmentCalendarSyncStore = container.employmentCalendarSyncStore,
            todoReminders = container.todoReminders,
            initialLoadStrategy = initialLoadStrategy,
            onOpenHomework = { onOpenHomeworkDetail(it, ModuleKeys.Calendar) },
        )
        ModuleKeys.Homework -> HomeworkScreen(
            repository = container.moduleRepository,
            initialLoadStrategy = initialLoadStrategy,
            onNavigate = onNavigate,
            onOpenHomeworkDetail = { onOpenHomeworkDetail(it, ModuleKeys.Homework) },
        )
        ModuleKeys.Mail -> MailScreen(container.mailRepository, initialLoadStrategy)
        ModuleKeys.Zhixing -> ZhixingScreen(container.zhixingRepository, initialLoadStrategy)
        ModuleKeys.EmploymentConsultation -> EmploymentConsultationScreen(
            repository = container.employmentConsultationRepository,
            employmentCalendarSyncStore = container.employmentCalendarSyncStore,
            initialLoadStrategy = initialLoadStrategy,
        )
        ModuleKeys.CourseResources -> CourseResourcesScreen(container.courseResourceRepository, initialLoadStrategy,
            initialCourseId = agentPageTarget?.courseId.orEmpty(), initialFolderId = agentPageTarget?.folderId ?: "0")
        ModuleKeys.CourseReplay -> CourseReplayScreen(container.courseReplayRepository, container.httpClient.client, initialLoadStrategy)
        ModuleKeys.TeachingAssessment -> TeachingAssessmentScreen(container.moduleRepository, initialLoadStrategy)
        ModuleKeys.EmptyRooms -> EmptyRoomsScreen(container.moduleRepository, initialLoadStrategy)
    }
}

@Composable
private fun DetailTitleBar(title: String, onBack: () -> Unit) {
    CompactTitleBar(title = title, onBack = onBack)
}

@Composable
private fun CompactTitleBar(title: String, onBack: (() -> Unit)? = null) {
    val colorScheme = MaterialTheme.colorScheme
    val isApple = LocalAppUiStyle.current == AppUiStyle.Apple
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (isApple) 60.dp else 52.dp)
            .background(if (isApple) colorScheme.surface else colorScheme.background)
            .padding(
                start = if (onBack == null) {
                    if (isApple) 24.dp else 20.dp
                } else {
                    4.dp
                },
                end = if (isApple) 20.dp else 16.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(48.dp),
            ) {
                if (isApple) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                    )
                } else {
                    ShellLineIcon(ShellIcon.Back, color = colorScheme.onBackground)
                }
            }
        }
        Text(
            text = title,
            color = colorScheme.onBackground,
            fontSize = if (isApple) 22.sp else 20.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun AppBottomBar(
    current: String,
    useWindowInsets: Boolean,
    onSelect: (String) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val isClassic = LocalAppUiStyle.current == AppUiStyle.Classic
    NavigationBar(
        modifier = Modifier.testTag("app-bottom-navigation"),
        containerColor = colorScheme.surface,
        tonalElevation = if (isClassic) 8.dp else 0.dp,
        windowInsets = if (useWindowInsets) NavigationBarDefaults.windowInsets else WindowInsets(0.dp),
    ) {
        BottomTabs.forEach { tab ->
            val selected = current == tab.route
            val label = if (!isClassic && tab.route == ModuleKeys.OpenWebUiAgent) {
                "助手"
            } else {
                tab.label
            }
            NavigationBarItem(
                modifier = Modifier.semantics {
                    contentDescription = label
                },
                selected = selected,
                onClick = { onSelect(tab.route) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = colorScheme.primary,
                    selectedTextColor = colorScheme.primary,
                    indicatorColor = colorScheme.primaryContainer,
                    unselectedIconColor = colorScheme.onSurfaceVariant,
                    unselectedTextColor = colorScheme.onSurfaceVariant,
                ),
                icon = {
                    if (tab.imageRes != null) {
                        Image(
                            painter = painterResource(tab.imageRes),
                            contentDescription = null,
                            modifier = Modifier
                                .size(28.dp)
                                .alpha(if (selected) 1f else 0.48f),
                        )
                    } else if (isClassic) {
                        ShellLineIcon(
                            icon = tab.icon,
                            color = if (selected) colorScheme.primary else colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Icon(
                            imageVector = tab.icon.materialIcon(),
                            contentDescription = null,
                            modifier = Modifier.size(25.dp),
                        )
                    }
                },
                label = {
                    Text(
                        text = label,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                },
            )
        }
    }
}

@Composable
internal fun AppNavigationRail(
    current: String,
    onSelect: (String) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    NavigationRail(
        modifier = Modifier
            .fillMaxHeight()
            .width(92.dp)
            .testTag("app-navigation-rail"),
        containerColor = colorScheme.surface,
    ) {
        Spacer(Modifier.height(18.dp))
        BottomTabs.forEach { tab ->
            val selected = current == tab.route
            val label = if (tab.route == ModuleKeys.OpenWebUiAgent) "助手" else tab.label
            NavigationRailItem(
                modifier = Modifier.semantics {
                    contentDescription = label
                },
                selected = selected,
                onClick = { onSelect(tab.route) },
                icon = {
                    if (tab.imageRes != null) {
                        Image(
                            painter = painterResource(tab.imageRes),
                            contentDescription = null,
                            modifier = Modifier
                                .size(28.dp)
                                .alpha(if (selected) 1f else 0.48f),
                        )
                    } else {
                        Icon(
                            imageVector = tab.icon.materialIcon(),
                            contentDescription = null,
                            modifier = Modifier.size(25.dp),
                        )
                    }
                },
                label = {
                    Text(
                        text = label,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )
        }
    }
}

private fun ShellIcon.materialIcon(): ImageVector =
    when (this) {
        ShellIcon.Home -> Icons.Filled.Home
        ShellIcon.Grid -> Icons.Filled.Apps
        ShellIcon.Agent -> Icons.Filled.SmartToy
        ShellIcon.Person -> Icons.Filled.Person
        ShellIcon.Back -> Icons.AutoMirrored.Filled.ArrowBack
    }

@Composable
private fun ShellLineIcon(icon: ShellIcon, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) {
        val stroke = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
        when (icon) {
            ShellIcon.Home -> {
                val roof = Path().apply {
                    moveTo(size.width * 0.16f, size.height * 0.48f)
                    lineTo(size.width * 0.5f, size.height * 0.18f)
                    lineTo(size.width * 0.84f, size.height * 0.48f)
                }
                drawPath(roof, color, style = stroke)
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width * 0.25f, size.height * 0.44f),
                    size = Size(size.width * 0.5f, size.height * 0.42f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                    style = stroke,
                )
            }
            ShellIcon.Grid -> {
                val cell = size.width * 0.26f
                listOf(0.2f to 0.2f, 0.54f to 0.2f, 0.2f to 0.54f, 0.54f to 0.54f).forEach { (x, y) ->
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(size.width * x, size.height * y),
                        size = Size(cell, cell),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                    )
                }
            }
            ShellIcon.Agent -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width * 0.2f, size.height * 0.28f),
                    size = Size(size.width * 0.6f, size.height * 0.46f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx(), 7.dp.toPx()),
                    style = stroke,
                )
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.5f, size.height * 0.28f),
                    end = Offset(size.width * 0.5f, size.height * 0.14f),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
                drawCircle(color, radius = size.minDimension * 0.045f, center = Offset(size.width * 0.5f, size.height * 0.11f))
                drawCircle(color, radius = size.minDimension * 0.035f, center = Offset(size.width * 0.39f, size.height * 0.48f))
                drawCircle(color, radius = size.minDimension * 0.035f, center = Offset(size.width * 0.61f, size.height * 0.48f))
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.4f, size.height * 0.62f),
                    end = Offset(size.width * 0.6f, size.height * 0.62f),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
            }
            ShellIcon.Person -> {
                drawCircle(color, radius = size.minDimension * 0.16f, center = Offset(size.width * 0.5f, size.height * 0.34f), style = stroke)
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width * 0.26f, size.height * 0.58f),
                    size = Size(size.width * 0.48f, size.height * 0.24f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(9.dp.toPx(), 9.dp.toPx()),
                    style = stroke,
                )
            }
            ShellIcon.Back -> {
                drawLine(color, Offset(size.width * 0.62f, size.height * 0.2f), Offset(size.width * 0.34f, size.height * 0.5f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.34f, size.height * 0.5f), Offset(size.width * 0.62f, size.height * 0.8f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
        }
    }
}

private fun openExternalUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

@Composable
private fun Splash() {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        colorScheme.background,
                        colorScheme.primaryContainer,
                        colorScheme.background,
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "MIS 教学服务系统",
                color = colorScheme.onBackground,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            CircularProgressIndicator(color = colorScheme.primary)
            Text(
                text = "正在检查本地会话…",
                color = colorScheme.onBackground.copy(alpha = 0.86f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
