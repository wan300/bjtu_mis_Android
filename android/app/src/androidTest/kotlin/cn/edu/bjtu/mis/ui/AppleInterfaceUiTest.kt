package cn.edu.bjtu.mis.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bjtu.mis.model.ModuleKeys
import cn.edu.bjtu.mis.ui.screens.CourseSelectionStartConfirmationDialog
import cn.edu.bjtu.mis.ui.screens.DashboardAction
import cn.edu.bjtu.mis.ui.screens.ProfileThemeScreen
import cn.edu.bjtu.mis.ui.screens.QuickActionsEditorDialog
import cn.edu.bjtu.mis.ui.screens.TeachingAssessmentSubmitConfirmationDialog
import cn.edu.bjtu.mis.ui.theme.AppAppearancePreferences
import cn.edu.bjtu.mis.ui.theme.AppUiStyle
import cn.edu.bjtu.mis.ui.theme.BjtuMisTheme
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppleInterfaceUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun themeSelectionIsImmediateAndSnackbarCanUndoIt() {
        composeRule.setContent {
            var appearance by remember {
                mutableStateOf(AppAppearancePreferences(uiStyle = AppUiStyle.Classic))
            }
            val snackbarHostState = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            BjtuMisTheme(
                themeOption = appearance.theme,
                appearance = appearance,
            ) {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        ProfileThemeScreen(
                            appearance = appearance,
                            onThemeSelected = { appearance = appearance.copy(theme = it) },
                            onUiStyleSelected = { nextStyle ->
                                val previous = appearance
                                scope.launch {
                                    applyUiStyleChangeWithUndo(
                                        previousAppearance = previous,
                                        nextStyle = nextStyle,
                                        onPreview = { appearance = it },
                                        persist = {},
                                        showUndo = { message ->
                                            snackbarHostState.showSnackbar(
                                                message = message,
                                                actionLabel = "撤销",
                                                withDismissAction = true,
                                            ) == SnackbarResult.ActionPerformed
                                        },
                                    )
                                }
                            },
                            onReduceMotionSelected = {
                                appearance = appearance.copy(reduceMotionOverride = it)
                            },
                            onReduceTransparencySelected = {
                                appearance = appearance.copy(reduceTransparencyOverride = it)
                            },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("ui-style-apple")
            .assertHasClickAction()
            .performClick()
        composeRule.onNodeWithTag("ui-style-apple").assertIsSelected()
        composeRule.onNodeWithText("撤销").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ui-style-classic").assertIsSelected()
    }

    @Test
    fun themeSelectionSnackbarCanBeDismissedWithoutUndo() {
        composeRule.setContent {
            var appearance by remember {
                mutableStateOf(AppAppearancePreferences(uiStyle = AppUiStyle.Classic))
            }
            val snackbarHostState = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            BjtuMisTheme(
                themeOption = appearance.theme,
                appearance = appearance,
            ) {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        ProfileThemeScreen(
                            appearance = appearance,
                            onThemeSelected = { appearance = appearance.copy(theme = it) },
                            onUiStyleSelected = { nextStyle ->
                                val previous = appearance
                                scope.launch {
                                    applyUiStyleChangeWithUndo(
                                        previousAppearance = previous,
                                        nextStyle = nextStyle,
                                        onPreview = { appearance = it },
                                        persist = {},
                                        showUndo = { message ->
                                            snackbarHostState.showSnackbar(
                                                message = message,
                                                actionLabel = "撤销",
                                                withDismissAction = true,
                                            ) == SnackbarResult.ActionPerformed
                                        },
                                    )
                                }
                            },
                            onReduceMotionSelected = {
                                appearance = appearance.copy(reduceMotionOverride = it)
                            },
                            onReduceTransparencySelected = {
                                appearance = appearance.copy(reduceTransparencyOverride = it)
                            },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("ui-style-apple").performClick()
        val dismissLabel = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(androidx.compose.material3.R.string.m3c_snackbar_dismiss)
        composeRule.onNodeWithContentDescription(dismissLabel).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ui-style-apple").assertIsSelected()
        composeRule.onNodeWithText("已切换到主题2").assertDoesNotExist()
    }

    @Test
    fun compactNavigationExposesNamedDestinations() {
        composeRule.setContent {
            val appearance = AppAppearancePreferences(uiStyle = AppUiStyle.Apple)
            BjtuMisTheme(
                themeOption = appearance.theme,
                appearance = appearance,
            ) {
                AppBottomBar(
                    current = "overview",
                    useWindowInsets = false,
                    onSelect = {},
                )
            }
        }

        composeRule.onNodeWithTag("app-bottom-navigation").assertExists()
        composeRule.onNodeWithContentDescription("首页").assertExists()
    }

    @Test
    fun wideNavigationExposesNamedDestinations() {
        composeRule.setContent {
            val appearance = AppAppearancePreferences(uiStyle = AppUiStyle.Apple)
            BjtuMisTheme(
                themeOption = appearance.theme,
                appearance = appearance,
            ) {
                AppNavigationRail(
                    current = "overview",
                    onSelect = {},
                )
            }
        }

        composeRule.onNodeWithTag("app-navigation-rail").assertExists()
        composeRule.onNodeWithContentDescription("服务").assertExists()
    }

    @Test
    fun quickActionsSupportAccessibleMoveAndSave() {
        var savedRoutes: List<String>? = null
        composeRule.setContent {
            val appearance = AppAppearancePreferences(uiStyle = AppUiStyle.Apple)
            BjtuMisTheme(
                themeOption = appearance.theme,
                appearance = appearance,
            ) {
                QuickActionsEditorDialog(
                    selectedRoutes = listOf(ModuleKeys.Homework, ModuleKeys.Timetable),
                    catalog = listOf(
                        DashboardAction(
                            label = "作业",
                            route = ModuleKeys.Homework,
                            icon = Icons.Filled.Home,
                            tint = Color.Blue,
                        ),
                        DashboardAction(
                            label = "课表",
                            route = ModuleKeys.Timetable,
                            icon = Icons.Filled.Schedule,
                            tint = Color.Green,
                        ),
                    ),
                    onDismiss = {},
                    onSave = { savedRoutes = it },
                )
            }
        }

        composeRule.onNodeWithContentDescription("下移 作业").performClick()
        composeRule.onNodeWithText("完成").performClick()
        composeRule.runOnIdle {
            assertEquals(
                listOf(ModuleKeys.Timetable, ModuleKeys.Homework),
                savedRoutes,
            )
        }
    }

    @Test
    fun teachingAssessmentStillRequiresExplicitFinalConfirmation() {
        var confirmed = false
        composeRule.setContent {
            TeachingAssessmentSubmitConfirmationDialog(
                readyCount = 2,
                submitting = false,
                onDismiss = {},
                onConfirm = { confirmed = true },
            )
        }

        composeRule.onNodeWithText("确认提交评教").assertExists()
        composeRule.onNodeWithText(
            "将按当前预填内容提交 2 门课程。提交后可能无法撤回，请确认课程和评价内容无误。",
        ).assertExists()
        composeRule.onNodeWithText("提交").performClick()
        composeRule.runOnIdle { assertTrue(confirmed) }
    }

    @Test
    fun courseSelectionExplainsReplacementBeforeStarting() {
        var confirmed = false
        composeRule.setContent {
            CourseSelectionStartConfirmationDialog(
                courseCount = 3,
                replacementCount = 1,
                onDismiss = {},
                onConfirm = { confirmed = true },
            )
        }

        composeRule.onNodeWithText("确认开始抢课").assertExists()
        composeRule.onNodeWithText(
            "将持续尝试选择 3 门课程，并执行 1 条换课规则；换课成功时会退掉原课程。请确认课程、重试间隔和最大轮数无误。",
        ).assertExists()
        composeRule.onNodeWithText("确认开始").performClick()
        composeRule.runOnIdle { assertTrue(confirmed) }
    }
}
