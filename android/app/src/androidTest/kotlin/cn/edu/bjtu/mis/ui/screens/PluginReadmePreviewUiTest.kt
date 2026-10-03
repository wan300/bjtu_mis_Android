package cn.edu.bjtu.mis.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
        compose.setContent {
            MaterialTheme {
                PluginReadmePreviewDialog(
                    target = PluginReadmeTarget(
                        title = "Plugin README",
                        owner = "alice",
                        repository = "demo",
                        commitSha = "abc1234",
                        requirements = (0..199).map { "Requirement $it" },
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
        assertTrue(content.fetchSemanticsNode().boundsInRoot.height > requirements.fetchSemanticsNode().boundsInRoot.height)
        requirements.performScrollToNode(hasText("Requirement 199"))
        compose.onNodeWithText("Requirement 199").assertIsDisplayed()
        content.assertIsDisplayed()

        compose.onNodeWithText("收起权限与运行要求").performClick()
        requirements.assertDoesNotExist()
        content.assertIsDisplayed()
    }
}
