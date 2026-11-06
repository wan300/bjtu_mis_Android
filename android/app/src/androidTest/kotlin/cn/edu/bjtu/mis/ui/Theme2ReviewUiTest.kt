package cn.edu.bjtu.mis.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bjtu.mis.BjtuMisApplication
import cn.edu.bjtu.mis.model.ModuleKeys
import cn.edu.bjtu.mis.ui.screens.ProfileThemeScreen
import cn.edu.bjtu.mis.ui.screens.ServicesScreen
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import cn.edu.bjtu.mis.ui.theme.AppAppearancePreferences
import cn.edu.bjtu.mis.ui.theme.AppThemeOption
import cn.edu.bjtu.mis.ui.theme.AppUiStyle
import cn.edu.bjtu.mis.ui.theme.BjtuMisTheme
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class Theme2ReviewUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun blueSettingsShowBothThemeNames() = reviewSettings(AppThemeOption.Default)

    @Test
    fun goldSettingsShowBothThemeNames() = reviewSettings(AppThemeOption.MascotGold)

    @Test
    fun roseSettingsShowBothThemeNames() = reviewSettings(AppThemeOption.IllustrationRose)

    @Test
    fun narrowSettingsRemainReadableAtDoubleFontScale() {
        showTheme(fontScale = 2f) { ThemeSettings() }
        composeRule.onNodeWithTag("ui-style-apple").performScrollTo().assertIsSelected()
        saveScreenshot("settings-large-font")
        assertTextFits("主题2")
        assertTextFits("更清晰的层级、动漫入口图标与自适应布局")
    }

    @Test
    fun shortNavigationRailCanReachEveryDestinationAtDoubleFontScale() {
        showTheme(fontScale = 2f) {
            var current by remember { mutableStateOf("overview") }
            Box(Modifier.height(220.dp)) {
                AppNavigationRail(current = current, onSelect = { current = it })
            }
        }
        listOf("我的", "助手", "服务", "首页").forEach { label ->
            composeRule.onNodeWithContentDescription(label)
                .performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
        }
        saveScreenshot("short-navigation-rail")
    }

    @Test
    fun compactNavigationSelectsEveryDestination() {
        showTheme {
            var current by remember { mutableStateOf("overview") }
            AppBottomBar(current, useWindowInsets = false, onSelect = { current = it })
        }
        listOf("服务", "助手", "我的", "首页").forEach { label ->
            composeRule.onNodeWithContentDescription(label)
                .assertIsDisplayed().performClick().assertIsSelected()
        }
        saveScreenshot("compact-navigation")
    }

    @Test
    fun wideSettingsAndNavigationRemainVisible() {
        showTheme(widthDp = 840) {
            Row {
                AppNavigationRail(current = ModuleKeys.Profile, onSelect = {})
                Column(Modifier.weight(1f)) { ThemeSettings() }
            }
        }
        composeRule.onNodeWithContentDescription("我的").assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithText("主题2").assertIsDisplayed()
        saveScreenshot("wide-settings-navigation")
    }

    @Test
    fun detailTitleWrapsWithoutClippingAtDoubleFontScale() {
        showTheme(fontScale = 2f) {
            CompactTitleBar("插件运行环境诊断", onBack = {})
        }
        assertTextFits("插件运行环境诊断")
        composeRule.onNodeWithContentDescription("返回").assertIsDisplayed()
        saveScreenshot("detail-title-large-font")
    }

    @Test
    fun effectSwitchesExposeTheirPurpose() {
        showTheme { ThemeSettings() }
        listOf("减少动态效果", "降低透明效果").forEach { label ->
            composeRule.onNodeWithContentDescription(label).performScrollTo().assertIsDisplayed()
        }
        saveScreenshot("effect-switches")
    }

    @Test
    fun serviceSearchSurvivesOpeningAndReturningFromAResult() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as BjtuMisApplication
        var destination: String? = null
        showTheme {
            var route by rememberSaveable { mutableStateOf("services") }
            val stateHolder = rememberSaveableStateHolder()
            stateHolder.SaveableStateProvider(route) {
                if (route == "services") {
                    ServicesScreen(
                        moduleRepository = app.container.moduleRepository,
                        thirdPartyServiceRepository = app.container.thirdPartyServiceRepository,
                        onNavigate = {
                            destination = it
                            route = it
                        },
                    )
                } else {
                    TextButton(onClick = { route = "services" }) { Text("返回服务") }
                }
            }
        }
        composeRule.onNodeWithText("搜索服务").performTextInput("成绩")
        composeRule.onNodeWithText("历史成绩").performScrollTo().performClick()
        composeRule.runOnIdle { org.junit.Assert.assertEquals(ModuleKeys.HistoryScores, destination) }
        composeRule.onNodeWithText("返回服务").performClick()
        composeRule.onNodeWithText("成绩", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("历史成绩").assertExists()
        saveScreenshot("service-search-restored")
    }

    private fun reviewSettings(theme: AppThemeOption) {
        showTheme(theme = theme) { ThemeSettings(theme) }
        composeRule.onNodeWithText("主题1").assertIsDisplayed()
        composeRule.onNodeWithText("主题2").assertIsDisplayed()
        composeRule.onNodeWithTag("ui-style-apple").assertIsSelected()
        saveScreenshot("settings-${theme.storageValue}")
        assertTextFits("蓝")
    }

    private fun showTheme(
        theme: AppThemeOption = AppThemeOption.Default,
        fontScale: Float = 1f,
        widthDp: Int = 320,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                BjtuMisTheme(
                    themeOption = theme,
                    appearance = AppAppearancePreferences(theme = theme, uiStyle = AppUiStyle.Apple),
                ) {
                    Surface(
                        modifier = Modifier.width(widthDp.dp).fillMaxHeight().testTag("review-content"),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        Column { content() }
                    }
                }
            }
        }
    }

    @Composable
    private fun ThemeSettings(theme: AppThemeOption = AppThemeOption.Default) {
        Column {
            CompactTitleBar("外观与显示", onBack = {})
            Box(Modifier.padding(20.dp)) {
                ProfileThemeScreen(
                    appearance = AppAppearancePreferences(theme = theme, uiStyle = AppUiStyle.Apple),
                    onThemeSelected = {},
                    onUiStyleSelected = {},
                    onReduceMotionSelected = {},
                    onReduceTransparencySelected = {},
                )
            }
        }
    }

    private fun assertTextFits(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Missing text layout: $text", layouts.isNotEmpty())
        layouts.forEach {
            // Paragraph width is the available space; check the actual line bounds for wrap-content Text.
            val lineOverflow = (0 until it.lineCount).any { line ->
                it.getLineLeft(line) < -1f || it.getLineRight(line) > it.size.width + 1f
            }
            val overflow = it.multiParagraph.didExceedMaxLines ||
                lineOverflow ||
                it.multiParagraph.height > it.size.height + 1f
            assertFalse(
                "Clipped text: $text; size=${it.size}; lines=${it.lineCount}; " +
                    "paragraph=${it.multiParagraph.width} x ${it.multiParagraph.height}",
                overflow,
            )
        }
    }

    private fun saveScreenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("themeReviewScreenshots") != "true") return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "theme-review").apply { mkdirs() }
        val bitmap = composeRule.onNodeWithTag("review-content").captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
