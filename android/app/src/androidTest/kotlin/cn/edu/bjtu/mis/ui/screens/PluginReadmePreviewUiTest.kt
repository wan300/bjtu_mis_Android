package cn.edu.bjtu.mis.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.edu.bjtu.mis.ui.components.LoadState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PluginReadmePreviewUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun longRequirementsScrollWithoutDisplacingTheReadme() {
        val requirementLabels = (0..199).map { "Requirement $it" }
        compose.setContent {
            MaterialTheme {
                PluginReadmePreviewDialog(
                    target = PluginReadmeTarget(
                        title = "Plugin README",
                        owner = "alice",
                        repository = "demo",
                        commitSha = "abc1234",
                        requirements = requirementLabels,
                    ),
                    state = LoadState.Data("# Plugin README\n\nPlugin documentation."),
                    onRetry = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("plugin-readme-requirements").assertDoesNotExist()
        compose.onNodeWithTag("plugin-readme-content").assertIsDisplayed()
        compose.onNodeWithText("展开权限与运行要求").performClick()
        val requirements = compose.onNodeWithTag("plugin-readme-requirements")
        val content = compose.onNodeWithTag("plugin-readme-content")
        requirements.assertIsDisplayed()
        content.assertIsDisplayed()
        val contentNode = content.fetchSemanticsNode()
        val requirementsNode = requirements.fetchSemanticsNode()
        compose.runOnIdle {
            assertTrue(contentNode.boundsInRoot.height > requirementsNode.boundsInRoot.height)
        }
        // Avoid repeatedly traversing lazy-list semantics while scroll/layout is in progress.
        requirements.performScrollToIndex(requirementLabels.lastIndex)
        compose.waitForIdle()
        compose.onNodeWithText(requirementLabels.last()).assertIsDisplayed()
        content.assertIsDisplayed()

        compose.onNodeWithText("收起权限与运行要求").performClick()
        requirements.assertDoesNotExist()
        content.assertIsDisplayed()
    }
}
